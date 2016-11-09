package net.flatmap.jsonrpc

import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.scaladsl.{Flow, Sink}
import akka.util.Timeout
import io.circe.{Decoder, Encoder}
import net.flatmap.jsonrpc.util.CancellableFuture
import shapeless.{Generic, HList}

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

  type NotificationHandler = Sink[RequestMessage.Notification,NotUsed]

  class RequestImplementation[P <: Product,R,E] private [Interface] (
    val request: RequestType[P,R,E],
    val handler: RequestHandler
  ) extends MethodImplementation { def name = request.name }

  case class RequestType[P <: Product,R,E] private [Interface] (val name: String)(
    implicit val paramEncoder: Encoder[P], val paramDecoder: Decoder[P],
    val resultEncoder: Encoder[R], val resultDecoder: Decoder[R],
    val errorEncoder: Encoder[E], val errorDecoder: Decoder[E]) extends MethodType {
    /*def apply[L <: HList, T <: Product](p: T)
      (implicit remote: Remote[self.type],
       timeout: Timeout,
       gen: Generic.Aux[P,L],
       gen2: Generic.Aux[T,L]): CancellableFuture[R] = {
      call(gen.from(gen2.to(p)))
    }*/

    def applyHList[L <: HList](l: L)
      (implicit remote: Remote[self.type],
       timeout: Timeout,
       gen: Generic.Aux[P,L]): CancellableFuture[R] = {
      call(gen.from(l))
    }

    def call(p: P)(implicit remote: Remote[self.type], timeout: Timeout): CancellableFuture[R] =
      remote.call(this)(p)

    def := (body: P => R)(implicit local: Local[self.type]): RequestImplementation[P,R,E] = {
      val handler: RequestHandler = {
        case r: RequestMessage.Request if r.method == name =>
          val param = paramDecoder.decodeJson(r.params)
          param.fold({ failure =>
            val err = ResponseError(ErrorCodes.InvalidParams, failure.message)
            Some(ResponseMessage.Failure(r.id,err))
          }, { param =>
            Try(body(param)).map[Option[ResponseMessage]] { result =>
              Some(ResponseMessage.Success(r.id,resultEncoder(result)))
            } .recover[Option[ResponseMessage]] {
              case err: ResponseError =>
                Some(ResponseMessage.Failure(r.id,err))
              case NonFatal(other) =>
                val err = ResponseError(ErrorCodes.InternalError,other.getMessage)
                Some(ResponseMessage.Failure(r.id,err))
            }.get
          })
      }
      new RequestImplementation[P,R,E](this, handler)
    }
  }

  class NotificationImplementation[P] private [Interface] (
    val notification: NotificationType[P,_],
    val handler: RequestHandler
  ) extends MethodImplementation { def name = notification.name}

  case class NotificationType[P,L <: HList] private [Interface] (val name: String)(
    implicit val paramEncoder: Encoder[P],
    gen: Generic.Aux[P,L],
    val paramDecoder: Decoder[P]) extends MethodType {

    def apply[T](t: T)
      (implicit remote: Remote[self.type],
      gen: Generic.Aux[T,L]): Unit = {
      applyHList(gen.to(t))
    }

    def applyHList(l: L)(implicit remote: Remote[self.type]): Unit = {
      call(gen.from(l))
    }

    def call(p: P)(implicit remote: Remote[self.type]): Unit =
      remote.call(this)(p)

    def forward (actor: ActorRef)(implicit local: Local[self.type]): NotificationImplementation[P] = {
      :=(actor ! _)
    }

    def := (body: P => Unit)(implicit local: Local[self.type]): NotificationImplementation[P] = {
      val handler: RequestHandler = {
        case n: RequestMessage.Notification if n.method == name =>
          val param = paramDecoder.decodeJson(n.params)
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
      new NotificationImplementation[P](this, handler)
    }
  }

  protected def request[P <: Product,R,E](name: String)(implicit paramEncoder: Encoder[P], paramDecoder: Decoder[P],
    resultEncoder: Encoder[R], resultDecoder: Decoder[R],
    errorEncoder: Encoder[E], errorDecoder: Decoder[E]): RequestType[P,R,E] = {
    val method = RequestType[P,R,E](name)
    methods_ += method
    method
  }

  protected class NotificationConstructor[P] {
    def apply[L <: HList](name: String)(
      implicit paramEncoder: Encoder[P],
      gen: Generic.Aux[P,L],
      paramDecoder: Decoder[P]): NotificationType[P,L] = {
      val method = NotificationType[P,L](name)
      methods_ += method
      method
    }
  }

  protected def notification[P] = new NotificationConstructor[P]
}