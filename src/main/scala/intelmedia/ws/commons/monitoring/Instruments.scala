package intelmedia.ws.commons.monitoring

import com.twitter.algebird.Group
import intelmedia.ws.commons.monitoring.{Buffers => B}
import scala.concurrent.duration._

/**
 * Provider of counters, guages, and timers, tied to some
 * `Monitoring` server instance.
 */
class Instruments(window: Duration, monitoring: Monitoring) {

  def counter(label: String, init: Int = 0): Counter[Periodic[Int]] = new Counter[Periodic[Int]] {
    val count = B.resetEvery(window)(B.counter(init))
    val previousCount = B.emitEvery(window)(count)
    val slidingCount = B.sliding(window)(identity[Int])(Group.intGroup)
    val (nowK, incrNow) = monitoring.topic(s"now/$label")(count)
    val (prevK, incrPrev) = monitoring.topic(s"previous/$label")(previousCount)
    val (slidingK, incrSliding) = monitoring.topic(s"sliding/$label")(slidingCount)
    def incrementBy(n: Int): Unit = {
      incrNow(n); incrPrev(n); incrSliding(n)
    }
    def keys = Periodic(nowK, prevK, slidingK)

    incrementBy(0)
  }

  def guage[A <% Reportable[A]](label: String, init: A): Guage[Continuous[A],A] = new Guage[Continuous[A],A] {
    val (key, snk) = monitoring.topic(s"$label/now")(B.resetEvery(window)(B.variable(init)))
    def modify(f: A => A): Unit = snk(f)
    def keys = Continuous(key)

    set(init)
  }

  def timer(label: String): Timer[Periodic[Stats]] = new Timer[Periodic[Stats]] {
    val timer = B.resetEvery(window)(B.stats)
    val previousTimer = B.emitEvery(window)(timer)
    val slidingTimer = B.sliding(window)((d: Double) => Stats(d))(Stats.statsGroup)
    val (nowK, nowSnk) = monitoring.topic(s"now/$label")(timer)
    val (prevK, prevSnk) = monitoring.topic(s"previous/$label")(previousTimer)
    val (slidingK, slidingSnk) = monitoring.topic(s"sliding/$label")(slidingTimer)
    def keys = Periodic(nowK, prevK, slidingK)
    def start: () => Unit = {
      val t0 = System.nanoTime
      () => {
        // record time in milliseconds
        val elapsed = (System.nanoTime - t0).toDouble / 1e6
        nowSnk(elapsed); prevSnk(elapsed); slidingSnk(elapsed)
      }
    }
  }
}

object Instruments {
  val fiveMinute: Instruments = instance(5 minutes)
  val oneMinute: Instruments = instance(1 minutes)
  val default = fiveMinute

  def instance(d: Duration, m: Monitoring = Monitoring.default): Instruments =
    new Instruments(d, m)
}