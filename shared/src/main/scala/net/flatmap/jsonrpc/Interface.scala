package net.flatmap.jsonrpc

import akka.util.Timeout
import io.circe.{Decoder, Encoder}
import net.flatmap.jsonrpc.util.CancellableFuture

sealed trait MethodType[I <: Interface] {
  val name: String
  private [jsonrpc] def copyTo[I2 <: Interface]: MethodType[I2]
}

class RequestType[PI,PO,R,E,I <: Interface] private [jsonrpc] (val name: String)(
  implicit val paramEncoder: Encoder[PO], val paramDecoder: Decoder[PI],
  val resultEncoder: Encoder[R], val resultDecoder: Decoder[R],
  val errorEncoder: Encoder[E], val errorDecoder: Decoder[E]) extends MethodType[I] {

  def apply(p: PO)(implicit remote: Remote[I], timeout: Timeout): CancellableFuture[R] =
    remote.sendRequest[PO,R,E](this)(p)

  def contramapParameter[P2](contramap: P2 => PO) =
    new RequestType[PI,P2,R,E,I](name)(paramEncoder.contramap(contramap),paramDecoder,
      resultEncoder,resultDecoder,errorEncoder,errorDecoder)

  def mapParameter[P2](map: PI => P2) =
    new RequestType[P2,PO,R,E,I](name)(paramEncoder,paramDecoder.map(map),
      resultEncoder,resultDecoder,errorEncoder,errorDecoder)

  private [jsonrpc] def copyTo[I2 <: Interface] = new RequestType[PI,PO,R,E,I2](name)
}

class NotificationType[PI,PO,I <: Interface] private [jsonrpc] (val name: String)(
  implicit val paramEncoder: Encoder[PO],
  val paramDecoder: Decoder[PI]) extends MethodType[I] {

  def apply(p: PO)(implicit remote: Remote[I]): Unit =
    remote.sendNotification[PO](this)(p)

  def contramapParameter[P2](contramap: P2 => PO) =
    new NotificationType[PI,P2,I](name)(paramEncoder.contramap(contramap),paramDecoder)

  def mapParameter[P2](map: PI => P2) =
    new NotificationType[P2,PO,I](name)(paramEncoder,paramDecoder.map(map))

  private [jsonrpc] def copyTo[I2 <: Interface] = new NotificationType[PI,PO,I2](name)
}

sealed trait InterfaceLike {
  def methods: Set[MethodType[_]]
}

trait Interface extends InterfaceLike {
  private var methods_ = Set.empty[MethodType[_]]
  def methods = methods_

  protected def request[P,R,E](name: String)(implicit paramEncoder: Encoder[P], paramDecoder: Decoder[P],
    resultEncoder: Encoder[R], resultDecoder: Decoder[R],
    errorEncoder: Encoder[E], errorDecoder: Decoder[E]): RequestType[P,P,R,E,this.type] = {
    val method = new RequestType[P,P,R,E,this.type](name)
    methods_ += method
    method
  }

  protected def notification[P](name: String)(
    implicit paramEncoder: Encoder[P],
    paramDecoder: Decoder[P]): NotificationType[P,P,this.type]  = {
    val method = new NotificationType[P,P,this.type](name)
    methods_ += method
    method
  }
}

import shapeless._
import shapeless.ops.hlist._

class CombinedInterface[IS <: HList : LUBConstraint.<<:[Interface]#λ : IsDistinctConstraint](val interfaces: IS)
  (implicit toTraversable: ToTraversable.Aux[IS,List,Interface]) extends InterfaceLike {

  override def methods = interfaces.toList[Interface].toSet.flatMap((i: Interface) => i.methods)

  def and [T <: Interface](other: T)(
    implicit toTraversable: ToTraversable.Aux[T :: IS,List,Interface],
    distinct: IsDistinctConstraint[T :: IS]): CombinedInterface[T :: IS] = other match {
    case other =>
      new CombinedInterface[T :: IS](other :: interfaces)
  }
}

object CombinedInterface {
  def apply[A <: Interface](interface: A) =
    new CombinedInterface[A :: HNil](interface :: HNil)
}