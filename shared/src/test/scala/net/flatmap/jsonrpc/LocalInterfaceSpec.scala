package net.flatmap.jsonrpc

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import io.circe.Json
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time._

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

object ExampleImpl {
  def implementation = {
    val local = Local(SimpleInterface)
    import local._
    implement(
      on(SimpleInterface.exampleRequest) { p =>
        p.toString
      },
      on(SimpleInterface.exampleNotification) { p =>

      }
    )
  }
}

class LocalInterfaceSpec extends AsyncFlatSpec with Matchers with ScalaFutures {
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

  "a local interface" should "process request messages" in {
    val local = ExampleImpl.implementation
    val source = Source.single[RequestMessage](
      Request(Id.Long(0),SimpleInterface.exampleRequest.name,Json.fromInt(42))
    )
    val sink = Sink.seq[ResponseMessage]
    val (l,f) =
      source.viaMat(local)(Keep.right).toMat(sink)(Keep.both).run()
    f.map { x =>
      x should have length 1
      x shouldBe Seq(
        Response.Success(Id.Long(0),Json.fromString("42"))
      )
    }
  }
/*
  it should "respect methods from mixed-in traits" in {
    val local =
      Local[ExampleInterfaces.Simple with ExampleInterfaces.Other]
    val source = Source.single(Request(Id.Long(0),"other/hallo",NoParameters))
    val sink = Sink.seq[Response]
    val (l,f) =
      source.viaMat(local)(Keep.right).toMat(sink)(Keep.both).run()
    class Blub extends ExampleImplementations.Simple with Other {
      def hallo(): Future[String] = Future.successful("yay!")
    }
    val interface = new Blub
    l.success(Some(interface))
    whenReady(f) { x =>
      x should have length 1
      x shouldBe Seq(
        Response.Success(Id.Long(0),Json.fromString("yay!"))
      )
    }
  }

  it should "respect spread param annotations" in {
    val local =
      Local[ExampleInterfaces.Simple]
    val param = ExampleParam(1,"2",true)
    val paramEnc = ExampleParam.encoder(param)
    val source = Source.single(Request(Id.Long(0),"spreaded",NamedParameters(paramEnc.asObject.get.toMap)))
    val sink = Sink.seq[Response]
    val (l,f) =
      source.viaMat(local)(Keep.right).toMat(sink)(Keep.both).run()
    val interface = new ExampleImplementations.Simple
    l.success(Some(interface))
    whenReady(f) { x =>
      x should have length 1
      x shouldBe Seq(
        Response.Success(Id.Long(0),Json.fromString(param.a + param.b + param.c))
      )
    }
  }

  it should "process notification messages for " +
    "methods with return type Future[T]" in {
    val local =
      Local[ExampleInterfaces.Simple]
    val source = Source.single(Notification("g",NamedParameters(Map("x" ->
      Json.fromString("42")))))
    val sink = Sink.seq[Response]
    val (l,f) =
      source.viaMat(local)(Keep.right).toMat(sink)(Keep.both).run()
    val interface = new ExampleImplementations.Simple
    l.success(Some(interface))
    whenReady(f) { x =>
      x shouldBe empty
      interface.lastCallTog shouldBe "42"
    }
  }

  it should "handle positioned parameters" in {
    val local =
      Local[ExampleInterfaces.Simple]
    val source = Source.maybe[RequestMessage]
    val sink = Sink.seq[Response]
    val ((p,l), f) =
      source.viaMat(local)(Keep.both).toMat(sink)(Keep.both).run()
    l.success(Some(new ExampleImplementations.Simple))
    p.success(Some(Request(Id.Long(0),"f",PositionedParameters(Array(Json.fromInt(42))))))
    whenReady(f) { x =>
      x should have length 1
      x shouldBe Seq(
        Response.Success(Id.Long(0),Json.fromString("42"))
      )
    }
  }

  it should "handle nested namespaces" in {
    val local =
      Local[ExampleInterfaces.Simple]
    val source = Source.maybe[RequestMessage]
    val sink = Sink.seq[Response]
    val ((p,l), f) =
      source.viaMat(local)(Keep.both).toMat(sink)(Keep.both).run()
    l.success(Some(new ExampleImplementations.Simple))
    p.success(Some(Request(Id.Long(0),"nested/foo",NoParameters)))
    whenReady(f) { x =>
      x should have length 1
      x shouldBe Seq(
        Response.Success(Id.Long(0),Json.fromInt(42))
      )
    }
  }

  it should "not expose methods not part of rpc protocol" in {
    val local =
      Local[ExampleInterfaces.Simple]
    val source = Source.single[RequestMessage](
      Request(Id.Long(0),"additional",NamedParameters(Map("x" ->
        Json.fromString("param"))))
    )
    val sink = Sink.seq[Response]
    val (l,f) =
      source.viaMat(local)(Keep.right).toMat(sink)(Keep.both).run()
    l.success(Some(new ExampleImplementations.Simple))
    whenReady(f) { x =>
      x should have length 1
      x.head shouldBe a[Response.Failure]
      x.head.asInstanceOf[Response.Failure].error.code shouldBe ErrorCodes.MethodNotFound
    }
  }

  it should "handle omitted optional parameters" in {
    val local =
      Local[ExampleInterfaces.Simple]
    val source = Source.maybe[RequestMessage]
    val sink = Sink.seq[Response]
    val ((p,l), f) =
      source.viaMat(local)(Keep.both).toMat(sink)(Keep.both).run()
    l.success(Some(new ExampleImplementations.Simple))
    p.success(Some(Request(Id.Long(0),"optional",NamedParameters(Map("f" ->
      Json.fromString("test"))))))
    whenReady(f) { x =>
      x should have length 1
      x shouldBe Seq(
        Response.Success(Id.Long(0),Json.fromString("test"))
      )
    }
  }

  it should "return failure responses for missing implementations" in {
    val local =
      Local[ExampleInterfaces.Simple]
    val source = Source.single[RequestMessage](
      Request(Id.Long(0),"f",NamedParameters(Map("x" ->
        Json.fromInt(4))))
    )
    val sink = Sink.seq[Response]
    val (l,f) =
      source.viaMat(local)(Keep.right).toMat(sink)(Keep.both).run()
    l.success(Some(new ExampleImplementations.Unimplemented))
    whenReady(f) { x =>
      x should have length 1
      x.head shouldBe a[Response.Failure]
      x.head.asInstanceOf[Response.Failure].error.code shouldBe ErrorCodes.MethodNotFound
    }
  }

  it should "return failure responses for missing parameters" in {
    val local =
      Local[ExampleInterfaces.Simple]
    val source = Source.single[RequestMessage](
      Request(Id.Long(0),"f",NamedParameters(Map("y" ->
        Json.fromInt(4))))
    )
    val sink = Sink.seq[Response]
    val (l,f) =
      source.viaMat(local)(Keep.right).toMat(sink)(Keep.both).run()
    l.success(Some(new ExampleImplementations.Unimplemented))
    whenReady(f) { x =>
      x should have length 1
      x.head shouldBe a[Response.Failure]
      x.head.asInstanceOf[Response.Failure].error.code shouldBe ErrorCodes.InvalidParams
    }
  }
*/
}
