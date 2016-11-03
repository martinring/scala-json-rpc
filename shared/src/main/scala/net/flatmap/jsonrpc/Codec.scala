package net.flatmap.jsonrpc

import akka.stream.scaladsl.{BidiFlow, Flow}
import io.circe.Decoder._
import io.circe._

/**
  * Created by martin on 28.09.16.
  */
object Codec {
  private val jsonPrinter = Printer.noSpaces

  val decoder =
    Flow[String].map { input =>
      parser.parse(input).fold({ failure =>
        val err = ResponseError(ErrorCodes.ParseError, failure.message, None)
        Response.Failure(Id.Null,err)
      },{ json =>
        Codec.decodeMessage.decodeJson(json).fold[Message]({ e =>
          val err = ResponseError(ErrorCodes.ParseError, e.message, None)
          val id = for { // try to find a valid id field on the message
            obj <- json.asObject
            id  <- obj("id")
            id  <- decodeId.decodeJson(id).right.toOption
          } yield id
          Response.Failure(id.getOrElse(Id.Null),err)
        }, identity)
      })
    }

  val encoder = Flow[Message]
    .map(encodeMessage.apply)
    .map(Printer.noSpaces.pretty)

  val standard = BidiFlow.fromFlows(encoder,decoder)

  implicit val encodeId = Encoder.instance[Id] {
    case Id.Null => Json.Null
    case Id.Long(i) => Json.fromLong(i)
    case Id.String(s) => Json.fromString(s)
  }
  implicit val decodeId: Decoder[Id] =
    Decoder.decodeLong.map[Id](Id.Long) or
    Decoder.decodeString.map[Id](Id.String) or
    Decoder.decodeNone.map[Id](_ => Id.Null)

  implicit val encodeRequest =
    Encoder.forProduct4("id","method","params","jsonrpc")((r: Request) => (r.id,r.method,r.params,r.jsonrpc))
  implicit val decodeRequest =
    Decoder.forProduct3("id","method","params")(Request.apply)

  implicit val encodeNotification =
    Encoder.forProduct3("method","params","jsonrpc")((r: Notification) => (r.method,r.params,r.jsonrpc))
  implicit val decodeNotification =
    Decoder.forProduct2("method","params")(Notification.apply)

  implicit val encodeRequestMessage = Encoder.instance[RequestMessage] {
    case r: Request => encodeRequest(r)
    case n: Notification => encodeNotification(n)
  }
  implicit val decodeRequestMessage: Decoder[RequestMessage] =
    decodeRequest or
    decodeNotification.map[RequestMessage](x => x)

  implicit val encodeSuccess =
    Encoder.forProduct3("id","result","jsonrpc")((r: Response.Success) => (r.id,r.result,r.jsonrpc))
  implicit val decodeSuccess =
    Decoder.forProduct2("id","result")(Response.Success.apply)

  implicit val encodeResponseError =
    Encoder.forProduct3("code","message","data")((r: ResponseError) => (r.code,r.message,r.data))
  implicit val decodeResponseError =
    Decoder.forProduct3("code","message","data")(ResponseError.apply)

  implicit val encodeFailure =
    Encoder.forProduct3("id","error","jsonrpc")((r: Response.Failure) => (r.id,r.error,r.jsonrpc))
  implicit val decodeFailure =
    Decoder.forProduct2("id","error")(Response.Failure.apply)

  implicit val encodeResponseMessage = Encoder.instance[ResponseMessage] {
    case s: Response.Success => encodeSuccess(s)
    case f: Response.Failure => encodeFailure(f)
  }
  implicit val decodeResponseMessage =
    decodeSuccess or
    decodeFailure.map[ResponseMessage](x => x)

  def validateJsonRPC(hCursor: HCursor): Boolean =
    hCursor.get[String]("jsonrpc").right.exists(_ == "2.0")

  implicit val encodeMessage = Encoder.instance[Message] {
    case a: ResponseMessage => encodeResponseMessage(a)
    case x: RequestMessage => encodeRequestMessage(x)
  }
  implicit val decodeMessage: Decoder[Message] =
    (decodeRequestMessage or decodeResponseMessage.map[Message](x => x))
      .validate(validateJsonRPC,"message is not declared as jsonrpc 2.0")
}
