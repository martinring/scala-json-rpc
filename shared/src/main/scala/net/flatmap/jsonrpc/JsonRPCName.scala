package net.flatmap.jsonrpc

import scala.annotation.ClassfileAnnotation

case class JsonRPC(name: String) extends ClassfileAnnotation
case class JsonRPCNamespace(prefix: String) extends ClassfileAnnotation