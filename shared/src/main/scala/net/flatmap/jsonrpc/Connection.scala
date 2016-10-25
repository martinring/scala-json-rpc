package net.flatmap.jsonrpc

import akka.{Done, NotUsed}
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import io.circe._
import net.flatmap.jsonrpc.util._

import scala.concurrent.Promise
import scala.util.Try

trait Connection[L,R] {
  def local: L
  def remote: R
  def close()
}

object Connection { self =>
  def bidi[L,R <: RemoteConnection](local: Flow[RequestMessage,Response,Promise[Option[L]]],
                remote: Flow[Response,RequestMessage,R],
                impl: R => L,
                framing: BidiFlow[String,ByteString,ByteString,String,NotUsed] = Framing.byteString,
                codec: BidiFlow[Message,String,String,Message,NotUsed] = Codec.standard
  ): Flow[ByteString,ByteString,Connection[L,R]] = {
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

    val end = Source.maybe[Nothing]

    val handler = GraphDSL.create(local, remote, end) { (l,r,end) =>
      val localImpl = impl(r)
      l.tryComplete(Try(Some(localImpl)))
      new Connection[L,R] {
        def local: L  = localImpl
        def remote: R = r
        def close() = {
          r.close()
          end.trySuccess(None)
        }
      }
    } { implicit b =>
      (local, remote, end) =>
      import GraphDSL.Implicits._

      val partition =
        b.add(TypePartition[Message,RequestMessage,Response])

      val merge     =
        b.add(Merge[Message](3,eagerComplete = true))

      partition.out1 ~> local  ~> merge
      partition.out2 ~> remote ~> merge
                           end ~> merge

      FlowShape(partition.in, merge.out)
    }

    stack.reversed.joinMat(handler)(Keep.right)
  }
}
