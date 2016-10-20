package net.flatmap.jsonrpc

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import org.scalatest._
import org.scalatest.concurrent.{ScalaFutures}
import org.scalatest.time._

import scala.concurrent.Future

trait TestInterface {
  def f(x: Int): Future[String]
  def g(x: String)
}

class Spec extends FlatSpec with Matchers with ScalaFutures {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher
  override def patienceConfig: PatienceConfig = PatienceConfig(timeout(Span(3 Seconds)))

  "a derived remote interface" should "produce request messages for methods " +
    "with return type Future[T]" in {
    val remote = Remote[TestInterface](Id.standard)
    val source = Source.empty[Response]
    val sink = Sink.seq[RequestMessage]
    val (interface, f) =
      source.viaMat(remote.limit(1))(Keep.right).toMat(sink)(Keep.both).run()
    interface.f(42)
    whenReady(f) { x =>
      x should have length 1
      x.head shouldBe a [RequestMessage]
    }
  }
}
