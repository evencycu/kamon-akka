/* =========================================================================================
 * Copyright © 2013-2014 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.akka


import java.nio.LongBuffer

import akka.actor._
import akka.routing._
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import kamon.Kamon
import org.scalactic.TimesOnInt._
import Metrics._
import kamon.akka.RouterMetricsTestActor._
import kamon.testkit.MetricInspection
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.duration._

class RouterMetricsSpec extends TestKit(ActorSystem("RouterMetricsSpec")) with WordSpecLike with MetricInspection with Matchers
  with BeforeAndAfterAll with ImplicitSender with Eventually {

  "the Kamon router metrics" should {
    "respect the configured include and exclude filters" in new RouterMetricsFixtures {
      createTestPoolRouter("tracked-pool-router")
      createTestPoolRouter("non-tracked-pool-router")
      createTestPoolRouter("tracked-explicitly-excluded-pool-router")

      routerProcessingTime.valuesForTag("path") should contain("RouterMetricsSpec/user/tracked-pool-router")
      routerProcessingTime.valuesForTag("path") shouldNot contain("RouterMetricsSpec/user/non-tracked-pool-router")
      routerProcessingTime.valuesForTag("path") shouldNot contain("RouterMetricsSpec/user/tracked-explicitly-excluded-pool-router")
    }


    "record the routing-time of the receive function for pool routers" in new RouterMetricsFixtures {
      val listener = TestProbe()
      val router = createTestPoolRouter("measuring-routing-time-in-pool-router", true)

      router.tell(Ping, listener.ref)
      listener.expectMsg(Pong)

      eventually {
        routerRoutingTime.refine(routerTags("RouterMetricsSpec/user/measuring-routing-time-in-pool-router"))
          .distribution(resetState = false).count should be(1L)
      }
    }

    "record the processing-time of the receive function for pool routers" in new RouterMetricsFixtures {
      val timingsListener = TestProbe()
      val router = createTestPoolRouter("measuring-processing-time-in-pool-router", true)

      router.tell(RouterTrackTimings(sleep = Some(1 second)), timingsListener.ref)
      val timings = timingsListener.expectMsgType[RouterTrackedTimings]
      val processingTimeDistribution = routerProcessingTime
        .refine(routerTags("RouterMetricsSpec/user/measuring-processing-time-in-pool-router")).distribution()

      processingTimeDistribution.count should be(1L)
      processingTimeDistribution.buckets.head.frequency should be(1L)
      processingTimeDistribution.buckets.head.value should be(timings.approximateProcessingTime +- 10.millis.toNanos)
    }

    "record the number of errors for pool routers" in new RouterMetricsFixtures {
      val listener = TestProbe()
      val router = createTestPoolRouter("measuring-errors-in-pool-router")

      10.times(router.tell(Fail, listener.ref))

      router.tell(Ping, listener.ref)
      listener.expectMsg(Pong)

      eventually {
        routerErrors
          .refine(routerTags("RouterMetricsSpec/user/measuring-errors-in-pool-router")).value(resetState = false) should be(10L)
      }
    }

    "record the time-in-mailbox for pool routers" in new RouterMetricsFixtures {
      val timingsListener = TestProbe()
      val router = createTestPoolRouter("measuring-time-in-mailbox-in-pool-router", true)

      router.tell(RouterTrackTimings(sleep = Some(1 second)), timingsListener.ref)
      val timings = timingsListener.expectMsgType[RouterTrackedTimings]

      val timeInMailboxDistribution = routerTimeInMailbox
        .refine(routerTags("RouterMetricsSpec/user/measuring-time-in-mailbox-in-pool-router")).distribution()

      timeInMailboxDistribution.count should be(1L)
      timeInMailboxDistribution.buckets.head.frequency should be(1L)
      timeInMailboxDistribution.buckets.head.value should be(timings.approximateTimeInMailbox +- 10.millis.toNanos)
    }

    "record the time-in-mailbox for balancing pool routers" in new RouterMetricsFixtures {
      val timingsListener = TestProbe()
      val router = createTestBalancingPoolRouter("measuring-time-in-mailbox-in-balancing-pool-router", true)

      router.tell(RouterTrackTimings(sleep = Some(1 second)), timingsListener.ref)
      val timings = timingsListener.expectMsgType[RouterTrackedTimings]

      val timeInMailboxDistribution = routerTimeInMailbox
        .refine(routerTags("RouterMetricsSpec/user/measuring-time-in-mailbox-in-balancing-pool-router") ++
          Map("dispatcher" -> "BalancingPool-/measuring-time-in-mailbox-in-balancing-pool-router")
        ).distribution()

      timeInMailboxDistribution.count should be(1L)
      timeInMailboxDistribution.buckets.head.frequency should be(1L)
      timeInMailboxDistribution.buckets.head.value should be(timings.approximateTimeInMailbox +- 10.millis.toNanos)
    }


    "record pending-messages for pool routers" in new RouterMetricsFixtures {
      val timingsListener = TestProbe()
      val router = createTestPoolRouter("measuring-pending-messages-in-pool-router", true)
      def pendingMessagesDistribution = routerPendingMessages
        .refine(routerTags("RouterMetricsSpec/user/measuring-pending-messages-in-pool-router")).distribution()

      10 times { router.tell(RouterTrackTimings(sleep = Some(1 second)), timingsListener.ref)}
      10 times { timingsListener.expectMsgType[RouterTrackedTimings] }

      pendingMessagesDistribution.max should be >= (5L)

      eventually {
        pendingMessagesDistribution.max should be (0L)
      }
    }

    "record pending-messages for balancing pool routers" in new RouterMetricsFixtures {
      val timingsListener = TestProbe()
      val router = createTestBalancingPoolRouter("measuring-pending-messages-in-balancing-pool-router", true)
      def pendingMessagesDistribution = routerPendingMessages
        .refine(routerTags("RouterMetricsSpec/user/measuring-pending-messages-in-balancing-pool-router") ++
          Map("dispatcher" -> "BalancingPool-/measuring-pending-messages-in-balancing-pool-router")).distribution()

      10 times { router.tell(RouterTrackTimings(sleep = Some(1 second)), timingsListener.ref)}
      10 times { timingsListener.expectMsgType[RouterTrackedTimings] }

      pendingMessagesDistribution.max should be >= (5L)

      eventually {
        pendingMessagesDistribution.max should be (0L)
      }
    }

    "record member count for pool routers" in new RouterMetricsFixtures {
      val timingsListener = TestProbe()
      val router = createTestPoolRouter("measuring-members-in-pool-router", true)
      def membersDistribution = routerMembers
        .refine(routerTags("RouterMetricsSpec/user/measuring-members-in-pool-router")).distribution()

      for(routeesLeft <- 4 to 0 by -1) {
        100 times { router.tell(Discard, timingsListener.ref) }
        router.tell(Die, timingsListener.ref)

        eventually {
          membersDistribution.max should be (routeesLeft)
        }
      }
    }

    "record member count for balancing pool routers" in new RouterMetricsFixtures {
      val timingsListener = TestProbe()
      val router = createTestBalancingPoolRouter("measuring-members-in-balancing-pool-router", true)
      def membersDistribution = routerMembers
        .refine(routerTags("RouterMetricsSpec/user/measuring-members-in-balancing-pool-router") ++
          Map("dispatcher" -> "BalancingPool-/measuring-members-in-balancing-pool-router")).distribution()

      for(routeesLeft <- 4 to 0 by -1) {
        100 times { router.tell(Discard, timingsListener.ref) }
        router.tell(Die, timingsListener.ref)

        eventually {
          membersDistribution.max should be (routeesLeft)
        }
      }
    }


    "pick the right dispatcher name when the routees have a custom dispatcher set via deployment configuration" in new RouterMetricsFixtures {
      val testProbe = TestProbe()
      val router = system.actorOf(FromConfig.props(Props[RouterMetricsTestActor]), "picking-the-right-dispatcher-in-pool-router")

      10 times {
        router.tell(Ping, testProbe.ref)
        testProbe.expectMsg(Pong)
      }

      val routerMetrics = routerMembers.partialRefine(Map("path" -> "RouterMetricsSpec/user/picking-the-right-dispatcher-in-pool-router"))
      routerMetrics.map(m => m("dispatcher")) should contain only("custom-dispatcher")
    }


    "clean the pending messages metric when a routee dies in pool routers" in new RouterMetricsFixtures {
      val timingsListener = TestProbe()
      val router = createTestPoolRouter("cleanup-pending-messages-in-pool-router", true)
      def pendingMessagesDistribution = routerPendingMessages
        .refine(routerTags("RouterMetricsSpec/user/cleanup-pending-messages-in-pool-router")).distribution()

      10 times { router.tell(RouterTrackTimings(sleep = Some(1 second)), timingsListener.ref)}
      1 times { router.tell(Die, timingsListener.ref)}
      500 times { router.tell(Discard, timingsListener.ref)}

      pendingMessagesDistribution.max should be >= (500L)
      10 times { timingsListener.expectMsgType[RouterTrackedTimings] }

      eventually {
        pendingMessagesDistribution.max should be (0L)
      }
    }

    "clean the pending messages metric when a routee dies in balancing pool routers" in new RouterMetricsFixtures {
      val timingsListener = TestProbe()
      val router = createTestBalancingPoolRouter("cleanup-pending-messages-in-balancing-pool-router", true)
      def pendingMessagesDistribution = routerPendingMessages
        .refine(routerTags("RouterMetricsSpec/user/cleanup-pending-messages-in-balancing-pool-router") ++
          Map("dispatcher" -> "BalancingPool-/cleanup-pending-messages-in-balancing-pool-router")).distribution()

      10 times { router.tell(RouterTrackTimings(sleep = Some(1 second)), timingsListener.ref)}
      1 times { router.tell(Die, timingsListener.ref)}
      500 times { router.tell(Discard, timingsListener.ref)}

      pendingMessagesDistribution.max should be >= (100L)
      10 times { timingsListener.expectMsgType[RouterTrackedTimings] }

      eventually {
        pendingMessagesDistribution.max should be (0L)
      }
    }

    "clean up the associated recorder when the pool router is stopped" in new RouterMetricsFixtures {
      val trackedRouter = createTestPoolRouter("stop-in-pool-router")
      routerProcessingTime.valuesForTag("path") should contain("RouterMetricsSpec/user/stop-in-pool-router")

      // Killing the router should remove it's RouterMetrics and registering again bellow should create a new one.
      val deathWatcher = TestProbe()
      deathWatcher.watch(trackedRouter)
      trackedRouter ! PoisonPill
      deathWatcher.expectTerminated(trackedRouter)

      routerProcessingTime.valuesForTag("path") shouldNot contain("RouterMetricsSpec/user/stop-in-pool-router")
    }
  }

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(timeout = 5 seconds, interval = 5 milliseconds)

  override protected def afterAll(): Unit = shutdown()

  def routerTags(path: String): Map[String, String] = {
    val routerClass = if(path.contains("balancing")) "akka.routing.BalancingPool" else "akka.routing.RoundRobinPool"

    Map(
      "path" -> path,
      "system" -> "RouterMetricsSpec",
      "dispatcher" -> "akka.actor.default-dispatcher",
      "routeeClass" -> "kamon.akka.RouterMetricsTestActor",
      "routerClass" -> routerClass
    )
  }


  trait RouterMetricsFixtures {
    def createTestGroupRouter(routerName: String, resetState: Boolean = false): ActorRef = {
      val routees = Vector.fill(5) {
        system.actorOf(Props[RouterMetricsTestActor])
      }

      val group = system.actorOf(RoundRobinGroup(routees.map(_.path.toStringWithoutAddress)).props(), routerName)
      val initialiseListener = TestProbe()

      // Ensure that the router has been created before returning.
      group.tell(Ping, initialiseListener.ref)
      initialiseListener.expectMsg(Pong)

      // Cleanup all the metric recording instruments:
      if(resetState) {
        routerRoutingTime.refine(routerTags(s"RouterMetricsSpec/user/$routerName")).distribution(resetState = true)
        routerTimeInMailbox.refine(routerTags(s"RouterMetricsSpec/user/$routerName")).distribution(resetState = true)
        routerProcessingTime.refine(routerTags(s"RouterMetricsSpec/user/$routerName")).distribution(resetState = true)
        routerErrors.refine(routerTags(s"RouterMetricsSpec/user/$routerName")).value(resetState = true)
      }

      group
    }

    def createTestPoolRouter(routerName: String, resetState: Boolean = false): ActorRef = {
      val router = system.actorOf(RoundRobinPool(5).props(Props[RouterMetricsTestActor]), routerName)
      val initialiseListener = TestProbe()

      // Ensure that the router has been created before returning.
      router.tell(Ping, initialiseListener.ref)
      initialiseListener.expectMsg(Pong)

      // Cleanup all the metric recording instruments:
      if(resetState) {
        routerRoutingTime.refine(routerTags(s"RouterMetricsSpec/user/$routerName")).distribution(resetState = true)
        routerTimeInMailbox.refine(routerTags(s"RouterMetricsSpec/user/$routerName")).distribution(resetState = true)
        routerProcessingTime.refine(routerTags(s"RouterMetricsSpec/user/$routerName")).distribution(resetState = true)
        routerErrors.refine(routerTags(s"RouterMetricsSpec/user/$routerName")).value(resetState = true)
        routerPendingMessages.refine(routerTags(s"RouterMetricsSpec/user/$routerName")).distribution(resetState = true)
        routerMembers.refine(routerTags(s"RouterMetricsSpec/user/$routerName")).distribution(resetState = true)
      }

      router
    }

    def createTestBalancingPoolRouter(routerName: String, resetState: Boolean = false): ActorRef = {
      val router = system.actorOf(BalancingPool(5).props(Props[RouterMetricsTestActor]), routerName)
      val initialiseListener = TestProbe()

      // Ensure that the router has been created before returning.
      router.tell(Ping, initialiseListener.ref)
      initialiseListener.expectMsg(Pong)

      // Cleanup all the metric recording instruments:
      if(resetState) {
        val tags = routerTags(s"RouterMetricsSpec/user/$routerName") ++
          Map("dispatcher" -> s"BalancingPool-/$routerName")

        routerRoutingTime.refine(tags).distribution(resetState = true)
        routerTimeInMailbox.refine(tags).distribution(resetState = true)
        routerProcessingTime.refine(tags).distribution(resetState = true)
        routerPendingMessages.refine(tags).distribution(resetState = true)
        routerMembers.refine(tags).distribution(resetState = true)
        routerErrors.refine(tags).value(resetState = true)
      }

      router
    }
  }
}

