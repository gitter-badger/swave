/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.inout

import scala.annotation.tailrec
import swave.core.macros.StageImpl
import swave.core.PipeElem
import swave.core.impl.{ Outport, Inport }
import swave.core.util.RingBuffer
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class BufferBackpressureStage(size: Int) extends InOutStage
  with PipeElem.InOut.BufferWithBackpressure {

  requireArg(size > 0)

  def pipeElemType: String = "bufferBackpressure"
  def pipeElemParams: List[Any] = size :: Nil

  private[this] val buffer = new RingBuffer[AnyRef](roundUpToNextPowerOf2(size))

  connectInOutAndSealWith { (ctx, in, out) ⇒
    ctx.registerForXStart(this)
    awaitingXStart(in, out)
  }

  /**
   * @param in  the active upstream
   * @param out the active downstream
   */
  def awaitingXStart(in: Inport, out: Outport) = state(
    xStart = () => {
      in.request(size.toLong)
      running(in, out, size.toLong, 0)
    })

  /**
   * Upstream and downstream active.
   * We always have `buffer.available` elements pending from upstream,
   * i.e. we are trying to always have the buffer filled.
   *
   * @param in        the active upstream
   * @param out       the active downstream
   * @param pending   number of elements already requested from upstream but not yet received, >= 0
   * @param remaining number of elements already requested by downstream but not yet delivered, >= 0
   */
  def running(in: Inport, out: Outport, pending: Long, remaining: Long): State = {

    @tailrec def handleDemand(pend: Long, rem: Long): State =
      if (rem > 0 && buffer.nonEmpty) {
        out.onNext(buffer.unsafeRead())
        handleDemand(pend, rem - 1)
      } else {
        val alreadyRequested = pend ⊹ buffer.size
        val target = rem ⊹ size
        val delta = target - alreadyRequested
        val newPending =
          if (delta > (size >> 1)) { // we suppress requesting a number of elems < half the buffer size
            in.request(delta)
            pend + delta
          } else pend
        running(in, out, newPending, rem)
      }

    state(
      request = (n, _) ⇒ handleDemand(pending, remaining ⊹ n),
      cancel = stopCancelF(in),

      onNext = (elem, _) ⇒ {
        requireState(buffer.canWrite)
        buffer.write(elem)
        handleDemand(pending - 1, remaining)
      },

      onComplete = _ ⇒ {
        if (remaining > 0) {
          requireState(buffer.isEmpty)
          stopComplete(out)
        } else {
          if (buffer.isEmpty) stopComplete(out) else draining(out)
        }
      },

      onError = stopErrorF(out))
  }

  /**
   * Upstream completed, downstream active and buffer non-empty.
   *
   * @param out the active downstream
   */
  def draining(out: Outport) = state(
    request = (n, _) ⇒ {
      @tailrec def rec(nn: Int): State =
        if (buffer.nonEmpty) {
          if (nn > 0) {
            out.onNext(buffer.unsafeRead())
            rec(nn - 1)
          } else stay()
        } else stopComplete(out)
      rec(n)
    },

    cancel = stopF)
}
