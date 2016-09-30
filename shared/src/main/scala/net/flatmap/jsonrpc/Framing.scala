package net.flatmap.jsonrpc

import akka.stream.scaladsl._
import java.nio.charset.StandardCharsets

import akka.util.ByteString

import scala.annotation.tailrec


sealed trait FramingState { def append(s: ByteString): FramingState }

case class IncompleteHeader(part: ByteString) extends FramingState {
  def append(x: ByteString) = copy(part = part ++ x)
}
case class IncompleteContent(contentLength: Int, part: ByteString) extends FramingState {
  def append(x: ByteString) = copy(part = part ++ x)
}

object FramingState {
  @tailrec
  def reduceStream(messages: collection.immutable.Seq[String], connectionState: FramingState):
    (collection.immutable.Seq[String], FramingState) = connectionState match {
    case IncompleteHeader(part) =>
      val str = part.decodeString(StandardCharsets.US_ASCII)
      val pos = str.indexOf("\r\n\r\n")
      if (pos > -1) {
        val parts = str.take(pos).split("\r\n")
        val contentLength = parts.find(_.startsWith("Content-Length: ")).map { x =>
          x.drop(16).toInt
        }.get
        val remaining = part.drop(pos + 4)
        reduceStream(messages,IncompleteContent(contentLength, remaining))
      } else {
        (collection.immutable.Seq.empty, IncompleteHeader(part))
      }
    case IncompleteContent(contentLength,part) =>
      if (part.length < contentLength)
        (messages, IncompleteContent(contentLength,part))
      else if (part.length == contentLength)
        (messages :+ part.decodeString(StandardCharsets.UTF_8), IncompleteHeader(ByteString.empty))
      else {
        val (content,next) = part.splitAt(contentLength)
        reduceStream(messages :+ content.decodeString(StandardCharsets.UTF_8), IncompleteHeader(next))
      }
  }
}

object Framing {
  val deframer =
    Flow[ByteString].scan[(collection.immutable.Iterable[String],FramingState)]((collection.immutable.Seq.empty,IncompleteHeader(ByteString.empty))) {
      case ((msgs, state), next) =>
        FramingState.reduceStream(collection.immutable.Seq.empty,state.append(next))
    }.map(_._1).flatMapConcat(Source.apply)

  val framer =
    Flow[String].map(s =>
      ByteString(s"Content-Length: ${s.length}\r\n\r\n",StandardCharsets.US_ASCII.name()) ++
        ByteString(s,StandardCharsets.UTF_8.name()))

  val framing = BidiFlow.fromFlows(framer,deframer)
}