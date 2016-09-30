package net.flatmap.jsonrpc

import akka.NotUsed
import akka.actor.ActorSystem

import scala.concurrent.Await
import akka.stream._
import akka.stream.scaladsl._
import akka.util.{ByteString, PrettyDuration}
import io.circe
import io.circe.{Json, ParsingFailure, Printer}

import scala.concurrent.duration._

object Connection { self =>
  private var requestId: Int = 0

  def uniqueId = synchronized {
    requestId += 1
    Id.Int(requestId)
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

  val codecParser = BidiFlow.fromFunctions(
    outbound = jsonPrinter.pretty,
    inbound  = (x: String) => circe.parser.parse(x).toTry.get
  )

  val codecInterpreter = BidiFlow.fromFlows(encoder,decoder)

  val codec = codecInterpreter atop codecParser

  val messagePartitioner = GraphDSL.create() { implicit b =>
    import GraphDSL.Implicits._
    val partition = b.add(Partition[Message](2,{
      case r: RequestMessage => 0
      case r: Response => 1
    }))

    val castRequest = b.add(Flow[Message].map(_.asInstanceOf[RequestMessage]))
    val castRespose = b.add(Flow[Message].map(_.asInstanceOf[Response]))

    partition.out(0) ~> castRequest.in
    partition.out(1) ~> castRespose.in

    new FanOutShape2(partition.in,castRequest.out,castRespose.out)
  }

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
      val partition = b.add(messagePartitioner)
      val merge     = b.add(Merge[Message](2))
      partition.out0 ~> local  ~> merge
      partition.out1 ~> remote ~> merge
      FlowShape(partition.in, merge.out)
    }

    stack.reversed.joinMat(handler)(Keep.right)
  }

  def open[T](in: Source[ByteString,Any], out: Sink[ByteString,Any], connection: Flow[ByteString,ByteString,T])(implicit materializer: Materializer): T =
    in.viaMat(connection)(Keep.right).to(out).run()
}
