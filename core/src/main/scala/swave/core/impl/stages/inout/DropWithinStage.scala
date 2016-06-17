/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.inout

import scala.concurrent.duration._
import swave.core.{ Cancellable, PipeElem }
import swave.core.impl.{ StreamRunner, Inport, Outport }
import swave.core.macros.StageImpl
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class DropWithinStage(duration: FiniteDuration) extends InOutStage with PipeElem.InOut.DropWithin {

  requireArg(duration >= Duration.Zero, "The `duration` must be non-negative")

  def pipeElemType: String = "dropWithin"
  def pipeElemParams: List[Any] = duration :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒
    ctx.registerForRunnerAssignment(this)
    ctx.registerForXStart(this)
    running(in, out)
  }

  def running(in: Inport, out: Outport) = {

    def awaitingXStart() = state(
      xStart = () => dropping(runner.scheduleTimeout(this, duration)))

    /**
     * Dropping all elements while the timer hasn't fired yet.
     */
    def dropping(timer: Cancellable): State = state(
      request = requestF(in),

      cancel = _ => {
        timer.cancel()
        stopCancel(in)
      },

      onNext = (_, _) ⇒ {
        in.request(1)
        stay()
      },

      onComplete = _ => {
        timer.cancel()
        stopComplete(out)
      },

      onError = (e, _) => {
        timer.cancel()
        stopError(e, out)
      },

      xEvent = { case StreamRunner.Timeout => draining() })

    /**
     * Simply forwarding elements from upstream to downstream.
     */
    def draining() = state(
      intercept = false,

      request = requestF(in),
      cancel = stopCancelF(in),
      onNext = onNextF(out),
      onComplete = stopCompleteF(out),
      onError = stopErrorF(out),

      xEvent = { case StreamRunner.Timeout => stay() })

    awaitingXStart()
  }
}
