package net.flatmap.jsonrpc

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import io.circe.Json
import net.flatmap.jsonrpc.ExampleInterfaces.Nested
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time._

import scala.concurrent.{ExecutionContext, Future}

object ExampleImplementations {
  class Simple extends ExampleInterfaces.Simple {
    var lastCallTog = ""
    def f(x: Int): Future[String] = Future.successful(x.toString)
    def g(x: String) = lastCallTog = x

    def nested = new Nested {
      override def foo(): Future[Int] = Future.successful(42)
    }
    override def h(x: String): Unit = ()

    def optional(f: String, y: Option[Int]): Future[String] =
      Future.successful(List.fill(y.getOrElse(1))(f).mkString)
  }
}


class LocalInterfaceSpec extends FlatSpec with Matchers with ScalaFutures {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(Span(500, Milliseconds))

  "a derived local interface" should "process request messages for " +
    "methods with return type Future[T]" in {
    val local =
      Local[ExampleInterfaces.Simple](new ExampleImplementations.Simple)
    val source = Source.maybe[RequestMessage]
    val sink = Sink.seq[Response]
    val (p, f) =
      source.viaMat(local)(Keep.left).toMat(sink)(Keep.both).run()
    p.success(Some(Request(Id.Long(0),"f",NamedParameters(Map("x" ->
      Json.fromInt(42))))))
    whenReady(f) { x =>
      x should have length 1
      x shouldBe Seq(
        Response.Success(Id.Long(0),Json.fromString("42"))
      )
    }
  }

  it should "process notification messages for " +
    "methods with return type Future[T]" in {
    val interface = new ExampleImplementations.Simple
    val local =
      Local[ExampleInterfaces.Simple](interface)
    val source = Source.maybe[RequestMessage]
    val sink = Sink.seq[Response]
    val (p, f) =
      source.viaMat(local)(Keep.left).toMat(sink)(Keep.both).run()
    p.success(Some(Notification("g",NamedParameters(Map("x" ->
      Json.fromString("42"))))))
    whenReady(f) { x =>
      x shouldBe empty
      interface.lastCallTog shouldBe "42"
    }
  }

  it should "handle positioned parameters" in {
    val local =
      Local[ExampleInterfaces.Simple](new ExampleImplementations.Simple)
    val source = Source.maybe[RequestMessage]
    val sink = Sink.seq[Response]
    val (p, f) =
      source.viaMat(local)(Keep.left).toMat(sink)(Keep.both).run()
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
      Local[ExampleInterfaces.Simple](new ExampleImplementations.Simple)
    val source = Source.maybe[RequestMessage]
    val sink = Sink.seq[Response]
    val (p, f) =
      source.viaMat(local)(Keep.left).toMat(sink)(Keep.both).run()
    p.success(Some(Request(Id.Long(0),"nested/foo",NoParameters)))
    whenReady(f) { x =>
      x should have length 1
      x shouldBe Seq(
        Response.Success(Id.Long(0),Json.fromInt(42))
      )
    }
  }

  it should "handle omitted optional parameters" in {
    val local =
      Local[ExampleInterfaces.Simple](new ExampleImplementations.Simple)
    val source = Source.maybe[RequestMessage]
    val sink = Sink.seq[Response]
    val (p, f) =
      source.viaMat(local)(Keep.left).toMat(sink)(Keep.both).run()
    p.success(Some(Request(Id.Long(0),"optional",NamedParameters(Map("f" ->
      Json.fromString("test"))))))
    whenReady(f) { x =>
      x should have length 1
      x shouldBe Seq(
        Response.Success(Id.Long(0),Json.fromString("test"))
      )
    }
  }

}
