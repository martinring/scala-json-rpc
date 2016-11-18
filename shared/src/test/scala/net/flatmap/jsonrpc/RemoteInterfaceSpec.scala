package net.flatmap.jsonrpc

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl._
import akka.util.Timeout
import io.circe.Json
import net.flatmap.jsonrpc.SimpleInterface.ExampleNotificationParams
import shapeless._
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time._

import scala.concurrent.duration._
import scala.concurrent.Promise

object SimpleInterface {
  import io.circe.generic.auto._

  case class ExampleRequestParams(
    x: Int
  )

  object exampleRequest extends
    RequestType[ExampleRequestParams,String,Unit]("example/request")

  case class ExampleNotificationParams(
    x: String
  )

  object exampleNotification extends
    NotificationType[ExampleNotificationParams]("example/notification")

  val interface = Interface(exampleRequest,exampleNotification)
}

object OtherInterface {
  import io.circe.generic.auto._
  import SimpleInterface._

  case class AnotherNotificationParams(x: Int, y: String)

  object AnotherNotification extends NotificationType[AnotherNotificationParams]("example/anotherNotification")

  val interface = Interface(AnotherNotification)
}

object CombinedInterface {
  val interface = SimpleInterface.interface and OtherInterface.interface
}

class RemoteInterfaceSpec extends AsyncFlatSpec with Matchers with ScalaFutures {
  implicit val system = ActorSystem("test-system",testConfig)
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
    val remote = Remote(SimpleInterface.interface)
    val source = Source.maybe[ResponseMessage]
    val sink = Sink.seq[RequestMessage]
    import SimpleInterface._
    implicit val ((p,r), f) =
      source.viaMat(remote)(Keep.both).toMat(sink)(Keep.both).run()
    exampleRequest(5)
    exampleRequest(17)
    r.close()
    f.map { x =>
      x should have length 2
      x shouldBe Seq(
        RequestMessage.Request(Id.Long(0),"example/request",Json.obj("x" -> Json.fromInt(5))),
        RequestMessage.Request(Id.Long(1),"example/request",Json.obj("x" -> Json.fromInt(17)))
      )
    }
  }

  it should "produce notification messages" in {
    val remote = Remote(CombinedInterface.interface)
    val source = Source.maybe[ResponseMessage]
    val sink = Sink.seq[RequestMessage]
    implicit val ((p,r), f) =
      source.viaMat(remote)(Keep.both).toMat(sink)(Keep.both).run()
    OtherInterface.AnotherNotification(4, "foo")
    SimpleInterface.exampleNotification("bar")
    r.close()
    f.map { x =>
      x should have length 2
      x shouldBe Seq(
        RequestMessage.Notification("example/anotherNotification",Json.obj("x" -> Json.fromInt(4), "y" -> Json.fromString("foo"))),
        RequestMessage.Notification("example/notification",Json.obj("x" -> Json.fromString("bar")))
      )
    }
  }

  it should "complete the futures when responding to a request" in {
    val remote = Remote(SimpleInterface.interface)
    val source = Source.queue[ResponseMessage](3,OverflowStrategy.fail)
    val sink = Sink.seq[RequestMessage]

    import SimpleInterface._

    implicit val ((p,r), f) =
      source.viaMat(remote)(Keep.both).toMat(sink)(Keep.both).run()

    val x = exampleRequest(42) // Id.Long(0)
    val y = exampleRequest(17) // Id.Long(1)
    val z = exampleRequest(19) // Id.Long(2)

    // respond to calls
    p.offer(ResponseMessage.Success(Id.Long(1),Json.fromString("y")))
    p.offer(ResponseMessage.Success(Id.Long(2),Json.fromString("z")))
    p.offer(ResponseMessage.Success(Id.Long(0),Json.fromString("x")))
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
    val remote = Remote(SimpleInterface.interface)
    val source = Source.maybe[ResponseMessage]
    val sink = Sink.seq[RequestMessage]

    import SimpleInterface._

    implicit val ((p,r), f) =
      source.viaMat(remote)(Keep.both).toMat(sink)(Keep.both).run()

    val x = exampleRequest(42) // Id.Long(0)
    val y = exampleRequest(17) // Id.Long(1)
    val z = exampleRequest(19) // Id.Long(2)

    // respond to call "y"
    p.success(Some(ResponseMessage.Failure(Id.Long(1), ResponseError(
      17,"fail!",None
    ))))

    r.close()

    y.failed.map { r =>
      r shouldBe a[ResponseError]
      r.getMessage shouldEqual "fail!"
    }
  }

  it should "fail the futures when a request is not answered" in {
    val remote = Remote(SimpleInterface.interface)
    val source = Source.maybe[ResponseMessage]
    val sink = Sink.seq[RequestMessage]

    implicit val ((p,r), f) =
      source.viaMat(remote)(Keep.both).toMat(sink)(Keep.both).run()

    import SimpleInterface._

    val x = exampleRequest(5) // Id.Long(0)
    val y = exampleRequest(17) // Id.Long(1)
    val z = exampleRequest(19) // Id.Long(2)

    // respond to call "y"
    p.success(Some(ResponseMessage.Failure(Id.Long(1), ResponseError(
      17,"fail!",None
    ))))

    r.close()

    z.failed.map { r =>
      r shouldBe a[ResponseError]
      r.getMessage shouldEqual "closed source"
    }
  }

  it should "send cancellation notifications for cancelled futures" in {
    val remote = Remote(SimpleInterface.interface)
    val source = Source.maybe[ResponseMessage]
    val sink = Sink.seq[RequestMessage]

    implicit val ((p,r), f) =
      source.viaMat(remote)(Keep.both).toMat(sink)(Keep.both).run()

    import SimpleInterface._

    val x = exampleRequest(42) // Id.Long(0)
    val y = exampleRequest(17) // Id.Long(1)
    val z = exampleRequest(19) // Id.Long(2)

    // cancel "y"
    y.cancel()

    y.onComplete(_ => r.close())

    f.map { messages =>
      messages should contain (RequestMessage.Notification("$/cancel",Json.obj("id" -> Json.fromLong(1))))
    }
  }
}
