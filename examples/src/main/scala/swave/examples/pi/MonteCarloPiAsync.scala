/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.examples.pi

import scala.util.{Failure, Success}
import swave.core._
import swave.core.util.XorShiftRandom

object MonteCarloPiAsync extends App {

  implicit val env = StreamEnv()
  import env.defaultDispatcher

  val random = XorShiftRandom()

  Stream.continually(random.nextDouble())
    .grouped(2)
    .map { case x +: y +: Nil ⇒ Point(x, y) }
    .fanOutBroadcast()
      .sub.filter(_.isInner).map(_ => InnerSample).end
      .sub.filterNot(_.isInner).map(_ => OuterSample).end
    .fanInMerge()
    .async()
    .scan(State(0, 0)) { _ withNextSample _ }
    .drop(1)
    .inject()
    .map(_ drop 999999 take 1)
    .flattenConcat()
    .map(state ⇒ f"After ${state.totalSamples}%,10d samples π is approximated as ${state.π}%.6f")
    .take(30)
    .foreach(println)
    .onComplete {
      case Success(_) =>
        val time = System.currentTimeMillis() - env.startTime
        println(f"Done. Total time: $time%,6d ms, Throughput: ${30000.0/time}%.3fM samples/sec\n")
        env.shutdown()

      case Failure(e) => println(e)
    }

  println("Main thread exit.")

  //////////////// MODEL ///////////////

  case class Point(x: Double, y: Double) {
    def isInner: Boolean = x * x + y * y < 1.0
  }

  sealed trait Sample
  case object InnerSample extends Sample
  case object OuterSample extends Sample

  case class State(totalSamples: Long, inCircle: Long) {
    def π: Double = (inCircle.toDouble / totalSamples) * 4.0
    def withNextSample(sample: Sample) =
      State(totalSamples + 1, if (sample == InnerSample) inCircle + 1 else inCircle)
  }
}
