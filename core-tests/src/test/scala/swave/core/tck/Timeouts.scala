/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.tck

import scala.concurrent.duration._

object Timeouts {
  def publisherShutdownTimeout = 3000.millis

  def defaultTimeout = 800.millis
}
