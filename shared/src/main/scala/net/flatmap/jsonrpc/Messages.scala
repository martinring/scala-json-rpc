package net.flatmap.jsonrpc

import io.circe._

import scala.concurrent.Promise

sealed trait Id
object Id {
  case class Long(value: scala.Long) extends Id
  case class String(value: java.lang.String) extends Id
  case object Null extends Id

  def discriminated(n: Int, i: Int): Iterator[Id.Long] =
    Iterator.iterate(Id.Long(i)) { case Id.Long(x) => Id.Long(x + n) }

  def discriminator(n: Int, i: Int): Id => Boolean = {
    case Id.Long(x) if x % n == i => true
    case _ => false
  }

  def discriminator(n: Int): Id => Int = {
    case Id.Long(x) => (x % n).toInt
    case _ => -1
  }

  def standard: Iterator[Id] = discriminated(1,0)
}

sealed trait Message {
  /**
    * A String specifying the version of the JSON-RPC protocol. MUST be exactly
    * "2.0".
    */
  val jsonrpc = "2.0"
}

sealed trait ParameterList
case class PositionedParameters(val params: IndexedSeq[Json]) extends ParameterList
case class NamedParameters(val params: Map[String,Json]) extends ParameterList

sealed trait RequestMessage extends Message {
  /**
    * A String containing the name of the method to be invoked. Method names
    * that begin with the word rpc followed by a period character (U+002E or
    * ASCII 46) are reserved for rpc-internal methods and extensions and MUST
    * NOT be used for anything else.
    */
  val method: String
  def prefixed(prefix: String): RequestMessage = this match {
    case r: ResolveableRequest => r.copy(method = prefix + method)
    case r: Request      => r.copy(method = prefix + method)
    case n: Notification => n.copy(method = prefix + method)
  }
}

case class Request(id: Id, method: String, params: Option[ParameterList])
  extends RequestMessage

private [jsonrpc] case class ResolveableRequest(method: String, params: Option[ParameterList], promise: Promise[Json], id: Option[Id] = None) extends RequestMessage

/**
  * A Notification is a Request object without an "id" member. A Request object
  * that is a Notification signifies the Client's lack of interest in the
  * corresponding Response object, and as such no Response object needs to be
  * returned to the client. The Server MUST NOT reply to a Notification,
  * including those that are within a batch request.
  *
  * Notifications are not confirmable by definition, since they do not have a
  * Response object to be returned. As such, the Client would not be aware of
  * any errors (like e.g. "Invalid params","Internal error").
  * @param method A String containing the name of the method to be invoked.
  *               Method names that begin with the word rpc followed by a period
  *               character (U+002E or ASCII 46) are reserved for rpc-internal
  *               methods and extensions and MUST NOT be used for anything else.
  * @param params A Structured value that holds the parameter values to be used
  *               during the invocation of the method. This member MAY be
  *               omitted.
  */
case class Notification(method: String, params: Option[ParameterList])
  extends RequestMessage

sealed trait Response extends Message {
  /**
    * This member is REQUIRED.
    * It MUST be the same as the value of the id member in the Request Object.
    * If there was an error in detecting the id in the Request object (e.g.
    * Parse error/Invalid Request), it MUST be Null.
    */
  val id: Id
}

object Response {

  /**
    * @param id     If there was an error in detecting the id in the Request
    *               object (e.g. Parse error/Invalid Request), it MUST be Null.
    * @param result The value of this member is determined by the method invoked
    *               on the Server.
    */
  case class Success(id: Id, result: Json) extends Response

  /**
    * @param id If there was an error in detecting the id in the Request
    *           object (e.g. Parse error/Invalid Request), it MUST be Null.
    */
  case class Failure(id: Id, error: ResponseError) extends Response

}

/**
  * When a rpc call encounters an error, the Response Object MUST contain the
  * error member with a value that is a Object with the following members:
  *
  * @param code    A Number that indicates the error type that occurred.
  * @param message A String providing a short description of the error.
  * @param data    A Primitive or Structured value that contains additional
  *                information about the error.
  */
case class ResponseError(
  val code: Int,
  val message: String,
  val data: Option[Json]
) extends Throwable {
  override def getMessage: String = message
}

object ErrorCodes {
  val ParseError = -32700
  val InvalidRequest = -32600
  val MethodNotFound = -32601
  val InvalidParams = -32602
  val InternalError = -32603
  val serverErrorStart = -32099
  val serverErrorEnd = -32000
}