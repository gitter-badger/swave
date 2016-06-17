/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.inout

import scala.util.control.NonFatal
import swave.core.PipeElem
import swave.core.impl.{ Inport, Outport }
import swave.core.macros.StageImpl

// format: OFF
@StageImpl
private[core] final class ScanStage(zero: AnyRef, f: (AnyRef, AnyRef) ⇒ AnyRef)
  extends InOutStage with PipeElem.InOut.Scan {

  def pipeElemType: String = "scan"
  def pipeElemParams: List[Any] = zero :: f :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒ awaitingDemand(in, out) }

  /**
   * @param in  the active upstream
   * @param out the active downstream
   */
  def awaitingDemand(in: Inport, out: Outport): State = state(
    request = (n, _) ⇒ {
      out.onNext(zero)
      if (n > 1) in.request((n - 1).toLong)
      running(in, out, zero)
    },

    cancel = stopCancelF(in),
    onComplete = _ ⇒ drainingZero(out),
    onError = stopErrorF(out))

  /**
   * @param in        the active upstream
   * @param out       the active downstream
   * @param last      the last value produced
   */
  def running(in: Inport, out: Outport, last: AnyRef): State = state(
    request = requestF(in),
    cancel = stopCancelF(in),

    onNext = (elem, _) ⇒ {
      try {
        val next = f(last, elem)
        out.onNext(next)
        running(in, out, next)
      } catch { case NonFatal(e) => { in.cancel(); stopError(e, out) } }
    },

    onComplete = stopCompleteF(out),
    onError = stopErrorF(out))

  /**
   * Upstream completed without having produced any element, downstream active, awaiting first request.
   *
   * @param out  the active downstream
   */
  def drainingZero(out: Outport) = state(
    request = (_, _) ⇒ {
      out.onNext(zero)
      stopComplete(out)
    },

    cancel = stopF)
}
