package net.flatmap.jsonrpc

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import org.scalatest.AsyncFlatSpec
import akka.stream.scaladsl._

import scala.concurrent.duration._

/**
  * Created by martin on 04/11/2016.
  *
class SwitchFlowTest extends AsyncFlatSpec {
  implicit val system = ActorSystem("SwitchFlowTest")
  implicit val materialize = ActorMaterializer()

  val flows = Source.tick(200.milliseconds,200.millisecond,Unit).zipWithIndex.map { case (_,i) =>
    Flow[String].map(x => x + i)
  }

  "something" should "happen" in {
    val compl = Source.tick(10.milliseconds, 10.millisecond, "test").take(10).via(SwitchFlow(flows))
      .runWith(Sink.foreach(println))
    compl.map { done =>
      assert(true)
    }
  }
}
*/