package net.flatmap.jsonrpc.util

import akka.stream.{Inlet, Outlet, Shape}
import akka.stream.scaladsl.{Flow, GraphDSL, Partition}

import scala.collection.immutable.Seq
import scala.reflect.ClassTag

case class TypePartitionShape[In,Out1,Out2](
   in: Inlet[In],
   out1: Outlet[Out1],
   out2: Outlet[Out2]
 ) extends Shape {
  override def inlets: Seq[Inlet[_]] = in :: Nil
  override def outlets: Seq[Outlet[_]] = out1 :: out2 :: Nil
  override def deepCopy(): Shape =
    TypePartitionShape(in.carbonCopy(),out1.carbonCopy(),out2.carbonCopy())
  override def copyFromPorts(inlets: Seq[Inlet[_]], outlets: Seq[Outlet[_]]): Shape =
    TypePartitionShape(inlets(0).as[In],outlets(0).as[Out1],outlets(1).as[Out2])
}

object TypePartition{
  def apply[In,Out1 <: In,Out2 <: In](implicit classTag1: ClassTag[Out1], classTag2: ClassTag[Out2]) = GraphDSL.create() { implicit b =>
    import GraphDSL.Implicits._
    val partition = b.add(Partition[In](2,{
      case r: Out1 => 0
      case r: Out2 => 1
    }))

    val castRequest = b.add(Flow[In].map(_.asInstanceOf[Out1]))
    val castRespose = b.add(Flow[In].map(_.asInstanceOf[Out2]))

    partition.out(0) ~> castRequest.in
    partition.out(1) ~> castRespose.in

    new TypePartitionShape(partition.in,castRequest.out,castRespose.out)
  }
}
