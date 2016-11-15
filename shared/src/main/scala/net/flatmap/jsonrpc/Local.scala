package net.flatmap.jsonrpc

import akka.NotUsed
import akka.event.Logging
import akka.stream.scaladsl._

import scala.util.Try
import scala.util.control.NonFatal

sealed trait MethodImplementation[I <: Interface] {
  def name: String
  val handler: Local.RequestHandler
}

class RequestImplementation[P,R,E,I <: Interface] private [jsonrpc] (
  val request: RequestType[P,_,R,E,I],
  val handler: Local.RequestHandler
) extends MethodImplementation[I] { def name = request.name }

class NotificationImplementation[P,I <: Interface] private [jsonrpc] (
  val notification: NotificationType[P,_,I],
  val handler: Local.RequestHandler
) extends MethodImplementation[I] { def name = notification.name}

case class Implementation[I <: Interface]()

abstract class Local[I <: Interface](val interface: I) {
  implicit class ImplementableRequestType[P,R,E](request: RequestType[P,_,R,E,I]) {
    def := (body: P => R): RequestImplementation[P,R,E,I] = {
      val handler: Local.RequestHandler = {
        case r: RequestMessage.Request if r.method == request.name =>
          val param = request.paramDecoder.decodeJson(r.params)
          param.fold({ failure =>
            val err = ResponseError(ErrorCodes.InvalidParams, failure.message)
            Some(ResponseMessage.Failure(r.id,err))
          }, { param =>
            Try(body(param)).map[Option[ResponseMessage]] { result =>
              Some(ResponseMessage.Success(r.id,request.resultEncoder(result)))
            } .recover[Option[ResponseMessage]] {
              case err: ResponseError =>
                Some(ResponseMessage.Failure(r.id,err))
              case NonFatal(other) =>
                val err = ResponseError(ErrorCodes.InternalError,other.getMessage)
                Some(ResponseMessage.Failure(r.id,err))
            }.get
          })
      }
      new RequestImplementation[P,R,E,I](request, handler)
    }
  }

  implicit class ImplementableNotificationType[P](notification: NotificationType[P,_,I]) {
    def := (body: P => Unit): NotificationImplementation[P,I] = {
      val handler: Local.RequestHandler = {
        case n: RequestMessage.Notification if n.method == notification.name =>
          val param = notification.paramDecoder.decodeJson(n.params)
          param.fold[Option[ResponseMessage]]({ failure =>
            val err = ResponseError(ErrorCodes.InvalidParams, failure.message)
            Some(ResponseMessage.Failure(Id.Null, err))
          }, { param =>
            Try(body(param)).map(_ => None).recover[Option[ResponseMessage]] {
              case err: ResponseError =>
                Some(ResponseMessage.Failure(Id.Null, err))
              case NonFatal(other) =>
                val err = ResponseError(ErrorCodes.InternalError, other.getMessage)
                Some(ResponseMessage.Failure(Id.Null, err))
            }.get
          })
      }
      new NotificationImplementation[P,I](notification, handler)
    }
  }

  case class Implementation(impls: MethodImplementation[I]*)

  val implementation: Implementation

  lazy val messageHandler = {
    val notificationNames = interface.methods.map(_.name)
    val requestNames      = interface.methods.map(_.name)
    val notFound: PartialFunction[RequestMessage,Option[ResponseMessage]] = {
      case RequestMessage.Request(id,method,_) if requestNames.contains(method) =>
        val err = ResponseError(ErrorCodes.MethodNotFound,s"request method not implemented: $method")
        Some(ResponseMessage.Failure(id,err))
      case RequestMessage.Notification(method,_) if notificationNames.contains(method) =>
        val err = ResponseError(ErrorCodes.MethodNotFound,s"notification method not implemented: $method")
        Some(ResponseMessage.Failure(Id.Null,err))
      case RequestMessage.Request(id,method,_) =>
        val err = ResponseError(ErrorCodes.MethodNotFound,s"request method not found: $method")
        Some(ResponseMessage.Failure(id,err))
      case RequestMessage.Notification(method,_) =>
        val err = ResponseError(ErrorCodes.MethodNotFound,s"notification method not found: $method")
        Some(ResponseMessage.Failure(Id.Null,err))
    }
    implementation.impls.map(_.handler).foldLeft(notFound) {
      case (a,b) => b orElse a
    }
  }

  lazy val flow: Flow[RequestMessage,ResponseMessage,NotUsed] = Flow[RequestMessage].map(messageHandler).collect {
    case Some(x) => x
  }
}

object Local {
  type RequestFlow = Flow[RequestMessage,ResponseMessage,NotUsed]
  type RequestHandler = PartialFunction[RequestMessage,Option[ResponseMessage]]

  def apply[I <: Interface](interface: I)(impls: MethodImplementation[I]*) = new Local[I](interface) {
    val implementation: Implementation = Implementation(impls :_*)
  }
}