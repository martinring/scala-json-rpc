package net.flatmap.jsonrpc

import akka.NotUsed
import akka.actor.{Actor, ActorRef}
import akka.stream._
import akka.stream.actor.{ActorPublisher, ActorSubscriber, OneByOneRequestStrategy, RequestStrategy}
import akka.stream.scaladsl._
import io.circe.{Decoder, Encoder, Json}

import scala.collection.immutable.Seq
import scala.concurrent.{Future, Promise}
import scala.language.experimental.macros
import scala.reflect.macros.whitebox.Context

object Local {
  def buildFlow(f: PartialFunction[RequestMessage,Option[Future[Response]]]) =
    Flow[RequestMessage].map(f).collect({
      case Some(json) => json
    }).flatMapMerge(128,Source.fromFuture)

  def apply[T](impl: T): Flow[RequestMessage,Response,NotUsed] = macro applyImpl[T]
  def applyImpl[T: c.WeakTypeTag](c: Context)(impl: c.Expr[T]) = {
    import c.universe._
    val t = c.weakTypeOf[T]
    val cases = c.weakTypeOf[T].decls.filter(x => x.isMethod && x.isAbstract).map(_.asMethod).map { m =>
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
        val n = c.freshName(x.name.toTermName)
        val t = x.typeSignature
        (x.pos,x.name.toString,n,t)
      }))

      val argMatch = paramss.flatten.map(_._2)
      val argMatchNamed = argMatch.map(x => pq"${x.toString} -> $x")

      val args = c.freshName[TermName]("args")

      val paramsDecoded = paramss.map(_.map {
        case (pos,xn,n,t) =>
          q"implicitly[io.circe.Decoder[$t]].decodeJson($args($xn)).toTry.get"
      })

      if (m.returnType =:= c.typeOf[Unit]) {
        cq"net.flatmap.jsonrpc.Notification(${name.toString}, Some(net.flatmap.jsonrpc.NamedParameters($args))) => $impl.$name(...$paramsDecoded); None"
      } else {
        val rt = m.returnType.typeArgs.head
        val id = c.freshName[TermName]("id")
        val res = q"Some($impl.$name(...$paramsDecoded).map((x) => net.flatmap.jsonrpc.Response.Success($id,implicitly[io.circe.Encoder[$rt]].apply(x))))"
        cq"net.flatmap.jsonrpc.Request($id, ${name.toString}, Some(net.flatmap.jsonrpc.NamedParameters($args))) => $res"
        //q"case net.flatmap.jsonrpc.Request(${name.toString}, net.flatmap.jsonrpc.NamedParameters(..$argMatchNamed)) => $impl.$name(...$paramsDecoded)"
      }
    }
    q"""net.flatmap.jsonrpc.Local.buildFlow {
          case ..$cases
        }"""
  }
}

case class RemoteShape(responses: Inlet[Response], ids: Inlet[Id], requests: Outlet[RequestMessage]) extends Shape {
  override def inlets: Seq[Inlet[_]] = responses :: ids :: Nil
  override def outlets: Seq[Outlet[_]] = requests :: Nil
  override def deepCopy(): Shape =
    RemoteShape(responses.carbonCopy(),ids.carbonCopy(),requests.carbonCopy())
  override def copyFromPorts(inlets: Seq[Inlet[_]], outlets: Seq[Outlet[_]]): Shape =
    RemoteShape(inlets(0).as[Response],inlets(1).as[Id],outlets(0).as[RequestMessage])
}

object Remote {
  def buildFlow[T](derive: (Request => Future[Json], Notification => Unit) => T): Flow[Response,RequestMessage,T] = {
    val requests = Source.actorRef[RequestMessage](bufferSize = 1024, OverflowStrategy.fail)

    val idProvider = Flow[RequestMessage].scan((0l,Option.empty[RequestMessage]))({
      case ((id,_),ResolveableRequest(method,params,promise,None)) => (id + 1, Some(ResolveableRequest(method,params,promise,Some(Id.Long(id)))))
      case ((id,_),other) => (id,Some(other))
    }).collect { case (id,Some(x)) => x }

    val idRequests = requests.viaMat(idProvider)(Keep.left)

    val reqCycle =
      Flow[Message].scan(Map.empty[Id,Promise[Json]],Option.empty[RequestMessage])({
        case ((pending,_),ResolveableRequest(method,params,promise,Some(id))) =>
          (pending + (id -> promise), Some(Request(id,method,params)))
        case ((pending,_),other: RequestMessage) =>
          (pending, Some(other))
        case ((pending,_),Response.Success(id,result)) =>
          pending.get(id).fold {
            // TODO: not waiting for this response
          } { p =>
            p.success(result)
          }
          (pending - id, None)
        case ((pending,_),Response.Failure(id,result)) =>
          pending.get(id).fold {
            // TODO: not waiting for this response
          } { p =>
            p.failure(result)
          }
          (pending - id, None)
      }).map(_._2).collect[RequestMessage] { case Some(x) => x }

    val flow = Flow[Response].mergeMat(idRequests)(Keep.right).viaMat(reqCycle)(Keep.left)

    flow.mapMaterializedValue { ref =>
      derive({
        case Request(id,method,params) =>
          val p = Promise[Json]
          ref ! ResolveableRequest(method,params,p)
          p.future
      }, ref ! _)
    }
  }

  def apply[T]: Flow[Response,RequestMessage,T] = macro applyImpl[T]

  def applyImpl[T: c.WeakTypeTag](c: Context) = {
    import c.universe._
    val t = c.weakTypeOf[T]
    val sendNotification = c.freshName[TermName]("sendNotification")
    val sendRequest = c.freshName[TermName]("sendRequest")
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
        q"$sendNotification($msg)"
      } else {
        val t = m.returnType.typeArgs.head
        val msg = q"net.flatmap.jsonrpc.Request(net.flatmap.jsonrpc.Id.Null, ${name.toString}, $params)"
        q"$sendRequest($msg).map(implicitly[io.circe.Decoder[$t]].decodeJson).map(_.toTry.get)"
      }

      val returnType = m.returnType
      q"override def $name(...$paramdecls) = $body"
    }
    q"""net.flatmap.jsonrpc.Remote.buildFlow {
          case ($sendRequest,$sendNotification) => new $t { ..$impls }
        }"""
  }
}