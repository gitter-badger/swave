/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl

import swave.core.PipeElem

private[swave] sealed trait Port {
  def pipeElem: PipeElem.Basic

  def xSeal(ctx: RunContext): Unit

  def isSealed: Boolean
}

private[swave] sealed trait Inport extends Port {

  def subscribe()(implicit from: Outport): Unit

  def request(n: Long)(implicit from: Outport): Unit

  def cancel()(implicit from: Outport): Unit
}

private[swave] sealed trait Outport extends Port {

  def onSubscribe()(implicit from: Inport): Unit

  def onNext(elem: AnyRef)(implicit from: Inport): Unit

  def onComplete()(implicit from: Inport): Unit

  def onError(error: Throwable)(implicit from: Inport): Unit
}

private[swave] abstract class PipeElemImpl extends Inport with Outport { this: PipeElem.Basic ⇒
  private[this] var _moduleStarts = List.empty[PipeElem.Module]
  private[this] var _moduleEnds = List.empty[PipeElem.Module]

  final def pipeElem = this

  final def moduleEntries = _moduleStarts
  final def moduleExits = _moduleEnds

  final def markModuleEntry(module: PipeElem.Module): Unit = _moduleStarts ::= module
  final def markModuleExit(module: PipeElem.Module): Unit = _moduleEnds ::= module
}
