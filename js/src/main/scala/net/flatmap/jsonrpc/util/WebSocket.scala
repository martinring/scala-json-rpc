package net.flatmap.jsonrpc.util

import java.net.URI

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import akka.util.ByteString
import org.reactivestreams.{Processor, Subscriber, Subscription}

import scala.collection.mutable
import scala.scalajs.js
import scala.scalajs.js.annotation.JSName
import scala.scalajs.js.typedarray._

@js.native
private trait MessageEvent extends js.Object {
  def data: Any = js.native
}

@js.native
private trait ErrorEvent extends js.Object {
  def colno: Int = js.native
  def filename: String = js.native
  def lineno: Int = js.native
  def message: String = js.native
}

@js.native
private trait CloseEvent extends js.Object {
  def wasClean: Boolean = js.native
  def reason: String = js.native
  def code: Int = js.native
}

@js.native
private class WebSocket(var url: String = js.native, var protocol: String = js.native) extends js.Object {
  def this(url: String, protocol: js.Array[String]) = this("", "")
  def readyState: Int = js.native
  def bufferedAmount: Int = js.native
  var onopen: js.Function1[js.Object, _] = js.native
  def extensions: String = js.native
  var onmessage: js.Function1[MessageEvent, _] = js.native
  var onclose: js.Function1[CloseEvent, _] = js.native
  var onerror: js.Function1[ErrorEvent, _] = js.native
  var binaryType: String = js.native
  def close(code: Int = js.native,
            reason: String = js.native): Unit = js.native
  def send(data: String): Unit = js.native
  def send(data: ArrayBuffer): Unit = js.native
}

private class WebSocketProcessor(url: URI, maxInFlight: Int) extends Processor[ByteString,ByteString] {
  val socket = new WebSocket(url.toString)
  socket.binaryType = "arraybuffer"

  val inputBuffer = mutable.Buffer.empty[ByteString]
  private var subscription: Subscription = null

  def onSubscribe(s: Subscription): Unit = {
    subscription.request(maxInFlight)
    subscription = s
  }

  def onNext(t: ByteString): Unit = {
    socket.send(js.typedarray.byteArray2Int8Array(t.toArray).buffer)
  }

  def onError(t: Throwable): Unit = {
    socket.close(reason = t.getMessage)
  }

  def onComplete(): Unit = {
    socket.close()
  }

  def subscribe(subscriber: Subscriber[_ >: ByteString]): Unit = {
    var demand = 0l
    val outputBuffer = mutable.Buffer.empty[ByteString]

    socket.onopen = { (e: js.Object) =>
      subscriber.onSubscribe(new Subscription {
        def cancel(): Unit = {
          socket.close()
          demand = 0
        }
        def request(n: Long): Unit = {
          val es = Math.min(outputBuffer.length,demand + n)
          for (i <- 1 to es.toInt) {
            subscriber.onNext(outputBuffer.remove(0))
          }
          demand = demand + n - es
        }
      })
    }

    socket.onmessage = { (e: MessageEvent) =>
      val data = e.data.asInstanceOf[ArrayBuffer]
      val bs = ByteString(TypedArrayBuffer.wrap(data))
      if (demand > 0) {
        subscriber.onNext(bs)
        demand -= 1
      } else {
        outputBuffer.append(bs)
      }
    }

    socket.onclose = { (e: CloseEvent) =>
      subscriber.onComplete()
    }

    socket.onerror = { (e: ErrorEvent) =>
      subscriber.onError(new Exception(e.message))
    }
  }
}

object WebSocket {
  def apply(url: URI): Flow[ByteString,ByteString,NotUsed] = {
    Flow.fromProcessor(() => new WebSocketProcessor(url, 4))
  }
}
