import java.nio.charset.StandardCharsets

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, ClosedShape}
import akka.stream.scaladsl.{Flow, GraphDSL, Keep, RunnableGraph, Sink, Source, StreamConverters}
import akka.util.ByteString
import io.circe.Json
import net.flatmap.jsonrpc._

import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future
import scala.io.StdIn
import scala.util.Random

trait ExampleInterface {
  def sayHello(msg: String, foo: Int): Future[String]
}

object ExampleInterfaceImpl extends ExampleInterface {
  override def sayHello(msg: String, foo: Int) = Future {
    Thread.sleep(1000)
    msg + " " + foo
  }
}

object Example extends App {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  val remote = Remote[ExampleInterface]

  val local = Local[ExampleInterface](ExampleInterfaceImpl)

  val clientLocal = Local[ExampleInterface](ExampleInterfaceImpl)

  val clientRemote = Remote[ExampleInterface]

  val connection = Connection.create(local,remote)

  val clientConnection = Connection.create(clientLocal,clientRemote)

  val out       = Flow[ByteString].map{x => println("#### server ####\n" + x.decodeString(StandardCharsets.UTF_8)); x}
  val clientOut = Flow[ByteString].map{x => println("#### client ####\n" + x.decodeString(StandardCharsets.UTF_8)); x}

  val (interface,interfaceClient) = RunnableGraph.fromGraph(GraphDSL.create(connection,clientConnection) (Keep.both) {
    implicit b => (connection,clientConnection) =>
      import GraphDSL.Implicits._
      val o = b.add(out)
      val co = b.add(clientOut)
      connection ~> o  ~> clientConnection
      connection <~ co <~ clientConnection
      ClosedShape
  }).run()


  def f(s: String): Unit = interface.sayHello(s, Random.nextInt(10)).foreach(x => f(x))

  f("Test")
  f("Test2")

  StdIn.readLine()

  system.terminate()
}
