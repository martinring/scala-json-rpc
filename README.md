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
libraryDependencies += "net.flatmap" %%% "jsonrpc" % "0.3.1"

// For ordinary Scala projects
libraryDependencies += "net.flatmap" %% "jsonrpc" % "0.3.1"
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

```scala
val local: Flow[RequestMessage,Response,NotUsed]  =
  Local[ExampleClient](ExampleClientImpl)

val remote: Flow[Response,RequestMessage,ExampleServer] =
  Remote[ExampleServer](Id.standard)
```

### Opening a connection

Initialize and open your connection

```scala
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

```scala
val f = server.sayHello("bar")
```

will create the following request: (and listen for response on id 0)

```json
{
  "jsonrpc": "2.0",
  "id": 0,
  "method": "sayHello",
  "params": {
    "s": "bar"
  }
}
```

## Using with HTTP or WebSockets

To use websockets or http use `Connection.create(local,remote,framing = Framing.none)` and connect flow to websockets or http requests via `Connection.open`

## Advanced Features

### Custom Names

To customize names, use `@JsonRPC` annotation:

```scala
@JsonRPC("class")
def clazz: Future[Something]
```

### Namespaces (Sub-protocols)

Sometimes it is desirable to nest some namespaces into the base protocol. This can
be achieved with sub-protocols:

```scala
trait MyProtocol {
  @JsonRPCNamespace(prefix = "arbitrary/")
  def arbitrary: MySubProtocol
}

trait MySubProtocol {
  def subtract(a: Int, b: Int): Future[Int]
}
```

Calls to MyProtocol.arbitrary.subtract will result in calls of method `"arbitrary/subtract"`

### License

MIT
