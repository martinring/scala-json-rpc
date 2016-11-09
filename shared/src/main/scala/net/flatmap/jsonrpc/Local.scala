package net.flatmap.jsonrpc

import akka.stream.scaladsl._

abstract class Local[I <: Interface](val interface: I) {
  implicit val local: Local[I] = this

  val implementation: Set[interface.MethodImplementation]

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
    implementation.map(_.handler).foldLeft(notFound) {
      case (a,b) => b orElse a
    }
  }

  lazy val flow = Flow[RequestMessage].map(messageHandler).collect {
    case Some(x) => x
  }
}