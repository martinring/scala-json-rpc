package net.flatmap.jsonrpc

import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Sink, SinkQueueWithCancel, Source, SourceQueueWithComplete}
import io.circe.{Decoder, Encoder, Json}
import org.reactivestreams.{Processor, Publisher, Subscriber, Subscription}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Success, Try}

trait Interface {
  case class RequestType[P,R,E] protected (val name: String)
                                          (implicit
                                           val paramEncoder: Encoder[P], val paramDecoder: Decoder[P],
                                           val resultEncoder: Encoder[R], val resultDecoder: Decoder[R],
                                           val errorEncoder: Encoder[E], val errorDecoder: Decoder[E])
  case class NotificationType[P] protected (val name: String)
                                           (implicit val paramEncoder: Encoder[P], val paramDecoder: Decoder[P])
}

class Remote[I <: Interface] private (val interface: I,
                                      sink: Publisher[ResponseMessage],
                                      source: SourceQueueWithComplete[RequestMessage],
                                      ids: Iterator[Id])(implicit ec: ExecutionContext, mat: Materializer) {

  private val promises = collection.mutable.Map.empty[Id,Either[ResponseError,Json] => Unit]
  private var closed   = false

  private def init() = {
    Source.fromPublisher(sink).takeWhile(_ => !closed).runForeach {
      case response =>
        promises.synchronized(promises.remove(response.id)).fold {
          if (response.id == Id.Null) {
            // Not bound to a request/notification
          }
          else {
            // Unknown request... This is bad!
          }
        } { promise =>
          promise(response match {
            case Response.Success(_,json) => Right(json)
            case Response.Failure(_,error) => Left(error)
          })
        }
    }
  }

  implicit class callableRequestType[P,R,E](rt: interface.RequestType[P,R,E]) {
    def apply(p: P): Future[Either[E,R]] = {
      val id = ids.synchronized(ids.next())
      val promise = Promise[Either[E,R]]
      val f: Either[ResponseError,Json] => Unit = (either: Either[ResponseError,Json]) =>
        promise.complete( either match {
          case Right(json) =>
            rt.resultDecoder.decodeJson(json).toTry.map[Either[E,R]](Right(_))
          case Left(error) =>
            error.data
              .flatMap(rt.errorDecoder.decodeJson(_).toOption)
              .map[Try[Either[E,R]]](x => Success(Left(x)))
              .getOrElse(scala.util.Failure(error))
        } )
      promises.synchronized(promises += id -> f)
      source.offer(Request(id,rt.name,rt.paramEncoder(p)))
      promise.future
    }
  }

  implicit class callableNotificationType[P](nt: interface.NotificationType[P]) {
    def apply(p: P): Unit = {
      source.offer(Notification(nt.name,nt.paramEncoder(p)))
    }
  }

  def close() = if (!closed) {
    closed = true
    source.complete()
  }
}

object Remote {
  def apply[I <: Interface](interface: I, idProvider: Iterator[Id])
                           (implicit ec: ExecutionContext, mat: Materializer): Flow[ResponseMessage,RequestMessage,Remote[I]] = {
    val sink = Sink.asPublisher[ResponseMessage](true)
    val source = Source.queue[RequestMessage](bufferSize = 128, OverflowStrategy.backpressure)
    Flow.fromSinkAndSourceMat(sink,source)(
      (sink,source) => new Remote[I](interface,sink,source,idProvider)
    )
  }
}