/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.inout

import scala.annotation.tailrec
import scala.concurrent.duration._
import swave.core.macros.StageImpl
import swave.core.{ PipeElem, Stream }
import swave.core.impl.stages.source.SubSourceStage
import swave.core.impl.{ RunContext, Outport, Inport }
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class InjectStage(timeout: Duration) extends InOutStage with PipeElem.InOut.Inject { stage =>

  def pipeElemType: String = "inject"
  def pipeElemParams: List[Any] = Nil

  private[this] var buffer: RingBuffer[AnyRef] = _

  connectInOutAndSealWith { (ctx, in, out) ⇒
    ctx.registerForXStart(this)
    running(ctx, in, out, if (timeout eq Duration.Undefined) ctx.env.settings.subscriptionTimeout else timeout)
  }

  def running(ctx: RunContext, in: Inport, out: Outport, subscriptionTimeout: Duration) = {

    def awaitingXStart() = state(
      xStart = () => {
        buffer = new RingBuffer[AnyRef](roundUpToNextPowerOf2(ctx.env.settings.maxBatchSize))
        in.request(buffer.capacity.toLong)
        noSubAwaitingElem(buffer.capacity, mainRemaining = 0)
      })

    /**
     * Buffer non-empty, no sub-stream open.
     * Waiting for the next request from the main downstream.
     *
     * @param pending number of elements already requested from upstream but not yet received (> 0)
     *                or 0, if the buffer is full
     */
    def noSubAwaitingMainDemand(pending: Int): State = state(
      request = (n, from) ⇒ if (from eq out) awaitingSubDemand(emitNewSub(), pending, (n - 1).toLong) else stay(),
      cancel = from => if (from eq out) stopCancel(in) else stay(),

      onNext = (elem, _) ⇒ {
        requireState(buffer.write(elem))
        noSubAwaitingMainDemand(pendingAfterReceive(pending))
      },

      onComplete = _ => noSubAwaitingMainDemandUpstreamGone(),
      onError = stopErrorF(out))

    /**
     * Buffer non-empty, upstream already completed, no sub-stream open.
     * Waiting for the next request from the main downstream.
     */
    def noSubAwaitingMainDemandUpstreamGone(): State = state(
      request = (n, from) ⇒ {
        if (from eq out) {
          val s = emitNewSub()
          s.xEvent(SubSourceStage.EnableSubscriptionTimeout)
          awaitingSubDemandUpstreamGone(s, (n - 1).toLong)
        } else stay()
      },

      cancel = from => if (from eq out) stopCancel(in) else stay())

    /**
     * Buffer empty, no sub-stream open, demand signalled to upstream.
     * Waiting for the next element from upstream.
     *
     * @param pending       number of elements already requested from upstream but not yet received, > 0
     * @param mainRemaining number of elements already requested by downstream but not yet delivered, >= 0
     */
    def noSubAwaitingElem(pending: Int, mainRemaining: Long): State = state(
      request = (n, from) ⇒ if (from eq out) noSubAwaitingElem(pending, mainRemaining ⊹ n) else stay(),
      cancel = from => if (from eq out) stopCancel(in) else stay(),

      onNext = (elem, _) ⇒ {
        requireState(buffer.write(elem))
        if (mainRemaining > 0) awaitingSubDemand(emitNewSub(), pendingAfterReceive(pending), mainRemaining - 1)
        else noSubAwaitingMainDemand(pendingAfterReceive(pending))
      },

      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))

    /**
     * Buffer non-empty, sub-stream open.
     * Waiting for the next request from the sub-stream.
     *
     * @param sub           the currently open sub-stream
     * @param pending       number of elements already requested from upstream but not yet received (> 0),
     *                      or 0, if the buffer is full
     * @param mainRemaining number of elements already requested by downstream but not yet delivered, >= 0
     */
    def awaitingSubDemand(sub: SubSourceStage, pending: Int, mainRemaining: Long): State = state(
      request = (n, from) ⇒ {
        @tailrec def rec(nn: Int): State =
          if (buffer.nonEmpty) {
            if (nn > 0) {
              sub.onNext(buffer.unsafeRead())
              rec(nn - 1)
            } else awaitingSubDemand(sub, pendingAfterBufferRead(pending), mainRemaining)
          } else awaitingElem(sub, pendingAfterBufferRead(pending), subRemaining = nn.toLong, mainRemaining)

        if (from eq sub) rec(n)
        else if (from eq out) awaitingSubDemand(sub, pending, mainRemaining ⊹ n)
        else stay()
      },

      cancel = from => {
        if (from eq sub) {
          if (mainRemaining > 0) awaitingSubDemand(emitNewSub(), pending, mainRemaining - 1)
          else noSubAwaitingMainDemand(pending)
        } else if (from eq out) {
          sub.xEvent(SubSourceStage.EnableSubscriptionTimeout)
          awaitingSubDemandDownstreamGone(sub, pending)
        }
        else stay()
      },

      onNext = (elem, _) ⇒ {
        requireState(buffer.write(elem))
        awaitingSubDemand(sub, pendingAfterReceive(pending), mainRemaining)
      },

      onComplete = _ => {
        sub.xEvent(SubSourceStage.EnableSubscriptionTimeout)
        awaitingSubDemandUpstreamGone(sub, mainRemaining)
      },
      onError = stopErrorSubAndMainF(sub))

    /**
     * Buffer non-empty, sub-stream open, upstream already completed.
     * Waiting for the next request from the sub-stream.
     *
     * @param sub           the currently open sub-stream
     * @param mainRemaining number of elements already requested by downstream but not yet delivered, >= 0
     */
    def awaitingSubDemandUpstreamGone(sub: SubSourceStage, mainRemaining: Long): State = state(
      request = (n, from) ⇒ {
        @tailrec def rec(nn: Int): State =
          if (buffer.nonEmpty) {
            if (nn > 0) {
              sub.onNext(buffer.unsafeRead())
              rec(nn - 1)
            } else awaitingSubDemandUpstreamGone(sub, mainRemaining)
          } else {
            sub.onComplete()
            stopComplete(out)
          }

        if (from eq sub) rec(n)
        else if (from eq out) awaitingSubDemandUpstreamGone(sub, mainRemaining ⊹ n)
        else stay()
      },

      cancel = from => {
        if (from eq sub) {
          if (mainRemaining > 0) awaitingSubDemandUpstreamGone(emitNewSub(), mainRemaining - 1)
          else noSubAwaitingMainDemandUpstreamGone()
        } else if (from eq out) awaitingSubDemandUpAndDownstreamGone(sub)
        else stay()
      })

    /**
     * Buffer non-empty, sub-stream open, main downstream already cancelled.
     * Waiting for the next request from the sub-stream.
     *
     * @param sub     the currently open sub-stream
     * @param pending number of elements already requested from upstream but not yet received (> 0),
     *                or 0, if the buffer is full
     */
    def awaitingSubDemandDownstreamGone(sub: SubSourceStage, pending: Int): State = state(
      request = (n, from) ⇒ {
        @tailrec def rec(nn: Int): State =
          if (buffer.nonEmpty) {
            if (nn > 0) {
              sub.onNext(buffer.unsafeRead())
              rec(nn - 1)
            } else awaitingSubDemandDownstreamGone(sub, pendingAfterBufferRead(pending))
          } else awaitingElemDownstreamGone(sub, pendingAfterBufferRead(pending), subRemaining = nn.toLong)

        if (from eq sub) rec(n) else stay()
      },

      cancel = from => if (from eq sub) stopCancel(in) else stay(),

      onNext = (elem, _) ⇒ {
        requireState(buffer.write(elem))
        awaitingSubDemandDownstreamGone(sub, pendingAfterReceive(pending))
      },

      onComplete = _ => awaitingSubDemandUpAndDownstreamGone(sub),
      onError = stopErrorF(sub))

    /**
     * Buffer non-empty, sub-stream open, upstream already completed, main downstream already cancelled.
     * Waiting for the next request from the sub-stream.
     *
     * @param sub the currently open sub-stream
     */
    def awaitingSubDemandUpAndDownstreamGone(sub: SubSourceStage): State = state(
      request = (n, from) ⇒ {
        @tailrec def rec(nn: Int): State =
          if (buffer.nonEmpty) {
            if (nn > 0) {
              sub.onNext(buffer.unsafeRead())
              rec(nn - 1)
            } else awaitingSubDemandUpAndDownstreamGone(sub)
          } else stopComplete(sub)

        if (from eq sub) rec(n) else stay()
      },

      cancel = from => if (from eq sub) stopCancel(in) else stay())

    /**
     * Buffer empty, sub-stream open, demand signalled to upstream.
     * Waiting for the next element from upstream.
     *
     * @param sub           the currently open sub-stream
     * @param pending       number of elements already requested from upstream but not yet received, > 0
     * @param subRemaining  number of elements already requested by sub-stream but not yet delivered, >= 0
     * @param mainRemaining number of elements already requested by downstream but not yet delivered, >= 0
     */
    def awaitingElem(sub: SubSourceStage, pending: Int, subRemaining: Long, mainRemaining: Long): State = state(
      request = (n, from) ⇒ {
        if (from eq sub) awaitingElem(sub, pending, subRemaining ⊹ n, mainRemaining)
        else if (from eq out) awaitingElem(sub, pending, subRemaining, mainRemaining ⊹ n)
        else stay()
      },

      cancel = from => {
        if (from eq sub) noSubAwaitingElem(pending, mainRemaining)
        else if (from eq out) {
          sub.xEvent(SubSourceStage.EnableSubscriptionTimeout)
          awaitingElemDownstreamGone(sub, pending, subRemaining)
        } else stay()
      },

      onNext = (elem, _) ⇒ {
        if (subRemaining > 0) {
          sub.onNext(elem)
          awaitingElem(sub, pendingAfterReceive(pending), subRemaining - 1, mainRemaining)
        } else {
          requireState(buffer.write(elem))
          awaitingSubDemand(sub, pendingAfterReceive(pending), mainRemaining)
        }
      },

      onComplete = stopCompleteSubAndMainF(sub),
      onError = stopErrorSubAndMainF(sub))

    /**
     * Buffer empty, sub-stream open, main downstream cancelled, demand signalled to upstream.
     * Waiting for the next element from upstream.
     *
     * @param sub           the currently open sub-stream
     * @param pending       number of elements already requested from upstream but not yet received, > 0
     * @param subRemaining  number of elements already requested by sub-stream but not yet delivered, >= 0
     */
    def awaitingElemDownstreamGone(sub: SubSourceStage, pending: Int, subRemaining: Long): State = state(
      request = (n, from) ⇒ if (from eq sub) awaitingElemDownstreamGone(sub, pending, subRemaining ⊹ n) else stay(),
      cancel = from => if (from eq sub) stopCancel(in) else stay(),

      onNext = (elem, _) ⇒ {
        if (subRemaining > 0) {
          sub.onNext(elem)
          awaitingElemDownstreamGone(sub, pendingAfterReceive(pending), subRemaining - 1)
        } else {
          requireState(buffer.write(elem))
          awaitingSubDemandDownstreamGone(sub, pendingAfterReceive(pending))
        }
      },

      onComplete = stopCompleteSubAndMainF(sub),
      onError = stopErrorSubAndMainF(sub))

    ///////////////////////// helpers //////////////////////////

    def emitNewSub() = {
      val s = new SubSourceStage(ctx, this, subscriptionTimeout)
      out.onNext(new Stream(s).asInstanceOf[AnyRef])
      s
    }

    def pendingAfterReceive(pend: Int) =
      if (pend == 1) {
        val avail = buffer.available
        if (avail > 0) in.request(avail.toLong)
        avail
      } else pend - 1

    def pendingAfterBufferRead(pend: Int) =
      if (pend == 0) {
        val avail = buffer.available
        if (avail > 0) in.request(avail.toLong)
        avail
      } else pend

    def stopCompleteSubAndMainF(s: SubSourceStage)(i: Inport): State = {
      s.onComplete()
      stopComplete(out)
    }

    def stopErrorSubAndMainF(s: SubSourceStage)(e: Throwable, i: Inport): State = {
      s.onError(e)
      stopError(e, out)
    }

    awaitingXStart()
  }
}
