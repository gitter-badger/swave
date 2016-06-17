/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.source

import scala.annotation.tailrec
import swave.core.macros.StageImpl
import swave.core.PipeElem
import swave.core.impl.Outport

// format: OFF
@StageImpl
private[core] final class RepeatStage(element: AnyRef) extends SourceStage with PipeElem.Source.Repeat {

  def pipeElemType: String = "Stream.repeat"
  def pipeElemParams: List[Any] = element :: Nil

  connectOutAndSealWith { (ctx, out) ⇒ running(out) }

  def running(out: Outport): State = state(
    request = (n, _) ⇒ {
      @tailrec def rec(nn: Int): State =
        if (nn > 0) {
          out.onNext(element)
          rec(nn - 1)
        } else stay()
      rec(n)
    },

    cancel = stopF)
}
