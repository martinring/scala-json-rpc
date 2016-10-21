package net.flatmap.jsonrpc

import scala.annotation.StaticAnnotation

case class JsonRPC(name: String) extends StaticAnnotation
case class JsonRPCNamespace(prefix: String) extends StaticAnnotation