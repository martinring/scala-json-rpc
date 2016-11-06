package net.flatmap.jsonrpc

import akka.stream.scaladsl._

import scala.concurrent.Future

abstract class Local[I <: Interface](val interface: I) {
  implicit val local: Local[I] = this

  val implementation: Set[interface.MethodImplementation]

  lazy val messageHandler = {
    val notificationNames = interface.methods.map(_.name)
    val requestNames      = interface.methods.map(_.name)
    val notFound: PartialFunction[RequestMessage,Future[Option[ResponseMessage]]] = {
      case Request(id,method,_) if requestNames.contains(method) =>
        val err = ResponseError(ErrorCodes.MethodNotFound,s"request method not implemented: $method")
        Future.successful(Some(Response.Failure(id,err)))
      case Notification(method,_) if notificationNames.contains(method) =>
        val err = ResponseError(ErrorCodes.MethodNotFound,s"notification method not implemented: $method")
        Future.successful(Some(Response.Failure(Id.Null,err)))
      case Request(id,method,_) =>
        val err = ResponseError(ErrorCodes.MethodNotFound,s"request method not found: $method")
        Future.successful(Some(Response.Failure(id,err)))
      case Notification(method,_) =>
        val err = ResponseError(ErrorCodes.MethodNotFound,s"notification method not found: $method")
        Future.successful(Some(Response.Failure(Id.Null,err)))
    }
    implementation.map(_.handler).foldLeft(notFound) {
      case (a,b) => b orElse a
    }
  }

  lazy val flow = Flow[RequestMessage].mapAsync(16)(messageHandler).collect {
    case Some(x) => x
  }
}