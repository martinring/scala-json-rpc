package net.flatmap.jsonrpc

import akka.NotUsed
import akka.stream.scaladsl._
import scala.language.implicitConversions
import shapeless.ops.hlist.ToTraversable

sealed trait MethodImplementation[MT <: MethodType] {
  def name: String
  val handler: Local.RequestHandler
}

class RequestImplementation[P,R,E,RT <: RequestType[P,R,E]] private [jsonrpc] (
  val request: RequestType[P,R,E],
  val handler: Local.RequestHandler
) extends MethodImplementation[RT] { def name = request.name }

class NotificationImplementation[P,NT <: NotificationType[P]] private [jsonrpc] (
  val notification: NotificationType[P],
  val handler: Local.RequestHandler
) extends MethodImplementation[NT] { def name = notification.name}

import shapeless._
import shapeless.LUBConstraint._

class Implementation[MS <: HList : <<:[MethodType]#λ : IsDistinctConstraint] private (val impls: Seq[MethodImplementation[_]]) {
  val handlers: Seq[Local.RequestHandler] = impls.toList.map(_.handler)

  def and[MT <: MethodType](impl: MethodImplementation[MT])(implicit evidence: BasisConstraint[MT :: HNil, MS]): Implementation[MS] =
    new Implementation[MS](impls :+ impl)
}

object Implementation {
  def apply[MS <: HList : <<:[MethodType]#λ : IsDistinctConstraint]: Implementation[MS] =
    new Implementation[MS](Vector.empty)
}

abstract class Local[MS <: HList : <<:[MethodType]#λ : IsDistinctConstraint](val interface: Interface[MS]) {
  implicit def methodImplToImplementation[MT <: MethodType](method: MethodImplementation[MT])(implicit evidence: BasisConstraint[MT :: HNil, MS]) =
    Implementation[MS].and(method)

  def implement[MT <: MethodType, IS <: MT :: HList](is: IS) =

  def implement[P <: Product]()

  val implementation: Implementation[MS]

  lazy val messageHandler = {
    val notificationNames = interface.methods.toList[MethodType](interface.toTraversable).map(_.name)
    val requestNames      = interface.methods.toList[MethodType](interface.toTraversable).map(_.name)
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
    implementation.handlers.foldLeft(notFound) {
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
}