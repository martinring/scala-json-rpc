package net.flatmap.jsonrpc

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{BidiFlow, Flow, Keep, Sink, Source}
import akka.util.{ByteString, Timeout}
import io.circe.Json
import net.flatmap.jsonrpc.SimpleInterface.{ExampleNotificationParams, ExampleRequestParams}
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Milliseconds, Span}
import scala.concurrent.duration._

import scala.collection.immutable
import scala.concurrent.{Future, Promise}

class SimpleDependentImpl(implicit val remote: Remote[SimpleInterface.interface.Shape]) extends Local(SimpleInterface.interface) {
  private val promise   = Promise[String]
  val notificationValue = promise.future
  implicit val requestTimeout = Timeout(1,TimeUnit.SECONDS)

  val implementation = SimpleInterface.interface.implement((
    SimpleInterface.exampleRequest := { i =>
      if (i.x < 0) sys.error("some error")
      i.x.toString
    },
    SimpleInterface.exampleNotification := { i =>
      SimpleInterface.exampleNotification("foo")
    }
  ))

}

/**
  * Created by martin on 24.10.16.
  */
class ConnectionSpec extends AsyncFlatSpec with Matchers with ScalaFutures {
  implicit val system = ActorSystem("connection_test",testConfig)
  implicit val materializer = ActorMaterializer()
  implicit override def executionContext =
    scala.concurrent.ExecutionContext.Implicits.global
  implicit val requestTimeout = Timeout(1,TimeUnit.SECONDS)

  override def withFixture(test: NoArgAsyncTest): FutureOutcome = {
    val p = Promise[Outcome]
    system.scheduler.scheduleOnce(0.seconds) {
      p.completeWith(test.apply().toFuture)
    }
    new FutureOutcome(p.future)
  }

  "a connection" should "be short-circuitable" in {
    val flow = Connection(SimpleInterface.interface,SimpleInterface.interface) {
      new SimpleDependentImpl()(_)
    }

    val connection = flow.join(flow).run()

    import connection.remote

    SimpleInterface.exampleRequest(17).map { x =>
      connection.close()
      x shouldBe "17"
    }
  }

  it should "survive failures" in {
    val flow = Connection.bidi(SimpleInterface.interface,SimpleInterface.interface)(
      new SimpleDependentImpl()(_),BidiFlow.fromFlows(Flow[Message],Flow[Message]))

    val messages = immutable.Iterable(
      RequestMessage.Request(Id.Long(0), "example/request", Json.obj()),
      RequestMessage.Request(Id.Long(0), "example/request", Json.obj("x" -> Json.fromInt(4))),
      RequestMessage.Request(Id.Long(0), "example/request", Json.obj()),
      RequestMessage.Request(Id.Long(0), "example/request", Json.obj("x" -> Json.fromInt(-1))),
      RequestMessage.Request(Id.Long(0), "example/request", Json.obj("x" -> Json.fromInt(4)))
    )

    val (connection,out) =
      Source(messages).via(flow).toMat(Sink.seq[Message])(Keep.both).run()

    out.map { msgs =>
      msgs should have length 5
      msgs(0) shouldBe a[ResponseMessage.Failure]
      msgs(1) shouldBe a[ResponseMessage.Success]
      msgs(2) shouldBe a[ResponseMessage.Failure]
      msgs(3) shouldBe a[ResponseMessage.Failure]
      msgs(3).asInstanceOf[ResponseMessage.Failure].error.message shouldBe "some error"
      msgs(4) shouldBe a[ResponseMessage.Success]
    }
  }

  it should "survive parser failures" in {
    val flow = Connection(SimpleInterface.interface,SimpleInterface.interface)(new SimpleDependentImpl()(_))
    val okMessage = Codec.encodeRequest(
      RequestMessage.Request(Id.Long(0), "example/request", Json.obj("x" -> Json.fromInt(4)))
    ).noSpaces
    val messages = immutable.Iterable(
      ByteString.fromString("Content-Length: 5\r\n\r\n{\"k\"}"),
      ByteString.fromString(s"Content-Length: ${okMessage.length}\r\n\r\n" + okMessage),
      ByteString.fromString("Content-Length: 5\r\n\r\n{\"k\"}"),
      ByteString.fromString(s"Content-Length: ${okMessage.length}\r\n\r\n" + okMessage)
    )

    val (connection,out) =
      Source(messages).viaMat(flow)(Keep.right).toMat(Sink.seq[ByteString])(Keep.both).run()

    out.map { msgs =>
      msgs should have length 4
    }
  }

  /*it should "stay opened" in {
    val flow = Connection.bidi(SimpleInterface.Interface,SimpleInterface.Interface)(
      new SimpleDependentImpl()(_),BidiFlow.fromFlows(Flow[Message],Flow[Message]))

    val source = Source.queue[Message](16,OverflowStrategy.fail)
    val sink = Sink.queue[Message]

    val ((in,connection),out) =
      source.viaMat(flow)(Keep.both).toMat(sink)(Keep.both).run()

    in.offer(RequestMessage.Request(Id.Long(0), "example/request", Json.obj("x" -> Json.fromInt(-1))))
    out.pull().flatMap {
      case None => fail("unexpected end of message stream")
      case Some(msg: ResponseMessage) =>
        msg shouldBe a [ResponseMessage.Failure]
        Thread.sleep(200)
        in.offer(RequestMessage.Request(Id.Long(0), "example/request", Json.obj("x" -> Json.fromInt(1))))
        out.pull().flatMap {
          case None => fail("unexpected end of message stream")
          case Some(msg: ResponseMessage) =>
            msg shouldBe a [ResponseMessage.Success]
            Thread.sleep(200)
            in.offer(RequestMessage.Request(Id.Long(0), "example/request", Json.obj()))
            in.complete()
            out.pull().map {
              case None => fail("unexpected end of message stream")
              case Some(msg: ResponseMessage) =>
                msg shouldBe a [ResponseMessage.Failure]
            }
        }
    }
  }*/
}
