package net.flatmap.jsonrpc

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl._
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import io.circe.Json
import net.flatmap.jsonrpc
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time._

import scala.concurrent.duration._
import scala.concurrent.Promise

case class ExampleParam(a: Int, b: String, c: Boolean)

object ExampleParam {
  import io.circe.generic.semiauto._
  implicit val encoder = deriveEncoder[ExampleParam]
  implicit val decoder = deriveDecoder[ExampleParam]
}

case class ExampleError(message: String)

object ExampleError {
  import io.circe.generic.semiauto._
  implicit val encoder = deriveEncoder[ExampleError]
  implicit val decoder = deriveDecoder[ExampleError]
}

object SimpleInterface extends Interface {
  val exampleRequest =
    request[Int,String,ExampleError]("example/request")
  val exampleNotification =
    notification[String]("example/notification")
}


class RemoteInterfaceSpec extends AsyncFlatSpec with Matchers with ScalaFutures {
  implicit val system = ActorSystem("test-system",ConfigFactory.parseString("akka.loglevel = \"INFO\""))
  implicit val materializer = ActorMaterializer()
  implicit override def executionContext =
    scala.concurrent.ExecutionContext.Implicits.global
  implicit val requestTimeout = Timeout(1,TimeUnit.SECONDS)
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(Span(500, Milliseconds))

  override def withFixture(test: NoArgAsyncTest): FutureOutcome = {
    val p = Promise[Outcome]
    system.scheduler.scheduleOnce(0.seconds) {
      p.completeWith(test.apply().toFuture)
    }
    new FutureOutcome(p.future)
  }

  "a remote interface" should "produce request messages" in {
    val remote = Remote(SimpleInterface)
    val source = Source.maybe[ResponseMessage]
    val sink = Sink.seq[RequestMessage]
    val ((p,r), f) =
      source.viaMat(remote)(Keep.both).toMat(sink)(Keep.both).run()
    SimpleInterface.exampleRequest(5)(r,requestTimeout)
    SimpleInterface.exampleRequest(17)(r,requestTimeout)
    r.close()
    f.map { x =>
      x should have length 2
      x shouldBe Seq(
        Request(Id.Long(0),"example/request",Json.fromInt(5)),
        Request(Id.Long(1),"example/request",Json.fromInt(17))
      )
    }
  }

  it should "produce notification messages" in {
    val remote = Remote(SimpleInterface)
    val source = Source.maybe[ResponseMessage]
    val sink = Sink.seq[RequestMessage]
    val ((p,r), f) =
      source.viaMat(remote)(Keep.both).toMat(sink)(Keep.both).run()
    r.interface.exampleNotification("foo")(r)
    r.interface.exampleNotification("bar")(r)
    r.close()
    f.map { x =>
      x should have length 2
      x shouldBe Seq(
        Notification("example/notification",Json.fromString("foo")),
        Notification("example/notification",Json.fromString("bar"))
      )
    }
  }

  it should "complete the futures when responding to a request" in {
    val remote = Remote(SimpleInterface,Id.standard)
    val source = Source.queue[ResponseMessage](3,OverflowStrategy.fail)
    val sink = Sink.seq[RequestMessage]

    implicit val ((p,r), f) =
      source.viaMat(remote)(Keep.both).toMat(sink)(Keep.both).run()

    val x = r.interface.exampleRequest(42) // Id.Long(0)
    val y = r.interface.exampleRequest(17) // Id.Long(1)
    val z = r.interface.exampleRequest(19) // Id.Long(2)

    // respond to call "y"
    p.offer(Response.Success(Id.Long(1),Json.fromString("y")))
    p.offer(Response.Success(Id.Long(2),Json.fromString("z")))
    p.offer(Response.Success(Id.Long(0),Json.fromString("x")))
    p.complete()

    r.close()

    for {
      x <- x
      y <- y
      z <- z
    } yield {
      x shouldEqual("x")
      y shouldEqual("y")
      z shouldEqual("z")
    }
  }

  it should "fail the futures when responding to a request with an error" in {
    val remote = Remote(SimpleInterface,Id.standard)
    val source = Source.maybe[ResponseMessage]
    val sink = Sink.seq[RequestMessage]

    implicit val ((p,r), f) =
      source.viaMat(remote)(Keep.both).toMat(sink)(Keep.both).run()

    val x = r.interface.exampleRequest(42) // Id.Long(0)
    val y = r.interface.exampleRequest(17) // Id.Long(1)
    val z = r.interface.exampleRequest(19) // Id.Long(2)

    // respond to call "y"
    p.success(Some(Response.Failure(Id.Long(1), ResponseError(
      17,"fail!",None
    ))))

    r.close()

    y.failed.map { r =>
      r shouldBe a[ResponseError]
      r.getMessage shouldEqual "fail!"
    }
  }

  it should "fail the futures when a request is not answered" in {
    val remote = Remote(SimpleInterface,Id.standard)
    val source = Source.maybe[ResponseMessage]
    val sink = Sink.seq[RequestMessage]

    implicit val ((p,r), f) =
      source.viaMat(remote)(Keep.both).toMat(sink)(Keep.both).run()

    val x = r.interface.exampleRequest(42) // Id.Long(0)
    val y = r.interface.exampleRequest(17) // Id.Long(1)
    val z = r.interface.exampleRequest(19) // Id.Long(2)

    // respond to call "y"
    p.success(Some(Response.Failure(Id.Long(1), ResponseError(
      17,"fail!",None
    ))))

    r.close()

    z.failed.map { r =>
      r shouldBe a[ResponseError]
      r.getMessage shouldEqual "closed source"
    }
  }

  it should "send cancellation notifications for cancelled futures" in {
    val remote = Remote(SimpleInterface,Id.standard)
    val source = Source.maybe[ResponseMessage]
    val sink = Sink.seq[RequestMessage]

    implicit val ((p,r), f) =
      source.viaMat(remote)(Keep.both).toMat(sink)(Keep.both).run()

    val x = r.interface.exampleRequest(42) // Id.Long(0)
    val y = r.interface.exampleRequest(17) // Id.Long(1)
    val z = r.interface.exampleRequest(19) // Id.Long(2)

    // cancel "y"
    y.cancel()

    y.onComplete(_ => r.close())

    f.map { messages =>
      messages should contain (Notification("$/cancel",Json.obj("id" -> Json.fromLong(1))))
    }
  }
}
