package net.flatmap.jsonrpc

import akka.NotUsed
import akka.stream.scaladsl._
import shapeless.ops.hlist.{Selector, ToTraversable}

import scala.annotation.implicitNotFound
import scala.language.implicitConversions

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

@implicitNotFound("the implementation contains implementations for methods not contained in the interface")
trait Implements[IS <: HList, MS <: HList] extends Serializable
object Implements {
  type Aux[MS <: HList] = {
    type λ[IS <: HList] = Implements[IS,MS]
  }

  implicit def hnilImplements[MS <: HList] = new Implements[HNil,MS] {}
  implicit def hlistImplements[MT <: MethodType, MS <: HList, IS <: HList]
    (implicit bct: Implements[IS,MS], sel: Selector[MS,MT]) =
    new Implements[MethodImplementation[MT] :: IS,MS] { }
}

object Implementation {
  def apply[MS <: HList : <<:[MethodType]#λ : IsDistinctConstraint]: Implementation[MS] =
    new Implementation[MS](Vector.empty)

  def apply[
    MS <: HList : <<:[MethodType]#λ : IsDistinctConstraint,
    IS <: HList : <<:[MethodImplementation[_]]#λ
  ](is: IS)(implicit ev: Implements[IS,MS], traverse: ToTraversable.Aux[IS,List,MethodImplementation[_]]) =
    new Implementation[MS](is.toList[MethodImplementation[_]])
}

abstract class Local[MS <: HList : <<:[MethodType]#λ : IsDistinctConstraint](val interface: Interface[MS]) {
  implicit def methodImplToImplementation[MT <: MethodType](method: MethodImplementation[MT])(implicit evidence: BasisConstraint[MT :: HNil, MS]) =
    Implementation[MS].and(method)

  def implement[IS <: HList : <<:[MethodImplementation[_]]#λ](is: IS)
    (implicit ev: Implements[IS,MS], traverse: ToTraversable.Aux[IS,List,MethodImplementation[_]]): Implementation[MS] =
    Implementation[MS,IS](is)

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