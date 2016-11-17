package net.flatmap.jsonrpc

import akka.util.Timeout
import io.circe.{Decoder, Encoder}
import net.flatmap.jsonrpc.util.CancellableFuture
import shapeless.LUBConstraint.<<:
import shapeless._

import scala.util.Try
import scala.util.control.NonFatal

sealed trait MethodType {
  val name: String
}

abstract class RequestType[P,R,E] private [jsonrpc] (val name: String)(
  implicit val paramEncoder: Encoder[P], val paramDecoder: Decoder[P],
  val resultEncoder: Encoder[R], val resultDecoder: Decoder[R],
  val errorEncoder: Encoder[E], val errorDecoder: Decoder[E]) extends MethodType {

  type Param = P
  type Result = R
  type Error = E

  def apply[MS <: HList](p: P)(implicit remote: Remote[MS], evidence: RemoteFor[MS,this.type], timeout: Timeout): CancellableFuture[R] =
    remote.sendRequest[P,R,E](this)(p)

  def := (body: P => R): RequestImplementation[this.type] = {
    val handler: Local.RequestHandler = {
      case r: RequestMessage.Request if r.method == this.name =>
        val param = this.paramDecoder.decodeJson(r.params)
        param.fold({ failure =>
          val err = ResponseError(ErrorCodes.InvalidParams, failure.message)
          Some(ResponseMessage.Failure(r.id,err))
        }, { param =>
          Try(body(param)).map[Option[ResponseMessage]] { result =>
            Some(ResponseMessage.Success(r.id,this.resultEncoder(result)))
          } .recover[Option[ResponseMessage]] {
            case err: ResponseError =>
              Some(ResponseMessage.Failure(r.id,err))
            case NonFatal(other) =>
              val err = ResponseError(ErrorCodes.InternalError,other.getMessage)
              Some(ResponseMessage.Failure(r.id,err))
          }.get
        })
    }
    new RequestImplementation[this.type](this, handler)
  }
}

abstract class NotificationType[P] private [jsonrpc] (val name: String)(
  implicit val paramEncoder: Encoder[P],
  val paramDecoder: Decoder[P]) extends MethodType {

  def apply[MS <: HList](p: P)(implicit remote: Remote[MS], evidence: RemoteFor[MS,this.type], timeout: Timeout): Unit =
    remote.sendNotification[P](this)(p)

  def := (body: P => Unit): NotificationImplementation[this.type] = {
    val handler: Local.RequestHandler = {
      case n: RequestMessage.Notification if n.method == this.name =>
        val param = this.paramDecoder.decodeJson(n.params)
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
    new NotificationImplementation[this.type](this, handler)
  }
}

import shapeless._
import shapeless.ops.hlist._

class Interface[MS <: HList](val methods: MS)
  (implicit val toTraversable: ToTraversable.Aux[MS,List,MethodType],
   implicit val mslub: LUBConstraint[MS,MethodType],
   implicit val distinct: IsDistinctConstraint[MS]) {

  type Shape = MS
  type Remote = net.flatmap.jsonrpc.Remote[MS]
  type Local = net.flatmap.jsonrpc.Local[MS]
  type Implementation = net.flatmap.jsonrpc.Implementation[MS]

  def and [MS2 <: HList]
    (other: Interface[MS2])
    (implicit prepend: Prepend[MS2,MS],
     traversable: ToTraversable.Aux[Prepend[MS2,MS]#Out,List,MethodType],
     lub: LUBConstraint[Prepend[MS2,MS]#Out,MethodType],
     distinct: IsDistinctConstraint[Prepend[MS2,MS]#Out]) =
    new Interface((other.methods ++ this.methods) : Prepend[MS2,MS]#Out)

  def implement[P <: Product, IS <: HList](is: P)(
     implicit
     gen: Generic.Aux[P,IS],
     ev: Implements[IS,MS],
     lub: LUBConstraint[IS,MethodImplementation[_]],
     traverse: ToTraversable.Aux[IS,List,MethodImplementation[_]]): Implementation =
    Implementation[MS,IS](gen.to(is))
}

object Interface {
  def apply() = new Interface[HNil](HNil)

  def apply[MT <: MethodType](single: MT): Interface[MT :: HNil] =
    new Interface[MT :: HNil](single :: HNil)

  def apply[P <: Product, MS <: HList](p: P)(
    implicit
    gen: Generic.Aux[P,MS],
    lub: LUBConstraint[MS,MethodType],
    distinct: IsDistinctConstraint[MS],
    toTraversable: ToTraversable.Aux[MS,List,MethodType]
  ): Interface[MS] = new Interface(gen.to(p))
}