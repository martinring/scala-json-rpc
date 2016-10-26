package net.flatmap.jsonrpc

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep}
import akka.util.ByteString
import net.flatmap.jsonrpc.ExampleInterfaces.Nested
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Milliseconds, Span}

import scala.concurrent.Future

class SimpleDependentImpl(remote: ExampleInterfaces.Simple) extends ExampleInterfaces.Simple {
  def f(x: Int): Future[String] = Future.successful(hvalue)
  def g(x: String): Unit = remote.h("from g: " + x)
  @JsonRPCMethod("blub")
  var hvalue = ""
  def h(x: String): Unit = { hvalue = x }
  @JsonRPCNamespace("nested/")
  def nested: Nested = new Nested {
    def foo: Future[Int] = Future.successful(32)
  }
  def optional(f: String, y: Option[Int]): Future[String] = Future.successful(f)
}

/**
  * Created by martin on 24.10.16.
  */
class ConnectionSpec extends FlatSpec with Matchers with ScalaFutures {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(Span(500, Milliseconds))

  "a connection" should "be short-circuitable" in {
    val local = Local[ExampleInterfaces.Simple]
    val remote = Remote[ExampleInterfaces.Simple](Id.standard)

    val flow = Connection.bidi(local,remote,(l: ExampleInterfaces.Simple) => new SimpleDependentImpl(l))

    val connection = flow.join(flow).run()

    whenReady(connection.remote.nested.foo) { ft =>
      ft shouldBe 32
    }
    connection.remote.g("test")
    Thread.sleep(100)
    whenReady(connection.local.f(0)) { v =>
      v shouldBe "from g: test"
    }
    connection.close()
  }
}
