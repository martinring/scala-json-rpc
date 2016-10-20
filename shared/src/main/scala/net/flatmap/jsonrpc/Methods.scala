/*package net.flatmap.jsonrpc

TODO: Is it possible to use this abstraction at all???

import io.circe._
import shapeless._
import shapeless.LUBConstraint._
import shapeless.UnaryTCConstraint._

sealed trait Method
sealed trait Implementation

object Method {
  case class Parameter[T](name: String)(implicit encode: Decoder[T])

  case class Notification[PS <: HList : *->*[Parameter]#λ]
    (name: String, params: PS) extends Method

  object Request {
    def apply[R] = new {
      def apply[PS <: HList : *->*[Parameter]#λ]
        (name: String, params: PS)
        (implicit decode: Decoder[R]) =
          Method.Request[PS,R](name,params)
    }
  }

  case class Request[PS <: HList : *->*[Parameter]#λ,R]
    (name: String, params: PS)(implicit decode: Decoder[R]) extends Method
}

object Implementation {
  case class Argument[T](name: String)(implicit decode: Encoder[T])

  case class Notification[AS <: HList : *->*[Argument]#λ]
  (name: String, impl: AS => Unit) extends Implementation

  case class Request[AS <: HList : *->*[Argument]#λ, R]
  (name: String, impl: AS => R)(implicit encode: Encoder[R]) extends Implementation
}

object Interface {
  case class Local[MS <: HList : <<:[Implementation]#λ](methods: MS)
  case class Remote[MS <: HList : <<:[Method]#λ](methods: MS)
}

object Test {
  val f = Method.Notification("f", Method.Parameter[Int]("foo") :: HNil)
  val g = Method.Request[String]("g", Method.Parameter[Int]("foo") :: HNil)

  val i = Interface.Remote(f :: g :: HNil)
}*/