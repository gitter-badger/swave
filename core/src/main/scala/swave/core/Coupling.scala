/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import swave.core.impl.stages.inout.CouplingStage

sealed abstract class Coupling[T] {
  def in: Drain[T, Unit]
  def out: Stream[T]
}

object Coupling {

  def apply[T]: Coupling[T] =
    new Coupling[T] {
      private[this] val pipe = new CouplingStage
      val in = new Drain[T, Unit](pipe, ())
      val out = new Stream[T](pipe)
    }
}
