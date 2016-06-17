/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages

import org.scalacheck.Gen
import org.scalatest.Inspectors
import swave.core._
import swave.testkit.gen.{ TestSetup, TestOutput, TestFixture, TestError }

import scala.collection.mutable.ListBuffer

final class InjectSpec extends SyncPipeSpec with Inspectors {

  implicit val env = StreamEnv()
  implicit val config = PropertyCheckConfig(minSuccessful = 1000)

  implicit val integerInput = Gen.chooseNum(0, 999)

  "Inject" in check {
    testSetup
      .input[Int]
      .output[Stream[Int]]
      .fixture(fd ⇒ Gen.listOfN(10, fd.output[Int](TestSetup.Default.nonDroppingOutputScripts)))
      .prop
      .from { (in, out, allSubOuts) ⇒
        import TestFixture.State._

        val iter = allSubOuts.iterator
        val subOuts = ListBuffer.empty[TestOutput[Int]]
        out.appendElemHandler { sub ⇒
          if (iter.hasNext) {
            val subOut = iter.next()
            subOuts += subOut
            sub.drainTo(subOut.drain)
          } else sub.drainTo(Drain.ignore)
        }

        in.stream
          .inject()
          .drainTo(out.drain) shouldTerminate likeThis {
            case Cancelled ⇒ // input can be in any state

            case Completed if subOuts.nonEmpty ⇒
              forAll(subOuts.init) { _.terminalState shouldBe Cancelled }
              subOuts.last.terminalState should (be(Cancelled) or be(Completed))

            case Completed ⇒ in.scriptedSize shouldBe 0

            case error @ Error(TestError) ⇒
              if (subOuts.nonEmpty) {
                forAll(subOuts.init) { _.terminalState shouldBe Cancelled }
                subOuts.last.terminalState should (be(Cancelled) or be(error))
              }
              in.terminalState should (be(Cancelled) or be(error))
          }

        subOuts.flatMap(_.received) shouldEqual in.produced.take(subOuts.map(_.size).sum)
      }
  }
}
