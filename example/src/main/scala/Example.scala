import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source, StreamConverters}
import io.circe.Json
import net.flatmap.jsonrpc._

import scala.io.StdIn

trait ExampleInterface {
  def sayHello(msg: String, foo: Int): Unit
}

object ExampleInterfaceImpl extends ExampleInterface {
  override def sayHello(msg: String, foo: Int): Unit = println(msg)
}

object Example extends App {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  val local = Flow[RequestMessage].collect {
    case _ if false => Response.Success(Id.Null,Json.Null)
  }

  val remote = Remote[ExampleInterface]

  val localClient = Local[ExampleInterface](ExampleInterfaceImpl)

  val src = Source.single(Notification("sayHello",Some(NamedParameters(Map("msg" -> Json.fromString("hallo weld!"), "foo" -> Json.fromInt(7))))))

  ((src via localClient) to Sink.foreach(println)).run()

  val connection = Connection.create(local,remote)

  val interface = Connection.open(Source.empty,StreamConverters.fromOutputStream(() => System.out),connection)

  interface.sayHello("Hallo",8)

  StdIn.readLine()

  system.terminate()
  sys.exit()
}
