package net.flatmap.jsonrpc

import akka.stream.scaladsl.Flow
import io.circe.{Decoder, Encoder}

import scala.annotation.StaticAnnotation
import scala.concurrent.{Future, Promise}
import scala.reflect.macros.blackbox

import scala.language.experimental.macros

/**
  * WIP
  * @param c
  */
class Macros(val c: blackbox.Context) {
  import c.universe._

  sealed trait RPCMethod

  case class RPCRequest(name: String,
                        parameters: Seq[RPCParameter],
                        call: (Tree,Seq[Tree]) => Tree,
                        returnType: Type) extends RPCMethod

  case class RPCNotification(name: String,
                             parameters: Seq[RPCParameter],
                             call: (Tree,Seq[Tree]) => Tree) extends RPCMethod

  case class RPCParameter(name: Name, typeSignature: Type) {
    lazy val matchName = c.freshName(name)
    lazy val decoder = c.inferImplicitValue(c.typeOf[Decoder[Any]].map(_ => typeSignature))
    lazy val encoder = c.inferImplicitValue(c.typeOf[Encoder[Any]].map(_ => typeSignature))
  }

  def inferEncoder(t: Type) = {
    val etype = appliedType(typeOf[Encoder[_]].typeConstructor, t)
    val encoder = c.inferImplicitValue(etype)
    if (encoder.isEmpty) c.abort(c.enclosingPosition, s"Could not find implicit io.circe.Encoder[$t]")
    encoder
  }

  def inferDecoder(t: Type) = {
    val etype = appliedType(typeOf[Decoder[_]].typeConstructor, t)
    val decoder = c.inferImplicitValue(etype)
    if (decoder.isEmpty) c.abort(c.enclosingPosition, s"Could not find implicit io.circe.Decoder[$t]")
    decoder
  }

  def getAnnotationWithStringLiteral[T <: StaticAnnotation : TypeTag](sym: Symbol): Option[String] = {
    // ping info to get consistent annotations
    assert(sym.info != null)
    sym.annotations.map(_.tree).collectFirst {
      case tree@Apply(annon,List(Literal(Constant(name: String))))
        if tree.tpe =:= c.typeOf[T] => name
      case tree if tree.tpe =:= c.typeOf[T] =>
        val tname = weakTypeOf[T].typeSymbol.name.toString
        c.abort(tree.pos, s"Annotations of type $tname may only contain " +
          s"string literal")
    }
  }

  def parameters(m: MethodSymbol): Seq[RPCParameter] = {
    m.paramLists.flatMap { ps =>
      ps.map { param =>
        RPCParameter(param.name, param.typeSignature)
      }
    }
  }

  def callMethod(m: MethodSymbol)(on: Tree, args: Seq[Tree]): Tree = {
    val ns = m.paramLists.map(_.size)
    // make sure the right number of params is supplied
    assert(ns.sum == args.size)
    val (argss,Seq()) = ns.foldLeft((Vector.empty[Seq[Tree]], args)) {
      case ((ps,args),n) =>
        val (xs,ys) = args.splitAt(n)
        (ps :+ xs, ys)
    }
    q"$on.${m.name}(...$argss)"
  }

  def methods(t: c.Type): Seq[RPCMethod] = {
    val abstractMembers = t.decls.filter(_.isAbstract)
    abstractMembers.toSeq.flatMap { member =>
      if (!member.isMethod)
        c.abort(member.pos, "Json RPC Interfaces may not contain abstract" +
          "members other than methods")
      val method = member.asMethod
      val namespace = getAnnotationWithStringLiteral[JsonRPCNamespace](method)
      val customName = getAnnotationWithStringLiteral[JsonRPCMethod](method)
      if (namespace.isDefined && customName.isDefined)
        c.abort(method.pos, "Annotations JsonRPCNamespace and JsonRPCMethod " +
          "may not be present at the same time")
      else namespace.fold {
        val name = customName getOrElse method.name.toString
        val params = parameters(method)
        val call = callMethod(method) _
        if (method.returnType =:= typeOf[Unit])
          Seq(RPCNotification(name,params,call))
        else if (method.returnType <:< typeOf[Future[Any]])
          Seq(RPCRequest(name,params,call,method.returnType.typeArgs.head))
        else
          c.abort(method.pos, "Json RPC Methods may return Unit or Future[_]")
      } { namespace =>
        val nsParams = parameters(method)
        methods(method.returnType).map {
          case RPCNotification(name,params,call) =>
            RPCNotification(
              namespace + name,
              nsParams ++ params,
              (on,args) => {
                val (xs,ys) = args.splitAt(nsParams.size)
                val callNS = callMethod(method)(on,xs)
                call(callNS,ys)
              }
            )
          case RPCRequest(name,params,call,returnType) =>
            RPCRequest(
              namespace + name,
              nsParams ++ params,
              (on,args) => {
                val (xs,ys) = args.splitAt(nsParams.size)
                val callNS = callMethod(method)(on,xs)
                call(callNS,ys)
              },
              returnType
            )
        }
      }
    }
  }

  def deriveLocal[L: WeakTypeTag] = {
    val t = weakTypeOf[L]
    methods(t).map {
      case RPCNotification(name,parameters,call) =>
        val requiredParameters =
          parameters.filter(p => !(p.typeSignature <:< typeOf[Option[Any]]))
                    .map(_.name.toString)
        val args = c.freshName("args")
        val checkRequiredParameters =
          q"Set(..$requiredParameters).subsetOf($args.keySet)"
        val body =
          q""
        cq"""Notification($name,NamedParameters($args)) if $checkRequiredParameters => $body"""
      case RPCRequest(name,parameters,call,t) =>
        val args = c.freshName("args")
        val id = c.freshName("id")
    }
    q""
  }

  def deriveRemote[R: WeakTypeTag](idProvider: c.Expr[Iterable[Id]]) = {
    q""
  }
}

object JsonRPC {
  def local[L]: Flow[RequestMessage,Response,Promise[L]] =
    macro Macros.deriveLocal[L]

  def remote[R](idProvider: Iterable[Id]): Flow[Response,RequestMessage,R] =
    macro Macros.deriveRemote[R]
}