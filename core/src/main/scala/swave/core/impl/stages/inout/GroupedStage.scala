/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.inout

import scala.collection.mutable
import swave.core.macros.StageImpl
import swave.core.impl.{ Outport, Inport }
import swave.core.PipeElem
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class GroupedStage(groupSize: Int, emitSingleEmpty: Boolean, builder: mutable.Builder[Any, AnyRef]) extends InOutStage
  with PipeElem.InOut.Grouped {

  requireArg(groupSize > 0)

  def pipeElemType: String = "grouped"
  def pipeElemParams: List[Any] = groupSize :: emitSingleEmpty :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒ running(in, out) }

  def running(in: Inport, out: Outport): State = {

    /**
     * Waiting for a request from downstream.
     */
    def awaitingDemand() = state(
      request = (n, _) ⇒ {
        in.request(groupSize.toLong)
        collecting(groupSize, n.toLong, firstElem = true)
      },

      cancel = stopCancelF(in),
      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))

    /**
     * Gathering up the elements for the next group.
     *
     * @param pending   number of elements still required for completing the current group,
     *                  already requested from upstream but not yet received, > 0
     * @param remaining number of elements already requested by downstream but not yet delivered, > 0
     * @param firstElem true if we are still awaiting the very first element from upstream
     */
    def collecting(pending: Int, remaining: Long, firstElem: Boolean): State = state(
      request = (n, _) ⇒ collecting(pending, remaining ⊹ n, firstElem),
      cancel = stopCancelF(in),

      onNext = (elem, _) ⇒ {
        builder += elem
        if (pending == 1) {
          val group = builder.result()
          builder.clear()
          out.onNext(group)
          if (remaining > 1) {
            in.request(groupSize.toLong)
            collecting(groupSize, remaining - 1, firstElem = false)
          } else awaitingDemand()
        } else collecting(pending - 1, remaining, firstElem = false)
      },

      onComplete = _ ⇒ {
        if (pending < groupSize || firstElem && emitSingleEmpty)
          out.onNext(builder.result())
        builder.clear() // don't hold on to elements
        stopComplete(out)
      },

      onError = stopErrorF(out))

    awaitingDemand()
  }
}
