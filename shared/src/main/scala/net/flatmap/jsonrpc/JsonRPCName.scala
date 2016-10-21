package net.flatmap.jsonrpc

import scala.annotation.Annotation

case class JsonRPC(name: String) extends Annotation
case class JsonRPCNamespace(prefix: String) extends Annotation