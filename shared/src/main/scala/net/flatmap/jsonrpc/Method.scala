package net.flatmap.jsonrpc

import io.circe.{Decoder, Encoder}
import shapeless.HList
import shapeless.UnaryTCConstraint.*->*

sealed trait ReturnType[T]

object ReturnType {
  def apply[T] = new ReturnType[T] {}
}

case class Parameter[T](name: String)
                       (implicit encode: Encoder[T], decode: Decoder[T])

case class Method[PS <: HList : *->*[Parameter]#λ,R]
  (name: String, params: PS, returnType: ReturnType[R])
  (implicit encode: Encoder[R], decode: Decoder[R])