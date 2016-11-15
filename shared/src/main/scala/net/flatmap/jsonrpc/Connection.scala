package net.flatmap.jsonrpc

import akka.NotUsed
import akka.stream._
import akka.stream.contrib.PartitionWith
import akka.stream.scaladsl._
import akka.util.{ByteString, Timeout}

import scala.concurrent.ExecutionContext
import scala.util.{Success, Try}
import scala.util.control.NonFatal


trait Connection[L <: Interface,R <: Interface] {
  implicit def local: Local[L]
  implicit def remote: Remote[R]
  def close()(implicit timeout: Timeout) = remote.close()
}

object Connection { self =>
  def apply[L <: Interface,R <: Interface](local: L, remote: R)(impl: Remote[R] => Local[L],
    codec: BidiFlow[Message,ByteString,ByteString,MessageWithBypass,NotUsed] = Codec.standard atop Framing.byteString
  )(implicit ec: ExecutionContext): Flow[ByteString,ByteString,Connection[L,R]] = {
    bidi(local,remote)(impl,codec)
  }

  def bidi[L <: Interface,R <: Interface,IO](local: L, remote: R)(impl: Remote[R] => Local[L],
    codec: BidiFlow[Message,IO,IO,MessageWithBypass,NotUsed]
  )(implicit ec: ExecutionContext): Flow[IO,IO,Connection[L,R]] = {
    val r = Remote(remote)

    val l = Flow[RequestMessage].zipWithMat(Source.maybe[Local[L]].flatMapConcat(l => Source.repeat(l))) {
      case (msg,local) => local.messageHandler(msg)
    } (Keep.right).collect {
      case Some(msg) => msg
    }

    val partitioner = PartitionWith[Message,RequestMessage,ResponseMessage] {
      case r: RequestMessage => Left(r)
      case r: ResponseMessage => Right(r)
    }

    val bypassPartitioner = PartitionWith[MessageWithBypass,ResponseMessage.Failure,Message] {
      case BypassEnvelope(response) => Left(response)
      case other: Message => Right(other)
    }

    val handler = GraphDSL.create(l, r, partitioner, bypassPartitioner) { (l,r, _, _) =>
      val localImpl = impl(r)
      l.success(Some(localImpl))
      new Connection[L,R] {
        implicit def local: Local[L] = localImpl
        implicit def remote: Remote[R] = r
      }
    } { implicit b =>
      (local, remote, partition, bypass) =>
      import GraphDSL.Implicits._

      val merge = b.add(Merge[Message](3))

      bypass.out0                ~>                            merge
      bypass.out1 ~> partition.in; partition.out0 ~> local  ~> merge
                                   partition.out1 ~> remote ~> merge

      FlowShape(bypass.in, merge.out)
    }

    codec.reversed.joinMat(handler)(Keep.right)
  }
}
