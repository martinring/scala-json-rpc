package net.flatmap.jsonrpc.util

import akka.actor.Cancellable

import scala.concurrent.{CanAwait, ExecutionContext, Future, Promise}
import scala.concurrent.duration.Duration
import scala.util.{Success, Try}


case class CancellableFuture[+T](future: Future[T], cancellable: Cancellable) extends Future[T] with Cancellable {
  def onComplete[U](f: (Try[T]) => U)(implicit executor: ExecutionContext): Unit =
    future.onComplete(f)
  def isCompleted: Boolean =
    future.isCompleted
  def value: Option[Try[T]] =
    future.value
  def ready(atMost: Duration)(implicit permit: CanAwait): CancellableFuture.this.type = {
    future.ready(atMost); this
  }
  def result(atMost: Duration)(implicit permit: CanAwait): T = {
    future.result(atMost)
  }
  def cancel(): Boolean = cancellable.cancel()
  def isCancelled: Boolean = cancellable.isCancelled
}

object CancellableFuture {
  private def cancelFuture() = {
    val c = Promise[Unit]
    val token = new Cancellable {
      def isCancelled: Boolean = c.isCompleted
      def cancel(): Boolean = c.trySuccess()
    }
    (token,c.future)
  }

  private val noop = new Cancellable {
    def cancel(): Boolean    = false
    def isCancelled: Boolean = true
  }

  def failed[T](exception: Throwable) =
    CancellableFuture(Future.failed[T](exception),noop)

  def flatten[T](f: Future[CancellableFuture[T]])(implicit ec: ExecutionContext): CancellableFuture[T] = {
    val (token,cancel) = cancelFuture()
    CancellableFuture(f.flatMap { f =>
      cancel.onComplete {
        case _ => f.cancel()
      }
      f
    }, token)
  }
}