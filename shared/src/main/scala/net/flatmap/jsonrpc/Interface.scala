package net.flatmap.jsonrpc

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Sink}
import akka.util.Timeout
import io.circe.{Decoder, Encoder}
import net.flatmap.jsonrpc.util.CancellableFuture
import shapeless.Generic
import scala.util.Try
import scala.util.control.NonFatal

trait Interface { self =>
  private var methods_ = Set.empty[MethodType]
  def methods = methods_

  sealed trait MethodType {
    val name: String
  }

  sealed trait MethodImplementation {
    def name: String
    val handler: RequestHandler
  }

  type RequestFlow = Flow[RequestMessage,ResponseMessage,NotUsed]

  type RequestHandler = PartialFunction[RequestMessage,Option[ResponseMessage]]

  type NotificationHandler = Sink[Notification,NotUsed]

  class RequestImplementation[P,R,E] private [Interface] (
    val request: RequestType[P,R,E],
    val handler: RequestHandler
  ) extends MethodImplementation { def name = request.name }

  case class RequestType[P,R,E] private [Interface] (val name: String)(
    implicit val paramEncoder: Encoder[P], val paramDecoder: Decoder[P],
    val resultEncoder: Encoder[R], val resultDecoder: Decoder[R],
    val errorEncoder: Encoder[E], val errorDecoder: Decoder[E]) extends MethodType {
    def apply(p: P)(implicit remote: Remote[self.type], timeout: Timeout): CancellableFuture[R] =
      remote.call(this)(p)
    def := (body: P => R)(implicit local: Local[self.type]): RequestImplementation[P,R,E] = {
      val handler: RequestHandler = {
        case r: Request if r.method == name =>
          val param = paramDecoder.decodeJson(r.params)
          param.fold({ failure =>
            val err = ResponseError(ErrorCodes.InvalidParams, failure.message)
            Some(Response.Failure(r.id,err))
          }, { param =>
            Try(body(param)).map[Option[ResponseMessage]] { result =>
              Some(Response.Success(r.id,resultEncoder(result)))
            } .recover[Option[ResponseMessage]] {
              case err: ResponseError =>
                Some(Response.Failure(r.id,err))
              case NonFatal(other) =>
                val err = ResponseError(ErrorCodes.InternalError,other.getMessage)
                Some(Response.Failure(r.id,err))
            }.get
          })
      }
      new RequestImplementation[P,R,E](this, handler)
    }
  }

  class NotificationImplementation[P] private [Interface] (
    val notification: NotificationType[P],
    val handler: RequestHandler
  ) extends MethodImplementation { def name = notification.name}

  case class NotificationType[P] private [Interface] (val name: String)(
    implicit val paramEncoder: Encoder[P],
    val paramDecoder: Decoder[P]) extends MethodType {
    def apply(p: P)(implicit remote: Remote[self.type]): Unit =
      remote.call(this)(p)
    def := (body: P => Unit)(implicit local: Local[self.type]): NotificationImplementation[P] = {
      val handler: RequestHandler = {
        case n: Notification if n.method == name =>
          val param = paramDecoder.decodeJson(n.params)
          param.fold[Option[ResponseMessage]]({ failure =>
            val err = ResponseError(ErrorCodes.InvalidParams, failure.message)
            Some(Response.Failure(Id.Null, err))
          }, { param =>
            Try(body(param)).map(_ => None).recover[Option[ResponseMessage]] {
              case err: ResponseError =>
                Some(Response.Failure(Id.Null, err))
              case NonFatal(other) =>
                val err = ResponseError(ErrorCodes.InternalError, other.getMessage)
                Some(Response.Failure(Id.Null, err))
            }.get
          })
      }
      new NotificationImplementation[P](this, handler)
    }
  }

  protected def request[P,R,E](name: String)(implicit paramEncoder: Encoder[P], paramDecoder: Decoder[P],
    resultEncoder: Encoder[R], resultDecoder: Decoder[R],
    errorEncoder: Encoder[E], errorDecoder: Decoder[E]): RequestType[P,R,E] = {
    val method = RequestType[P,R,E](name)
    methods_ += method
    method
  }

  protected def notification[P](name: String)(implicit paramEncoder: Encoder[P], paramDecoder: Decoder[P]): NotificationType[P] = {
    val method = NotificationType[P](name)
    methods_ += method
    method
  }
}