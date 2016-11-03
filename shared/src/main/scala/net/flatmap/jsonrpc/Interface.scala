package net.flatmap.jsonrpc

import io.circe.{Decoder, Encoder}


trait Interface {
  case class RequestType[P,R,E] protected (val name: String)
                                          (implicit
                                           val paramEncoder: Encoder[P], val paramDecoder: Decoder[P],
                                           val resultEncoder: Encoder[R], val resultDecoder: Decoder[R],
                                           val errorEncoder: Encoder[E], val errorDecoder: Decoder[E])
  case class NotificationType[P] protected (val name: String)
                                           (implicit val paramEncoder: Encoder[P], val paramDecoder: Decoder[P])
}