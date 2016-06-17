/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import org.reactivestreams.Publisher
import scala.annotation.implicitNotFound
import scala.concurrent.Future

@implicitNotFound(msg = "Don't know how to create a stream from instances of type ${T}. Maybe you'd like to provide an `implicit Streamable[${T}]`?")
abstract class Streamable[-T] {
  type Out
  def apply(value: T): Stream[Out]
}

object Streamable {
  type Aux[T, Out0] = Streamable[T] { type Out = Out0 }

  private[core] val stream =
    new Streamable[Stream[AnyRef]] {
      type Out = AnyRef
      def apply(value: Stream[AnyRef]) = value
    }
  implicit def forStream[T]: Aux[Stream[T], T] = stream.asInstanceOf[Aux[Stream[T], T]]

  private[core] val iterable =
    new Streamable[Iterable[AnyRef]] {
      type Out = AnyRef
      def apply(value: Iterable[AnyRef]): Stream[AnyRef] = Stream.fromIterable(value)
    }
  implicit def forIterable[T]: Aux[Iterable[T], T] = iterable.asInstanceOf[Aux[Iterable[T], T]]

  private[core] val iterator =
    new Streamable[Iterator[AnyRef]] {
      type Out = AnyRef
      def apply(value: Iterator[AnyRef]): Stream[AnyRef] = Stream.fromIterator(value)
    }
  implicit def forIterator[T]: Aux[Iterator[T], T] = iterable.asInstanceOf[Aux[Iterator[T], T]]

  private[core] val publisher =
    new Streamable[Publisher[AnyRef]] {
      type Out = AnyRef
      def apply(value: Publisher[AnyRef]): Stream[AnyRef] = ???
    }
  implicit def forPublisher[T]: Aux[Publisher[T], T] = iterable.asInstanceOf[Aux[Publisher[T], T]]

  private[core] val future =
    new Streamable[Future[AnyRef]] {
      type Out = AnyRef
      def apply(value: Future[AnyRef]): Stream[AnyRef] = ???
    }
  implicit def forFuture[T]: Aux[Future[T], T] = future.asInstanceOf[Aux[Future[T], T]]
}
