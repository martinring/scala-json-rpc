package net.flatmap.jsonrpc

import akka.actor.ActorRef
import akka.stream.scaladsl.Flow

import scala.concurrent.Future
import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context

object Remote {
  def apply[T]: Flow[Response,RequestMessage,T] = ??? // macro derriveImplementation[T]

  /*
  def derriveImplementation[T: c.WeakTypeTag](c: Context) = {
    import c.universe._
    val t = c.weakTypeOf[T]
    val impls = c.weakTypeOf[T].decls.filter(x => x.isMethod && x.isAbstract).map(_.asMethod).map { m =>
      val name = m.name

      if (m.isOverloaded)
        c.error(m.pos, "json rpc methods may not be overloaded")
      if (!m.isPublic)
        c.error(m.pos, "json rpc methods must be public")
      if (!m.typeParams.isEmpty)
        c.error(m.pos, "json rpc methods may not use type parameters")
      if (!(m.returnType =:= c.typeOf[Unit] || m.returnType <:< c.typeOf[Future[Any]]))
        c.error(m.pos, "json rpc methods can either return Unit or Future[T]")

      val paramss = m.paramLists.map(_.map({ x =>
        val n = x.name.toTermName
        val t = x.typeSignature
        (x.pos,n,t)
      }))

      val paramdecls = paramss.map(_.map({ case (pos,n,t) => q"$n: $t"}))

      val args = paramss.flatten.map {
        case (pos,n,t) =>
          val s = n.toString
          q"$s -> implicitly[io.circe.Encoder[$t]].apply($n)"
      }

      val params = q"Some(net.flatmap.jsonrpc.NamedParameters(scala.collection.immutable.Map(..$args)))"

      val body = if (m.returnType =:= c.typeOf[Unit]) {
        val msg = q"net.flatmap.jsonrpc.Notification(${name.toString}, $params)"
        q"$connection.send($msg)"
      } else {
        val decl = q"val id = $connection.uniqueId"
        val msg = q"net.flatmap.jsonrpc.Request(id,${name.toString}, $params)"
        q"$decl; $connection.send($msg); null"
      }


      val returnType = m.returnType
      q"override def $name(...$paramdecls) = $body"
    }
    q"new $t { ..$impls }"
  }*/
}