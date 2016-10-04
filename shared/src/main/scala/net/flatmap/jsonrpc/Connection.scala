package net.flatmap.jsonrpc

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import io.circe._
import net.flatmap.jsonrpc.util._

import scala.collection.immutable.Seq

object Connection { self =>
  private var requestId: Int = 0

  def uniqueId = synchronized {
    requestId += 1
    Id.Long(requestId)
  }

  val jsonPrinter = Printer.noSpaces.copy(dropNullKeys = true)

  val parseFailureHandler = Flow[Message].recover {
    case e: ParsingFailure =>
      val err = ResponseError(ErrorCodes.ParseError,e.message,None)
      Response.Failure(Id.Null,err)
  }

  val decoder = Flow[Json].map {
    (x: Json) => Codec.decode.decodeJson(x).toTry.get
  } via parseFailureHandler

  val encoder = Flow[Message]
    .map(Codec.encode.apply)

  val jsonParser = BidiFlow.fromFunctions(
    outbound = jsonPrinter.pretty,
    inbound  = (x: String) => parser.parse(x).toTry.get
                                         )

  val codecInterpreter = BidiFlow.fromFlows(encoder,decoder)

  /* codec stack
   *         +------------------------------------+
   *         | stack                              |
   *         |                                    |
   *         |  +----------+         +---------+  |
   *    ~>   O~~o          |   ~>    |         o~~O   ~>
   * Message |  | validate |  Json   |  parse  |  | String
   *    <~   O~~o          |   <~    |         o~~O   <~
   *         |  +----------+         +---------+  |
   *         +------------------------------------+
   */
  val codec = codecInterpreter atop jsonParser

  def create[T](
    local: Flow[RequestMessage,Response,NotUsed],
    remote: Flow[Response,RequestMessage,T],
    framing: BidiFlow[String,ByteString,ByteString,String,NotUsed] = Framing.framing,
    codec: BidiFlow[Message,String,String,Message,NotUsed] = codec
  ): Flow[ByteString,ByteString,T] = {
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

    val handler = GraphDSL.create(local,remote) (Keep.right) { implicit b =>
      (local,remote) =>
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
