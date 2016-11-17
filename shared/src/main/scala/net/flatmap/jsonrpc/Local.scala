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

class RequestImplementation[RT <: RequestType[_,_,_]] private [jsonrpc] (
  val request: RequestType[_,_,_],
  val handler: Local.RequestHandler
) extends MethodImplementation[RT] { def name = request.name }

class NotificationImplementation[NT <: NotificationType[_]] private [jsonrpc] (
  val notification: NotificationType[_],
  val handler: Local.RequestHandler
) extends MethodImplementation[NT] { def name = notification.name}

import shapeless._
import shapeless.LUBConstraint._

/**
  * Type class witnessing that every element of IS is of the form
  * MethodImplementation[MT] where MT is an element of MS
  */
@implicitNotFound("the implementation contains implementations for methods not contained in the interface")
trait Implements[-IS <: HList, MS <: HList] extends Serializable
object Implements {
  def apply[IS <: HList, MS <: HList](implicit i: Implements[IS,MS]): Implements[IS,MS] = i

  type Aux[MS <: HList] = {
    type λ[IS <: HList] = Implements[IS,MS]
  }

  implicit def hnilImplements[MS <: HList] =
    new Implements[HNil,MS] {}
  implicit def hlistImplements[MT <: MethodType, MS <: HList, IS <: HList]
    (implicit bct: Implements[IS,MS], sel: Selector[MS,MT]) =
    new Implements[MethodImplementation[MT] :: IS,MS] { }
}

class Implementation[MS <: HList] private (val impls: Seq[MethodImplementation[_]]) {
  val handlers: Seq[Local.RequestHandler] = impls.toList.map(_.handler)

  def and[MT <: MethodType](impl: MethodImplementation[MT])(implicit evidence: BasisConstraint[MT :: HNil, MS]): Implementation[MS] =
    new Implementation[MS](impls :+ impl)

  def and[MS2 <: HList : <<:[MethodType]#λ : BasisConstraint.Basis[MS]#λ](impl: Implementation[MS2]): Implementation[MS] =
    new Implementation[MS](impls ++ impl.impls)
}

object Implementation {
  def apply[MS <: HList]: Implementation[MS] =
    new Implementation[MS](Vector.empty)

  def apply[
    MS <: HList,
    IS <: HList : <<:[MethodImplementation[_]]#λ
  ](is: IS)(implicit ev: Implements[IS,MS], traverse: ToTraversable.Aux[IS,List,MethodImplementation[_]]) =
    new Implementation[MS](is.toList[MethodImplementation[_]])
}

abstract class Local[MS <: HList](val interface: Interface[MS]) {
  implicit def methodImplToImplementation[MT <: MethodType](method: MethodImplementation[MT])(implicit evidence: BasisConstraint[MT :: HNil, MS]) =
    Implementation[MS].and(method)(evidence)

  val implementation: interface.Implementation

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