package net.flatmap.jsonrpc

import akka.{Done, NotUsed}
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import io.circe._
import net.flatmap.jsonrpc.util._

trait Connection[L,R] {
  def local: L
  def remote: R

  def close()
}

object Connection { self =>
  def create[I,O,L,R](in: Source[ByteString,I],
                      out: Sink[ByteString,O],
                      local: Flow[RequestMessage,Response,L],
                      remote: Flow[Response,RequestMessage,R],
                      framing: BidiFlow[String,ByteString,ByteString,String,NotUsed] = Framing.byteString,
                      codec: BidiFlow[Message,String,String,Message,NotUsed] = Codec.standard
  ): RunnableGraph[Connection[L,R]] = {
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

    val handler = GraphDSL.create(local, remote) { (local,remote) =>
      new Connection[L,R] {
        def local: L  = local
        def remote: R = remote
        def close()   = ???
      }
    } { implicit b =>
      (local, remote) =>
      import GraphDSL.Implicits._

      val partition = b.add(TypePartition[Message,RequestMessage,Response])
      val merge     = b.add(Merge[Message](2))
      partition.out1 ~> local  ~> merge
      partition.out2 ~> remote ~> merge
      FlowShape(partition.in, merge.out)
    }

    val flow = stack.reversed.joinMat(handler)(Keep.right)

    in.viaMat(flow)(Keep.right).to(out)
  }
}
