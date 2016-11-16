package net.flatmap.jsonrpc

import akka.util.Timeout
import io.circe.{Decoder, Encoder}
import net.flatmap.jsonrpc.util.CancellableFuture
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

  def apply[MS <: HList](p: P)(implicit remote: Remote[MS], evidence: BasisConstraint[this.type :: HNil,MS], timeout: Timeout): CancellableFuture[R] =
    remote.sendRequest[P,R,E](this)(p)

  def := (body: P => R): RequestImplementation[P,R,E,this.type] = {
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
    new RequestImplementation[P,R,E,this.type](this, handler)
  }
}

abstract class NotificationType[P] private [jsonrpc] (val name: String)(
  implicit val paramEncoder: Encoder[P],
  val paramDecoder: Decoder[P]) extends MethodType {

  def apply[MS <: HList](p: P)(implicit remote: Remote[MS], evidence: BasisConstraint[this.type :: HNil,MS], timeout: Timeout): Unit =
    remote.remote.sendNotification[P](this)(p)

  def := (body: P => Unit): NotificationImplementation[P,this.type] = {
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
    new NotificationImplementation[P,this.type](this, handler)
  }
}

import shapeless._
import shapeless.ops.hlist._

class Interface[MS <: HList : LUBConstraint.<<:[MethodType]#λ : IsDistinctConstraint](val methods: MS)
  (implicit val toTraversable: ToTraversable.Aux[MS,List,MethodType]) {

  type Shape = MS

  def and [T <: MethodType](other: T)(
    implicit toTraversable: ToTraversable.Aux[T :: MS,List,MethodType],
    distinct: IsDistinctConstraint[T :: MS]): Interface[T :: MS] = other match {
    case other =>
      new Interface[T :: MS](other :: methods)
  }
}

object Interface {
  def apply[P <: Product, L <: HList](p: P)(
    implicit gen: Generic.Aux[P,L],
    lub: LUBConstraint[L,MethodType],
    distinct: IsDistinctConstraint[L],
    toTraversable: ToTraversable.Aux[L,List,MethodType]
  ) = new Interface(gen.to(p))
}