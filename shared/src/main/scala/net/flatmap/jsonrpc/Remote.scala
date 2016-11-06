package net.flatmap.jsonrpc

import java.util.concurrent.{CancellationException, TimeoutException}
import akka.actor.{ActorLogging, ActorRef, Cancellable, Props}
import akka.stream.actor._
import akka.stream._
import akka.stream.actor.ActorSubscriberMessage._
import akka.stream.scaladsl._

import scala.concurrent.{ExecutionContext, Future, Promise}
import akka.pattern.ask
import akka.util.Timeout
import io.circe.Json
import net.flatmap.jsonrpc.ResponseResolver.DispatchRequest
import net.flatmap.jsonrpc.util.CancellableFuture

class Remote[I <: Interface] private (val interface: I,
                                      sink: ActorRef,
                                      ids: Iterator[Id])
                                     (implicit ec: ExecutionContext) {

  def call[P,R,E](rt: interface.RequestType[P,R,E])(p: P)
                 (implicit timeout: Timeout): CancellableFuture[R] = {
    val id = ids.next()

    val request = ResponseResolver.DispatchRequest(
      Request(id,rt.name,rt.paramEncoder(p)),
      timeout
    )

    val response = CancellableFuture.flatten(
      (sink ? request).mapTo[CancellableFuture[ResponseMessage]]
    )

    val f = response.flatMap {
      case Response.Success(_, json) =>
        rt.resultDecoder.decodeJson(json).fold({ failure =>
          val err = ResponseError(ErrorCodes.ParseError, "result could not be parsed")
          Future.failed(err)
        }, Future.successful)
      case Response.Failure(_, json) =>
        Future.failed(json)
    }

    CancellableFuture(f,response.cancellable)
  }

  def call[P](nt: interface.NotificationType[P])(p: P) = {
    sink ! ResponseResolver.DispatchNotification(
      Notification(nt.name,nt.paramEncoder(p))
    )
  }

  def close()(implicit timeout: Timeout) =
    sink ! ResponseResolver.Cancel(timeout)
}

private object ResponseResolver {
  val props = Props(new ResponseResolver)
  case class Cancel(timeout: Timeout)
  case class DispatchRequest(request: Request, timeout: Timeout)
  case class DispatchNotification(notification: Notification)
}

private class ResponseResolver extends ActorSubscriber with ActorLogging {
  protected def requestStrategy: RequestStrategy = WatermarkRequestStrategy(16)

  private case object CancelUpstream
  private case class CancelRequest(id: Id, timeout: Boolean = false)

  implicit val dispatcher = context.dispatcher

  def receive = {
    case source: SourceQueueWithComplete[RequestMessage @unchecked] =>
      context.become(initialized(source,Map.empty))
  }

  def initialized(source: SourceQueueWithComplete[RequestMessage],
                  pending: Map[Id,(Promise[ResponseMessage],Cancellable)]): Receive = {
    case OnNext(response: ResponseMessage) =>
      pending.get(response.id).fold {
        log.warning("unhandled response " + response)
        // We are not waiting for this reponse...
        // TODO: Do something
      } { case (promise,timeout) =>
        timeout.cancel()
        context.become(initialized(source, pending - response.id))
        promise.success(response)
      }
    case OnComplete =>
      log.debug(s"upstream completed, failing all pending responses (${pending.size})")
      pending.foreach {
        case (id,(promise,timeout)) =>
          timeout.cancel()
          promise.failure(
            ResponseError(ErrorCodes.InternalError,"closed source")
          )
      }
      source.complete()
    case OnError(cause) =>
      log.debug(s"upstream failed, failing all pending responses (${pending.size})")
      pending.foreach {
        case (id,(promise,timeout)) =>
          timeout.cancel()
          promise.failure(
            ResponseError(ErrorCodes.InternalError,"failed source: " + cause.getMessage)
          )
      }
      source.fail(cause)
    case ResponseResolver.DispatchRequest(r,t) =>
      val promise = Promise[ResponseMessage]
      val cancel = new Cancellable {
        def isCancelled: Boolean = promise.isCompleted
        def cancel(): Boolean = if (!isCancelled) {
          self ! CancelRequest(r.id)
          true
        } else false
      }
      val timeout = context.system.scheduler.scheduleOnce(t.duration, self, CancelRequest(r.id, timeout = true))
      context.become(initialized(source, pending.updated(r.id, (promise, timeout))))
      log.debug("sending request: " + r)
      source.offer(r)
      sender ! CancellableFuture(promise.future, cancel)
    case CancelRequest(id,timeout) =>
      log.debug(s"cancellation requested for request '$id' (timeout is $timeout)")
      pending.get(id).foreach { case (p,c) =>
        log.debug(s"cancelling request '$id'")
        c.cancel()
        source.offer(Notification("$/cancel",Json.obj("id" -> Codec.encodeId(id))))
        context.become(initialized(source, pending - id))
        val error = if (timeout)
          new TimeoutException("the request timed out")
        else
          new CancellationException("the request has been cancelled")
        p.tryFailure(error)
      }
    case ResponseResolver.DispatchNotification(n) =>
      log.debug("sending notification: " + n)
      source.offer(n)
    case ResponseResolver.Cancel(timeout) =>
      log.debug(s"cancelling response resolver within $timeout")
      val t = context.system.scheduler.scheduleOnce(timeout.duration,self,CancelUpstream)
      source.complete()
      context.become(shuttingDown(pending, t))
  }

  def shuttingDown(pending: Map[Id,(Promise[ResponseMessage],Cancellable)], timeout: Cancellable): Receive = {
    if (pending.isEmpty) {
      log.debug("cancelling downstream since no more responses are pending")
      timeout.cancel()
      cancel()
    }

    {
      case OnNext(response: ResponseMessage) =>
        pending.get(response.id).fold {
          log.warning("unhandled response " + response)
          // We are not waiting for this reponse...
          // TODO: Do something
        } { case (promise,timeout) =>
          log.debug(s"dispatching response to request '${response.id}'")
          timeout.cancel()
          context.become(shuttingDown(pending - response.id, timeout))
          promise.success(response)
        }
      case OnComplete =>
        log.debug(s"upstream completed, failing all pending responses (${pending.size})")
        pending.foreach {
          case (id,(promise,timeout)) =>
            timeout.cancel()
            promise.tryFailure(
              ResponseError(ErrorCodes.InternalError,"closed source")
            )
        }
        timeout.cancel()
        context.stop(self)
      case OnError(cause) =>
        log.debug(s"upstream failed, failing all pending responses (${pending.size})")
        pending.foreach {
          case (id,(promise,timeout)) =>
            timeout.cancel()
            promise.failure(
              ResponseError(ErrorCodes.InternalError,"failed source: " + cause.getMessage)
            )
        }
        context.stop(self)
      case CancelRequest(id,isTimeout) =>
        log.debug(s"cancellation requested for request '$id' (timeout is $timeout)")
        pending.get(id).foreach { case (p,c) =>
          log.debug(s"cancelling request '$id'")
          c.cancel()
          context.become(shuttingDown(pending - id, timeout))
          p.tryFailure(new CancellationException(
            if (isTimeout) "the request timed out"
            else "request has been cancelled"
          ))
        }
      case CancelUpstream =>
        timeout.cancel()
        cancel()
      case DispatchRequest(_,_) =>
        sender ! CancellableFuture.failed(ResponseError(ErrorCodes.InternalError,"calling request on cancelled remote"))
      case ResponseResolver.Cancel(_) =>
        // this resolver is already cancelled
    }
  }
}

object Remote {
  def apply[I <: Interface](interface: I, idProvider: Iterator[Id] = Id.standard)
                           (implicit ec: ExecutionContext): Flow[ResponseMessage,RequestMessage,Remote[I]] = {
    val sink = Sink.actorSubscriber(ResponseResolver.props)
    val source = Source.queue[RequestMessage](bufferSize = 16, OverflowStrategy.backpressure)
    Flow.fromSinkAndSourceMat(sink,source) {
      (sink, source) =>
        sink ! source
        new Remote[I](interface, sink, idProvider)
    }
  }
}