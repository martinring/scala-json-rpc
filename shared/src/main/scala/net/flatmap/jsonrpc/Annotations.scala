package net.flatmap.jsonrpc

import scala.annotation.StaticAnnotation

class JsonRPCMethod(name: String) extends StaticAnnotation
class JsonRPCNamespace(prefix: String) extends StaticAnnotation