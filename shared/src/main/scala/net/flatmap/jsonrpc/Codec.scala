package net.flatmap.jsonrpc

import akka.stream.scaladsl.{BidiFlow, Flow}
import io.circe.Decoder._
import io.circe._

/**
  * Created by martin on 28.09.16.
  */
object Codec {
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
  val standard = codecInterpreter atop jsonParser

  implicit val idEncoder = new Encoder[Id] {
    def apply(a: Id): Json = a match {
      case Id.Null => Json.Null
      case Id.Long(i) => Json.fromLong(i)
      case Id.String(s) => Json.fromString(s)
    }
  }

  implicit val parameterListEncoder = new Encoder[ParameterList] {
    override def apply(a: ParameterList): Json = a match {
      case NamedParameters(params) => Json.obj(params.toSeq :_*)
      case PositionedParameters(params) => Json.arr(params :_*)
    }
  }
  implicit val requestEncoder =
    Encoder.forProduct4("id","method","params","jsonrpc")((r: Request) => (r.id,r.method,r.params,r.jsonrpc))

  implicit val notificationEncoder =
    Encoder.forProduct3("method","params","jsonrpc")((r: Notification) => (r.method,r.params,r.jsonrpc))

  implicit val requestMessageEncoder = new Encoder[RequestMessage] {
    override def apply(a: RequestMessage): Json = a match {
      case r: Request => requestEncoder(r)
      case n: Notification => notificationEncoder(n)
      case r: ResolveableRequest => sys.error("Resolvable Requests are not serializable")
    }
  }

  implicit val successEncoder =
    Encoder.forProduct3("id","result","jsonrpc")((r: Response.Success) => (r.id,r.result,r.jsonrpc))

  implicit val responseErrorEncoder =
    Encoder.forProduct3("code","message","data")((r: ResponseError) => (r.code,r.message,r.data))

  implicit val failureEncoder =
    Encoder.forProduct3("id","error","jsonrpc")((r: Response.Failure) => (r.id,r.error,r.jsonrpc))

  implicit val responseEncoder = new Encoder[Response] {
    override def apply(a: Response): Json = a match {
      case s: Response.Success => successEncoder(s)
      case f: Response.Failure => failureEncoder(f)
    }
  }

  implicit val encode = new Encoder[Message] {
    override def apply(a: Message): Json = a match {
      case a: Response => responseEncoder(a)
      case x: RequestMessage => requestMessageEncoder(x)
    }
  }

  implicit val idDecoder: Decoder[Id] = new Decoder[Id] {
    override def apply(c: HCursor): Result[Id] =
      c.as[Long].map(Id.Long) orElse c.as[String].map(Id.String)
  }

  implicit val parameterListDecoder = new Decoder[ParameterList] {
    override def apply(c: HCursor): Result[ParameterList] =
      c.as[IndexedSeq[Json]].map(PositionedParameters) orElse
      c.as[Map[String,Json]].map(NamedParameters)
  }

  implicit val requestDecoder =
    Decoder.forProduct3("id","method","params")(Request.apply)

  implicit val notificationDecoder =
    Decoder.forProduct2("method","params")(Notification.apply)

  implicit val requestMessageDecoder: Decoder[RequestMessage] = new Decoder[RequestMessage] {
    override def apply(c: HCursor): Result[RequestMessage] = {
      val r = for (fields <- c.fieldSet if fields.contains("id")) yield
        c.as[Request].map(x => x)
      r.getOrElse(c.as[Notification].map(x => x))
    }
  }

  implicit val successDecoder =
    Decoder.forProduct2("id","result")(Response.Success.apply)

  implicit val responseErrorDecoder =
    Decoder.forProduct3("code","message","data")(ResponseError.apply)

  implicit val failureDecoder =
    Decoder.forProduct2("id","error")(Response.Failure.apply)

  implicit val responseDecoder = new Decoder[Response] {
    override def apply(c: HCursor): Result[Response] = {
      val r = for (fields <- c.fieldSet if !fields.contains("error")) yield
        c.as[Response.Success].map(x => x)
      r.getOrElse(c.as[Response.Failure].map(x => x))
    }
  }


  implicit val decode: Decoder[Message] = new Decoder[Message] {
    override def apply(c: HCursor): Result[Message] = {
      val r = for (fields <- c.fieldSet if fields.contains("method")) yield
        c.as[RequestMessage].map(x => x)
      r.getOrElse(c.as[Response].map(x => x))
    }
  }
}
