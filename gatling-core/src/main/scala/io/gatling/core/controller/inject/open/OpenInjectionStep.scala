/*
 * Copyright 2011-2018 GatlingCorp (http://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatling.core.controller.inject.open

import java.util.Random
import java.util.concurrent.TimeUnit

import scala.collection.AbstractIterator
import scala.concurrent.duration._
import scala.math.abs

import io.gatling.core.util.Shard

sealed trait OpenInjectionStep {
  /**
   * Iterator of time deltas in between any injected user and the beginning of the simulation
   */
  def chain(iterator: Iterator[FiniteDuration]): Iterator[FiniteDuration]

  /**
   * Number of users to inject
   */
  def users: Long
}

abstract class InjectionIterator(durationInSeconds: Int) extends AbstractIterator[FiniteDuration] {

  private var finished = false
  private var thisSecond: Int = -1
  private var thisSecondIterator: Iterator[FiniteDuration] = Iterator.empty

  protected def thisSecondUsers(thisSecond: Int): Int

  private def moveToNextSecond(): Unit =
    while (!finished) {
      thisSecond += 1

      if (thisSecond == durationInSeconds) {
        finished = true
        thisSecondIterator = Iterator.empty
      } else {
        val users = thisSecondUsers(thisSecond)

        if (users > 0) {
          thisSecondIterator = Shard.shards(users, 1000)
            .zipWithIndex
            .flatMap {
              case (millisUsers, millis) =>
                if (millisUsers > 0)
                  Iterator.fill(millisUsers.toInt)((thisSecond * 1000 + millis) milliseconds)
                else
                  Iterator.empty
            }
          return
        }
      }
    }

  override def hasNext(): Boolean =
    if (finished) {
      false
    } else if (thisSecondIterator.hasNext) {
      true
    } else {
      // update thisSecondIterator
      do {
        moveToNextSecond()
      } while (!finished && !thisSecondIterator.hasNext)

      !finished
    }

  override def next(): FiniteDuration = thisSecondIterator.next()

  // init
  hasNext()
}

/**
 * Ramp a given number of users over a given duration
 */
case class RampOpenInjection(users: Long, duration: FiniteDuration) extends OpenInjectionStep {

  require(users >= 0, s"users ($users) must be >= 0")
  require(duration >= Duration.Zero, s"duration ($duration) must be >= 0")

  override def chain(chained: Iterator[FiniteDuration]): Iterator[FiniteDuration] =
    if (users == 0) {
      NothingForOpenInjection(duration).chain(chained)

    } else if (duration == Duration.Zero) {
      AtOnceOpenInjection(users).chain(chained)

    } else {
      val durationInSeconds = duration.toSeconds.toInt

      new InjectionIterator(durationInSeconds) {
        override protected def thisSecondUsers(thisSecond: Int): Int = Shard.shard(users, thisSecond, durationInSeconds).length
      } ++ chained.map(_ + duration)
    }
}

/**
 * Inject users at constant rate : an other expression of a RampInjection
 */
case class ConstantRateOpenInjection(rate: Double, duration: FiniteDuration) extends OpenInjectionStep {

  require(rate >= 0, s"rate ($rate) must be >= 0")
  require(duration >= Duration.Zero, s"duration ($duration) must be >= 0")
  require(!(rate > 0 && duration == Duration.Zero), s"can't inject a non 0 rate ($rate) for a 0 duration")

  override val users: Long = (duration.toSeconds * rate).round

  def randomized = PoissonOpenInjection(duration, rate, rate)

  override def chain(chained: Iterator[FiniteDuration]): Iterator[FiniteDuration] =
    if (rate == 0) {
      NothingForOpenInjection(duration).chain(chained)
    } else {
      RampOpenInjection(users, duration).chain(chained)
    }
}

/**
 * Don't injection any user for a given duration
 */
case class NothingForOpenInjection(duration: FiniteDuration) extends OpenInjectionStep {

  require(duration >= Duration.Zero, s"duration ($duration) must be >= 0")

  override def chain(chained: Iterator[FiniteDuration]): Iterator[FiniteDuration] =
    if (duration == Duration.Zero) {
      chained
    } else {
      chained.map(_ + duration)
    }

  override val users: Long = 0
}

/**
 * Inject all the users at once
 */
case class AtOnceOpenInjection(users: Long) extends OpenInjectionStep {

  require(users >= 0, s"users ($users) must be >= 0")

  override def chain(chained: Iterator[FiniteDuration]): Iterator[FiniteDuration] =
    if (users == 0) {
      chained
    } else {
      new Iterator[FiniteDuration] {

        private var i: Long = 0L

        override def hasNext: Boolean = i < users

        override def next(): FiniteDuration = {
          if (!hasNext) throw new NoSuchElementException
          i += 1
          Duration.Zero
        }
      } ++ chained
    }
}

/**
 * @param startRate Initial injection rate in users/seconds
 * @param endRate Final injection rate in users/seconds
 * @param duration Injection duration
 */
case class RampRateOpenInjection(startRate: Double, endRate: Double, duration: FiniteDuration) extends OpenInjectionStep {

  require(startRate >= 0.0 && endRate >= 0.0, s"injection rates ($startRate, $endRate) must be >= 0")
  require(duration >= Duration.Zero, s"duration ($duration) must be > 0")
  require(!((startRate > 0 || endRate > 0) && duration == Duration.Zero), s"can't inject non 0 rates ($startRate, $endRate) for a 0 duration")

  override val users: Long = ((startRate + (endRate - startRate) / 2) * duration.toSeconds).toLong

  def randomized = PoissonOpenInjection(duration, startRate, endRate)

  override def chain(chained: Iterator[FiniteDuration]): Iterator[FiniteDuration] =
    if (startRate == 0 && endRate == 0) {
      NothingForOpenInjection(duration).chain(chained)

    } else {
      val durationInSeconds = duration.toSeconds.toInt
      val a: Double = (endRate - startRate) / (2 * durationInSeconds)

      new InjectionIterator(durationInSeconds) {

        var pendingFraction: Double = 0d

        override protected def thisSecondUsers(thisSecond: Int): Int = {
          val thisSecondUsersDouble = a * (2 * thisSecond + 1) + startRate + pendingFraction
          val thisSecondUsersIntValue = thisSecondUsersDouble.toInt
          pendingFraction = thisSecondUsersDouble - thisSecondUsersIntValue

          if (thisSecond + 1 == durationInSeconds)
            thisSecondUsersIntValue + pendingFraction.round.toInt
          else
            thisSecondUsersIntValue
        }
      } ++ chained.map(_ + duration)
    }
}

/**
 *  Inject users through separated steps until reaching the closest possible amount of total users.
 *
 *  @param possibleUsers The maximum possible of total users.
 *  @param step The step that will be repeated.
 *  @param separator Will be injected in between the regular injection steps.
 */
case class SplitOpenInjection(possibleUsers: Long, step: OpenInjectionStep, separator: OpenInjectionStep) extends OpenInjectionStep {

  require(possibleUsers >= 0.0, s"possibleUsers ($possibleUsers) must be >= 0")

  private val stepUsers: Long = step.users
  private lazy val separatorUsers: Long = separator.users

  override def chain(chained: Iterator[FiniteDuration]): Iterator[FiniteDuration] = {
    require(stepUsers > 0, s"stepUsers ($stepUsers) must be > 0")
    require(separatorUsers >= 0, s"separatorUsers ($separatorUsers) must be >= 0")

    if (possibleUsers >= stepUsers) {
      val n = ((possibleUsers - stepUsers) / (stepUsers + separatorUsers)).toInt
      val lastScheduling = step.chain(chained)
      (1 to n).foldRight(lastScheduling)((_, iterator) => step.chain(separator.chain(iterator)))
    } else {
      chained
    }
  }

  override def users: Long =
    if (possibleUsers >= stepUsers) {
      possibleUsers - (possibleUsers - stepUsers) % (stepUsers + separatorUsers)
    } else {
      0
    }
}

/**
 * Injection rate following a Heaviside distribution function
 *
 * {{{
 * numberOfInjectedUsers(t) = u(t)
 *                          = ∫δ(t)
 *                          = Heaviside(t)
 *                          = 1/2 + 1/2*erf(k*t)
 *                          // (good numerical approximation)
 * }}}
 */
case class HeavisideOpenInjection(users: Long, duration: FiniteDuration) extends OpenInjectionStep {

  require(users >= 0, s"users ($users) must be >= 0")
  require(duration >= Duration.Zero, s"Duration ($duration) must be >= 0")

  override def chain(chained: Iterator[FiniteDuration]): Iterator[FiniteDuration] =
    if (users == 0) {
      NothingForOpenInjection(duration).chain(chained)

    } else if (duration == Duration.Zero) {
      AtOnceOpenInjection(users).chain(chained)

    } else {
      def heavisideInv(u: Long): Double = {
        val x = u.toDouble / (users + 2)
        Erf.erfinv(2 * x - 1)
      }

      val t0 = abs(heavisideInv(1))
      val d = t0 * 2
      val k = duration.toMillis / d

      new Iterator[FiniteDuration] {

        private var i: Long = 0L

        override def hasNext: Boolean = i < users

        override def next(): FiniteDuration = {
          if (!hasNext) throw new NoSuchElementException
          i += 1
          val t = heavisideInv(i)
          (k * (t + t0)).toLong.milliseconds
        }
      } ++ chained.map(_ + duration)
    }
}

/**
 * Inject users following a Poisson random process, with a ramped injection rate.
 *
 * A Poisson process models users arriving at a page randomly. You can specify the rate
 * that users arrive at, and this rate can ramp-up.
 *
 * Note that since this injector has an element of randomness, the total number of users
 * may vary from run to run, depending on the seed.
 *
 * @param duration the length of time this injector should run for
 * @param startRate initial injection rate for users
 * @param endRate final injection rate for users
 * @param seed a seed for the randomization. If the same seed is re-used, the same timings will be obtained
 */
case class PoissonOpenInjection(duration: FiniteDuration, startRate: Double, endRate: Double, seed: Long = System.nanoTime) extends OpenInjectionStep {

  require(startRate >= 0.0 && endRate >= 0.0, s"injection rates ($startRate, $endRate) must be >= 0")
  require(duration >= Duration.Zero, s"duration ($duration) must be > 0")
  require(!((startRate > 0 || endRate > 0) && duration == Duration.Zero), s"can't inject non 0 rates ($startRate, $endRate) for a 0 duration")

  override val users: Long = chain(Iterator.empty).size

  override def chain(chained: Iterator[FiniteDuration]): Iterator[FiniteDuration] =
    if (startRate == 0 && endRate == 0) {
      NothingForOpenInjection(duration).chain(chained)

    } else {
      val durationSecs = duration.toUnit(TimeUnit.SECONDS)
      val rand = new Random(seed)

      // Uses Lewis and Shedler's thinning algorithm: http://www.dtic.mil/dtic/tr/fulltext/u2/a059904.pdf
      val maxLambda = startRate max endRate
      def shouldKeep(d: Double) = {
        val actualLambda = startRate + (endRate - startRate) * d / durationSecs
        rand.nextDouble() < actualLambda / maxLambda
      }

      val rawIntervals = Iterator.continually {
        val u = rand.nextDouble()
        -math.log(u) / maxLambda
      }

      rawIntervals
        .scanLeft(0.0)(_ + _) // Rolling sum
        .drop(1) // Throw away first value of 0.0. It is not random, but a quirk of using scanLeft
        .takeWhile(_ < durationSecs)
        .filter(shouldKeep)
        .map(_.seconds) ++ chained.map(_ + duration)
    }
}
