package net.flatmap.jsonrpc

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.Timeout
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Milliseconds, Span}

import scala.concurrent.{Future, Promise}

class SimpleDependentImpl(implicit val remote: Remote[SimpleInterface.type]) extends Local(SimpleInterface) {
  private val promise   = Promise[String]
  val notificationValue = promise.future

  val implementation: Set[interface.MethodImplementation] = Set(
    SimpleInterface.exampleRequest := { i =>
      i.toString
    },
    SimpleInterface.exampleNotification := { i =>
      SimpleInterface.exampleNotification(i)
    }
  )
}

/**
  * Created by martin on 24.10.16.
  */
class ConnectionSpec extends FlatSpec with Matchers with ScalaFutures {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher
  implicit val requestTimeout = Timeout(1,TimeUnit.SECONDS)
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(Span(500, Milliseconds))

  "a connection" should "be short-circuitable" in {
    val flow = Connection.bidi(SimpleInterface,SimpleInterface) {
      remote => new SimpleDependentImpl()(remote)
    }

    val connection = flow.join(flow).run()

    import connection.remote

    remote.interface.exampleRequest(17).map { x =>
      connection.close()
      x shouldBe "17"
    }
  }
}
