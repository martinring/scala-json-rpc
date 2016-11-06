package net.flatmap.jsonrpc

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl._
import akka.util.{ByteString, Timeout}
import net.flatmap.jsonrpc.util._

import scala.concurrent.ExecutionContext
import scala.util.Try


trait Connection[L <: Interface,R <: Interface] {
  implicit def local: Local[L]
  implicit def remote: Remote[R]
  def close()(implicit timeout: Timeout) = remote.close()
}

object Connection { self =>
  def bidi[L <: Interface,R <: Interface](local: L, remote: R)(impl: Remote[R] => Local[L],
    framing: BidiFlow[String,ByteString,ByteString,String,NotUsed] = Framing.byteString,
    codec: BidiFlow[Message,String,String,Message,NotUsed] = Codec.standard
  )(implicit ec: ExecutionContext): Flow[ByteString,ByteString,Connection[L,R]] = {
    /* construct protocol stack
     *         +------------------------------------+
     *         | stack                              |
     *         |                                    |
     *         |  +-------+            +---------+  |
     *    ~>   O~~o       |     ~>     |         o~~O    ~>
     * Message |  | codec |   String   | framing |  | ByteString
     *    <~   O~~o       |     <~     |         o~~O    <~
     *         |  +-------+            +---------+  |
     *         +-----------------------------------*/
    val stack = codec atop framing

    val r = Remote(remote)

    val l = Flow[RequestMessage].zipWithMat(Source.maybe[Local[L]].expand(Iterator.continually(_))) {
      case (msg,local) =>
        local.messageHandler(msg)
    } (Keep.right).flatMapConcat(Source.fromFuture).collect {
      case Some(msg) => msg
    }

    val handler = GraphDSL.create(l, r) { (l,r) =>
      val localImpl = impl(r)
      l.success(Some(localImpl))
      new Connection[L,R] {
        implicit def local: Local[L] = localImpl
        implicit def remote: Remote[R] = r
      }
    } { implicit b =>
      (local, remote) =>
      import GraphDSL.Implicits._

      val partition =
        b.add(TypePartition[Message,RequestMessage,ResponseMessage])

      val merge     =
        b.add(Merge[Message](2,eagerComplete = true))

      partition.out1 ~> local  ~> merge
      partition.out2 ~> remote ~> merge

      FlowShape(partition.in, merge.out)
    }

    stack.reversed.joinMat(handler)(Keep.right)
  }
}
