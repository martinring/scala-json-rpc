# scala-json-rpc

Scala Implementation of [JSON RPC 2.0](http://www.jsonrpc.org/specification)
based on [circe](https://travisbrown.github.io/circe/) and [Akka Streams](http://doc.akka.io/docs/akka/2.4.11/scala/stream/index.html).

Features automatic derivation (using macros) of abstract traits describing the protocol.

Compiles to JVM and JavaScript via [Scala.js](https://www.scala-js.org/)

## Getting Started

Add the following to your sbt build definition

```scala
// Add flatmap bintray repo
resolvers += Resolver.bintrayRepo("flatmap", "maven")

// For scala.js projects
libraryDependencies += "net.flatmap" %%% "jsonrpc" % "0.4.0"

// For ordinary Scala projects
libraryDependencies += "net.flatmap" %% "jsonrpc" % "0.4.0"
```

### Defining a Protocol

Define your server and client interfaces as Scala traits:

```scala
trait ExampleServer {
  // all jsonrpc methods must either return `Unit` or `Future[T]`
  def sayHello(s: String): Future[String]
  
  // Custom Names
  @JsonRPC.Named("class")
  def clazz(i: Int): Unit
  
  // Sub-Protocols
  // Type 'Other' will be treated as protocol with
  // "other/" prefix on all methods
  @JsonRPC.Namespace("other/")
  def foo: Other    
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
class ExampleClientImpl(server: ExampleServer) {
  def foo: Future[Int] = 
    server.sayHello("world").map(_.length)  
}
```

Derive message-flow representations of the server and the client:

```scala
val local: Flow[RequestMessage,Response,Promise[Option[ExampleClient]]]  =
  Local[ExampleClient]

val remote: Flow[Response,RequestMessage,ExampleServer] =
  Remote[ExampleServer](Id.standard) // Use standard message Ids
```

### Opening a connection

Prepare your connection

```scala
val connectionFlow: Flow[ByteString,ByteString,Connection[ExampleClient,ExampleServer]] =
  Connection.bidi(local,remote,(srv: ExampleServer) => new ExampleClientImpl(srv))
```

Use it:

```scala
val in: Source[ByteString,Any] =  ... // input stream
val out: Sink[ByteString,Any] =   ... // output stream 
val connection = in.viaMat(connectionFlow)(Keep.right).to(out).run()
```

## Using with HTTP or WebSockets

To use websockets or http use `Connection.bidi(...,framing = Framing.none)` and connect flow to websockets or http requests via `Connection.open`


### License

[MIT](https://opensource.org/licenses/MIT)
