package net.flatmap.jsonrpc

import akka.NotUsed
import akka.stream.FlowShape
import akka.stream.scaladsl._

import scala.util.control.NonFatal
import scala.util.{Failure, Try}

case class Local[I <: Interface](interface: I) {
  def on[P](notification: interface.NotificationType[P])(body: P => Unit): Flow[RequestMessage,ResponseMessage,NotUsed] =
    Flow[RequestMessage].collect {
      case n: Notification if n.method == notification.name => Try {
        val param = notification.paramDecoder.decodeJson(n.params)
        body(param.toTry.get)
      }
    } .collect {
      case Failure(exception: ResponseError) =>
        Response.Failure(Id.Null,exception)
      case Failure(NonFatal(other)) =>
        Response.Failure(Id.Null,ResponseError(ErrorCodes.InternalError,other.getMessage,None))
    }

  def on[P,R,E](request: interface.RequestType[P,R,E])(body: P => R): Flow[RequestMessage,ResponseMessage,NotUsed] = {
    Flow[RequestMessage].collect {
      case r: Request if r.method == request.name => Try {
        val param = request.paramDecoder.decodeJson(r.params)
        val result = body(param.toTry.get)
        Response.Success(r.id, request.resultEncoder(result))
      }.recover {
        case e: ResponseError => Response.Failure(r.id,e)
        case NonFatal(other) =>
          Response.Failure(r.id,ResponseError(ErrorCodes.InternalError,other.getMessage,None))
      }.get
    }
  }

  on[Unit,Unit,Unit](null) { x =>
    
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