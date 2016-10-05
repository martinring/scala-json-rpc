# scala-json-rpc

Scala Implementation of [JSON RPC 2.0](http://www.jsonrpc.org/specification) 
based on [circe](https://travisbrown.github.io/circe/) and [Akka Streams](http://doc.akka.io/docs/akka/2.4.11/scala/stream/index.html).

Features automatic derivation (using macros) of abstract traits describing the protocol.

Compiles to JVM and JavaScript via [Scala.js](https://www.scala-js.org/)

## Getting Started

Add the following to your sbt build definition

```scala
// Add flatmap bintray repo
resolvers += Resolver.bintrayRepo("net/flatmap", "maven")

// For scala.js projects
libraryDependencies += "net.flatmap" %%% "scala-json-rpc" % "0.1"

// For ordinary Scala projects
libraryDependencies += "net.flatmap" %% "scala-json-rpc" % "0.1"
```

### Defining a Protocol

Define your server and client interfaces as Scala traits:
 
```scala
trait ExampleServer {
  // all jsonrpc methods must either return `Unit` or `Future[T]`
  def sayHello(s: String): Future[String]  
}
```

```scala
trait ExampleClient {
  def foo: Future[Int]
}
```

### Deriving Implementations

Implement the client:

```scala
object ExampleClientImpl {
  def foo: Future[Int] = Future.successful(42)
}
```


Derive message-flow representations of the server and the client:
 
```
val local: Flow[RequestMessage,Response,NotUsed]  = 
  Local[ExampleClient](ExampleClientImpl)
  
val remote: Flow[Response,RequestMessage,ExampleServer] = 
  Remote[ExampleServer]
```

### Opening a connection

Initialize and open your connection 

```
val connection: Flow[ByteString,ByteString,ExampleServer] = 
  Connection.create(local,remote)
  
val server: ExampleServer = Connection.open(
  // Read from stdin
  in  = StreamConverters.fromInputStream(() => System.in),
  // Write to stdout
  out = StreamConverters.fromOutputStream(() => System.out),
  connection = connection)
```

Use it:

```
val f = server.sayHello("bar")
```

To use websockets of http use `Connection.create(local,remote,framing = Framing.none)` and connect flow to websockets via `Connection.open`

### License

MIT