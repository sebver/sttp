package sttp.client.okhttp.monix

import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue

import cats.effect.Resource
import monix.eval.Task
import monix.execution.Ack.Continue
import monix.execution.{Ack, Scheduler}
import monix.reactive.Observable
import monix.reactive.observers.Subscriber
import okhttp3.{MediaType, OkHttpClient, RequestBody => OkHttpRequestBody}
import okio.BufferedSink
import sttp.client.impl.monix.{MonixStreams, TaskMonadAsyncError}
import sttp.client.okhttp.OkHttpBackend.EncodingHandler
import sttp.client.okhttp.{OkHttpAsyncBackend, OkHttpBackend, WebSocketHandler}
import sttp.client.testing.SttpBackendStub
import sttp.client.{SttpBackend, _}

import scala.concurrent.Future
import scala.util.{Success, Try}

class OkHttpMonixBackend private (client: OkHttpClient, closeClient: Boolean, customEncodingHandler: EncodingHandler)(
    implicit s: Scheduler
) extends OkHttpAsyncBackend[Task, MonixStreams, MonixStreams](
      client,
      TaskMonadAsyncError,
      closeClient,
      customEncodingHandler
    ) {
  override val streams: MonixStreams = MonixStreams

  override def streamToRequestBody(stream: Observable[ByteBuffer]): Option[OkHttpRequestBody] =
    Some(new OkHttpRequestBody() {
      override def writeTo(sink: BufferedSink): Unit =
        toIterable(stream) map (_.array()) foreach sink.write
      override def contentType(): MediaType = null
    })

  override def responseBodyToStream(responseBody: InputStream): Try[Observable[ByteBuffer]] =
    Success(
      Observable
        .fromInputStream(Task.now(responseBody))
        .map(ByteBuffer.wrap)
        .guaranteeCase(_ => Task(responseBody.close()))
    )

  private def toIterable[T](observable: Observable[T])(implicit s: Scheduler): Iterable[T] =
    new Iterable[T] {
      override def iterator: Iterator[T] =
        new Iterator[T] {
          case object Completed extends Exception

          val blockingQueue = new ArrayBlockingQueue[Either[Throwable, T]](1)

          observable.executeAsync.subscribe(new Subscriber[T] {
            override implicit def scheduler: Scheduler = s

            override def onError(ex: Throwable): Unit = {
              blockingQueue.put(Left(ex))
            }

            override def onComplete(): Unit = {
              blockingQueue.put(Left(Completed))
            }

            override def onNext(elem: T): Future[Ack] = {
              blockingQueue.put(Right(elem))
              Continue
            }
          })

          var value: T = _

          override def hasNext: Boolean =
            blockingQueue.take() match {
              case Left(Completed) => false
              case Right(elem) =>
                value = elem
                true
              case Left(ex) => throw ex
            }

          override def next(): T = value
        }
    }
}

object OkHttpMonixBackend {
  private def apply(client: OkHttpClient, closeClient: Boolean, customEncodingHandler: EncodingHandler)(implicit
      s: Scheduler
  ): SttpBackend[Task, MonixStreams, WebSocketHandler] =
    new FollowRedirectsBackend(new OkHttpMonixBackend(client, closeClient, customEncodingHandler)(s))

  def apply(
      options: SttpBackendOptions = SttpBackendOptions.Default,
      customEncodingHandler: EncodingHandler = PartialFunction.empty
  )(implicit
      s: Scheduler = Scheduler.global
  ): Task[SttpBackend[Task, MonixStreams, WebSocketHandler]] =
    Task.eval(
      OkHttpMonixBackend(
        OkHttpBackend.defaultClient(DefaultReadTimeout.toMillis, options),
        closeClient = true,
        customEncodingHandler
      )(s)
    )

  def resource(
      options: SttpBackendOptions = SttpBackendOptions.Default,
      customEncodingHandler: EncodingHandler = PartialFunction.empty
  )(implicit
      s: Scheduler = Scheduler.global
  ): Resource[Task, SttpBackend[Task, MonixStreams, WebSocketHandler]] =
    Resource.make(apply(options, customEncodingHandler))(_.close())

  def usingClient(
      client: OkHttpClient,
      customEncodingHandler: EncodingHandler = PartialFunction.empty
  )(implicit s: Scheduler = Scheduler.global): SttpBackend[Task, MonixStreams, WebSocketHandler] =
    OkHttpMonixBackend(client, closeClient = false, customEncodingHandler)(s)

  /**
    * Create a stub backend for testing, which uses the [[Task]] response wrapper, and supports `Observable[ByteBuffer]`
    * streaming.
    *
    * See [[SttpBackendStub]] for details on how to configure stub responses.
    */
  def stub: SttpBackendStub[Task, Observable[ByteBuffer], WebSocketHandler] = SttpBackendStub(TaskMonadAsyncError)
}
