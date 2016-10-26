package net.flatmap.jsonrpc

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import io.circe.Json
import net.flatmap.jsonrpc.ExampleInterfaces.{Nested, Other}
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object ExampleImplementations {
  class Simple extends ExampleInterfaces.Simple {
    var lastCallTog = ""
    def f(x: Int): Future[String] = Future.successful(x.toString)
    def g(x: String) = lastCallTog = x

    def nested = new Nested {
      override def foo(): Future[Int] = Future.successful(42)
    }
    override def h(x: String): Unit = ???

    def optional(f: String, y: Option[Int]): Future[String] =
      Future.successful(List.fill(y.getOrElse(1))(f).mkString)

    def additional(x: String): Future[String] = Future.successful(x.reverse)
  }

  class Unimplemented extends ExampleInterfaces.Simple {
    def f(x: Int): Future[String] = ???

    def g(x: String): Unit = ???

    @JsonRPCMethod("blub")
    def h(x: String): Unit = ???

    @JsonRPCNamespace("nested/")
    def nested: Nested = ???

    def optional(f: String, y: Option[Int]): Future[String] = ???
  }
}


class LocalInterfaceSpec extends FlatSpec with Matchers with ScalaFutures {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(Span(500, Milliseconds))

  def run(block: => Unit) = system.scheduler.scheduleOnce(0 millis)(block)

  "a derived local interface" should "process request messages for " +
    "methods with return type Future[T]" in {
    val local =
      Local[ExampleInterfaces.Simple]
    val source = Source.single[RequestMessage](
      Request(Id.Long(0),"f",NamedParameters(Map("x" ->
        Json.fromInt(42))))
    )
    val sink = Sink.seq[Response]
    val (l,f) =
      source.viaMat(local)(Keep.right).toMat(sink)(Keep.both).run()
    l.success(Some(new ExampleImplementations.Simple))
    whenReady(f) { x =>
      x should have length 1
      x shouldBe Seq(
        Response.Success(Id.Long(0),Json.fromString("42"))
      )
    }
  }

  it should "respect methods from mixed-in traits" in {
    val local =
      Local[ExampleInterfaces.Simple with ExampleInterfaces.Other]
    val source = Source.single(Request(Id.Long(0),"other/hallo",NoParameters))
    val sink = Sink.seq[Response]
    val (l,f) =
      source.viaMat(local)(Keep.right).toMat(sink)(Keep.both).run()
    class Blub extends ExampleImplementations.Simple with Other {
      @JsonRPCMethod("other/hallo")
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

}
