package net.flatmap.jsonrpc

import io.circe.{Decoder, Encoder}

trait Interface {
  private var methods_ = Set.empty[MethodType]
  def methods = methods_

  sealed trait MethodType { val name: String }
  case class RequestType[P,R,E] private (val name: String)
                                          (implicit
                                           val paramEncoder: Encoder[P], val paramDecoder: Decoder[P],
                                           val resultEncoder: Encoder[R], val resultDecoder: Decoder[R],
                                           val errorEncoder: Encoder[E], val errorDecoder: Decoder[E]) extends MethodType
  case class NotificationType[P] private (val name: String)
                                           (implicit val paramEncoder: Encoder[P], val paramDecoder: Decoder[P]) extends MethodType

  protected def request[P,R,E](name: String)(implicit paramEncoder: Encoder[P], paramDecoder: Decoder[P],
    resultEncoder: Encoder[R], resultDecoder: Decoder[R],
    errorEncoder: Encoder[E], errorDecoder: Decoder[E]): RequestType[P,R,E] = {
    val method = RequestType[P,R,E](name)
    methods_ += method
    method
  }

  protected def notification[P](name: String)(implicit paramEncoder: Encoder[P], paramDecoder: Decoder[P]): NotificationType[P] = {
    val method = NotificationType[P](name)
    methods_ += method
    method
  }
}