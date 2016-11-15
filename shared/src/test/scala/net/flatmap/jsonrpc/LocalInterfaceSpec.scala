package net.flatmap.jsonrpc

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.util.Timeout
import io.circe.Json
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time._

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

class ExampleImpl extends Local(SimpleInterface) {
  private val promise = Promise[String]
  val notificationValue = promise.future

  val implementation = Implementation(
    interface.exampleRequest := { i =>
      i.x.toString
    },
    interface.exampleNotification := { s =>
      promise.trySuccess(s.x)
    }
  )
}

class ExampleImplMissing extends Local(SimpleInterface) {
  val implementation = Implementation()
}

class LocalInterfaceSpec extends AsyncFlatSpec with Matchers with ScalaFutures {
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

  "a local interface" should "process request messages" in {
    val local = new ExampleImpl
    val source = Source.single[RequestMessage](
      RequestMessage.Request(Id.Long(0),SimpleInterface.exampleRequest.name,
        Json.obj("x" -> Json.fromInt(42)))
    )
    val sink = Sink.seq[ResponseMessage]
    val (l,f) =
      source.viaMat(local.flow)(Keep.right).toMat(sink)(Keep.both).run()
    f.map { x =>
      x should have length 1
      x shouldBe Seq(
        ResponseMessage.Success(Id.Long(0),Json.fromString("42"))
      )
    }
  }

  it should "process notification messages" in {
    val local = new ExampleImpl
    val source = Source.single[RequestMessage](
      RequestMessage.Notification(SimpleInterface.exampleNotification.name,
        Json.obj("x" -> Json.fromString("boo!")))
    )
    val sink = Sink.seq[ResponseMessage]
    source.via(local.flow).to(sink).run()
    local.notificationValue.map { x =>
      x shouldBe "boo!"
    }
  }


  it should "return failure responses for missing implementations" in {
    val local = new ExampleImplMissing
    val source = Source.single[RequestMessage](
      RequestMessage.Request(Id.Long(0),SimpleInterface.exampleRequest.name,
        Json.obj("x" -> Json.fromInt(42)))
    )
    val sink = Sink.seq[ResponseMessage]
    source.via(local.flow).runWith(sink).map { x =>
      x should have length 1
      x.head shouldBe a [ResponseMessage.Failure]
      x.head.asInstanceOf[ResponseMessage.Failure].error.code shouldBe ErrorCodes.MethodNotFound
    }
  }

  it should "return failure responses for missing parameters" in {
    val local = new ExampleImpl
    val source = Source.single[RequestMessage](
      RequestMessage.Request(Id.Long(0),SimpleInterface.exampleRequest.name,
        Json.obj()
    ))
    val sink = Sink.seq[ResponseMessage]
    source.via(local.flow).runWith(sink).map { x =>
      x should have length 1
      x.head shouldBe a [ResponseMessage.Failure]
      x.head.asInstanceOf[ResponseMessage.Failure].error.code shouldBe ErrorCodes.InvalidParams
    }
  }
}
