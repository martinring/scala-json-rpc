package net.flatmap.jsonrpc

import akka.stream.scaladsl.Flow

import scala.annotation.StaticAnnotation
import scala.concurrent.Promise
import scala.language.experimental.macros

object JsonRPC {
  class Named(name: String) extends StaticAnnotation
  class Namespace(prefix: String) extends StaticAnnotation
  class SpreadParam extends StaticAnnotation


  def local[L]: Flow[RequestMessage,Response,Promise[L]] =
    macro Macros.deriveLocal[L]

  def remote[R](idProvider: Iterable[Id]): Flow[Response,RequestMessage,R] =
    macro Macros.deriveRemote[R]
}