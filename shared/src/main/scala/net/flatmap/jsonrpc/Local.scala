package net.flatmap.jsonrpc

import akka.NotUsed
import akka.stream.FlowShape
import akka.stream.scaladsl._

import scala.util.control.NonFatal
import scala.util.{Failure, Try}

case class Local[I <: Interface](interface: I) {
  def on[P](notification: interface.NotificationType[P])(body: P => Unit): Flow[RequestMessage,ResponseMessage,NotUsed] =
    Flow[RequestMessage].collect[Option[ResponseMessage]] {
      case n: Notification if n.method == notification.name =>
        val param = notification.paramDecoder.decodeJson(n.params)
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
    }.collect{ case Some(x) => x }

  def on[P,R,E](request: interface.RequestType[P,R,E])(body: P => R): Flow[RequestMessage,ResponseMessage,NotUsed] = {
    Flow[RequestMessage].collect {
      case r: Request if r.method == request.name =>
        val param = request.paramDecoder.decodeJson(r.params)
        param.fold({ failure =>
          val err = ResponseError(ErrorCodes.InvalidParams, failure.message)
          Response.Failure(r.id,err)
        }, { param =>
          Try(body(param)).map[ResponseMessage] { result =>
            Response.Success(r.id,request.resultEncoder(result))
          } .recover[ResponseMessage] {
            case err: ResponseError =>
              Response.Failure(r.id,err)
            case NonFatal(other) =>
              val err = ResponseError(ErrorCodes.InternalError,other.getMessage)
              Response.Failure(r.id,err)
          }.get
        })
    }
  }

  def implement[S](methods: Flow[RequestMessage,ResponseMessage,NotUsed]*) = {
    val notificationNames = interface.methods.map(_.name)
    val requestNames      = interface.methods.map(_.name)
    val notFound = Flow[RequestMessage].collect {
      case Request(id,method,_) if !requestNames.contains(method) =>
        Response.Failure(id,ResponseError(ErrorCodes.MethodNotFound,s"request method not found: $method"))
      case Notification(method,_) if !notificationNames.contains(method) =>
        Response.Failure(Id.Null,ResponseError(ErrorCodes.MethodNotFound,s"notification method not found: $method"))
    }
    val methodsAndFallback = methods :+ notFound
    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._
      val broadcast = builder.add(Broadcast[RequestMessage](methodsAndFallback.size))
      val merge = builder.add(Merge[ResponseMessage](methodsAndFallback.size))
      methodsAndFallback.zipWithIndex.foreach { case (method,i) =>
        val m = builder.add(method)
        broadcast.out(i) ~> m.in
        m.out ~> merge.in(i)
      }
      FlowShape(broadcast.in, merge.out)
    })
  }
}