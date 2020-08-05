package sttp.client.okhttp

import okhttp3.{
  MediaType,
  Headers => OkHttpHeaders,
  MultipartBody => OkHttpMultipartBody,
  RequestBody => OkHttpRequestBody
}
import okio.{BufferedSink, ByteString, Okio}
import sttp.client.{
  BasicRequestBody,
  ByteArrayBody,
  ByteBufferBody,
  FileBody,
  InputStreamBody,
  MultipartBody,
  NoBody,
  RequestBody,
  StreamBody,
  Streams,
  StringBody
}
import sttp.model.{Header, HeaderNames, Part}

import scala.collection.JavaConverters._
import scala.util.Try

trait BodyToOkHttp[F[_], S] {
  val streams: Streams[S]
  def streamToRequestBody(stream: streams.BinaryStream): OkHttpRequestBody

  def apply[R](body: RequestBody[R], ct: Option[String]): Option[OkHttpRequestBody] = {
    val mediaType = ct.flatMap(c => Try(MediaType.parse(c)).toOption).orNull
    body match {
      case NoBody => None
      case StringBody(b, _, _) =>
        Some(OkHttpRequestBody.create(b, mediaType))
      case ByteArrayBody(b, _) =>
        Some(OkHttpRequestBody.create(b, mediaType))
      case ByteBufferBody(b, _) =>
        if (b.isReadOnly) Some(OkHttpRequestBody.create(ByteString.of(b), mediaType))
        else Some(OkHttpRequestBody.create(b.array(), mediaType))
      case InputStreamBody(b, _) =>
        Some(new OkHttpRequestBody() {
          override def writeTo(sink: BufferedSink): Unit =
            sink.writeAll(Okio.source(b))
          override def contentType(): MediaType = mediaType
        })
      case FileBody(b, _) =>
        Some(OkHttpRequestBody.create(b.toFile, mediaType))
      case StreamBody(s) =>
        Some(streamToRequestBody(s.asInstanceOf[streams.BinaryStream]))
      case MultipartBody(ps) =>
        val b = new OkHttpMultipartBody.Builder().setType(Option(mediaType).getOrElse(OkHttpMultipartBody.FORM))
        ps.foreach(addMultipart(b, _))
        Some(b.build())
    }
  }

  private def addMultipart(builder: OkHttpMultipartBody.Builder, mp: Part[BasicRequestBody]): Unit = {
    val allHeaders = mp.headers :+ Header(HeaderNames.ContentDisposition, mp.contentDispositionHeaderValue)
    val headers =
      OkHttpHeaders.of(allHeaders.filterNot(_.is(HeaderNames.ContentType)).map(h => (h.name, h.value)).toMap.asJava)

    apply(mp.body, mp.contentType).foreach(builder.addPart(headers, _))
  }
}
