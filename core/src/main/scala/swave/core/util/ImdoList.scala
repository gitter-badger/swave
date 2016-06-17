/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.util

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

/**
 * Intrusive, mutable, double-linked list.
 *
 * Actually the list forms a ring, i.e. the first node's `prev` member points to the lists last node.
 */
private[swave] abstract class ImdoList[L >: Null <: ImdoList[L]] {
  protected final var _prev: L = _
  protected final var _next: L = _
}

private[swave] object ImdoList {

  def insert[L >: Null <: ImdoList[L]](node: L, before: L): Unit = {
    requireArg(node.nonEmpty)
    requireArg(before.nonEmpty)
    requireArg((node._next eq node) && (node._prev eq node))
    val prev = before._prev
    prev._next = node
    node._prev = prev
    before._prev = node
    node._next = before
  }

  implicit class ImdoListOps[L >: Null <: ImdoList[L]](private val underlying: L) extends AnyVal {

    def isEmpty: Boolean = underlying eq null
    def nonEmpty: Boolean = underlying ne null

    def size: Int = {
      @tailrec def rec(current: L, count: Int): Int =
        if (current ne underlying) rec(current._next, count + 1) else count
      if (nonEmpty) rec(underlying._next, 1) else 0
    }

    def prev: L = if (nonEmpty) underlying._prev else throw new NoSuchElementException("prev of empty list")
    def next: L = if (nonEmpty) underlying._next else throw new NoSuchElementException("next of empty list")

    def append(node: L): L = prepend(node)._next

    def prepend(node: L): L = {
      if (isEmpty) {
        node._next = node
        node._prev = node
      } else insert(node, underlying)
      node
    }

    def foreach(f: L ⇒ Unit): Unit = {
      @tailrec def rec(current: L): Unit = {
        f(current)
        if (current._next ne underlying) rec(current._next)
      }
      if (nonEmpty) rec(underlying)
    }

    def toList: List[L] = {
      val buf = ListBuffer.empty[L]
      foreach { node ⇒ buf += node; () }
      buf.toList
    }
  }
}
