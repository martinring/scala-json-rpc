package net.flatmap.jsonrpc

import akka.{Done, NotUsed}
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import io.circe._
import net.flatmap.jsonrpc.util._

object Connection { self =>
  def create[L,R](
    remote: Flow[Response,RequestMessage,R],
    local: Flow[RequestMessage,Response,R => L],
    framing: BidiFlow[String,ByteString,ByteString,String,NotUsed] = Framing.byteStream,
    codec: BidiFlow[Message,String,String,Message,NotUsed] = Codec.standard
  ): Flow[ByteString,ByteString,(L,R)] = {
    /* construct protocol stack
     *         +------------------------------------+
     *         | stack                              |
     *         |                                    |
     *         |  +-------+            +---------+  |
     *    ~>   O~~o       |     ~>     |         o~~O    ~>
     * Message |  | codec |   String   | framing |  | ByteString
     *    <~   O~~o       |     <~     |         o~~O    <~
     *         |  +-------+            +---------+  |
     *         +------------------------------------+
     */
    val stack = codec atop framing

    val handler = GraphDSL.create(local, remote) ((l,r) => (l(r),r)) { implicit b =>
      (local, remote) =>
      import GraphDSL.Implicits._

      val partition = b.add(TypePartition[Message,RequestMessage,Response])
      val merge     = b.add(Merge[Message](2))
      partition.out1 ~> local  ~> merge
      partition.out2 ~> remote ~> merge
      FlowShape(partition.in, merge.out)
    }

    stack.reversed.joinMat(handler)(Keep.right)
  }

  def open[T](in: Source[ByteString,Any], out: Sink[ByteString,Any], connection: Flow[ByteString,ByteString,T])(implicit materializer: Materializer): T =
    in.viaMat(connection)(Keep.right).to(out).run()
}
