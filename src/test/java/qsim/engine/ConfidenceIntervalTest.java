/*
 * qsim-service — a JMT-backed queueing-network simulation service.
 * Copyright (C) 2026 qsim-service contributors.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */
package qsim.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qsim.http.Config;
import qsim.http.SimulationService;
import qsim.model.*;

/**
 * Gates issue #12: the service wrote {@code 1 - alpha} into the {@code <measure alpha>} attribute,
 * but JMT's {@code TStudent.ICDF} expects the significance level. Handed a confidence level it
 * returns a negative, wrong-tail quantile, so {@code confInt} came out negative and
 * {@code NewDynamicDataAnalyzer.HWtest} — which compares {@code precision > confInt / extMean} with
 * no absolute value — passed unconditionally. Every interval was too narrow, its endpoints were
 * inverted, and {@code success} was set true at any sample count.
 *
 * <p>The assertions here are semantic rather than structural, so they hold regardless of how the
 * attribute is spelled: a run that reports success must actually have met its precision target, and
 * a confidence interval must contain its own mean.
 *
 * <p>Both tests guard against passing vacuously, because the regression they gate would otherwise
 * arrange exactly that. A negative half-width satisfies {@code achieved <= precision} for free, and
 * a run that reports no successful measure gives the first test's loop nothing to check — so the
 * sign is asserted before it is used as a ratio, and each test asserts it examined something.
 */
class ConfidenceIntervalTest {

  private final SimulationService service = new SimulationService(Config.fromEnv());

  private Distribution exp(double r) {
    return new Distribution("exponential", r, null, null, null);
  }

  /** M/M/1 at lambda=0.8, mu=1.0 — true E[T] = 1/(mu-lambda) = 5.0. */
  private SimulationRequest mm1(double precision) {
    NetworkModel m = new NetworkModel("mm1",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(0.8)))),
                new QueueNode("q", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(1.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));
    // A generous ceiling, and the floor left to its default: SimulationService fills minSamples in
    // from QSIM_DEFAULT_MIN_SAMPLES (10,000), which is orders of magnitude below what this precision
    // target needs, so the CI rule is what decides when to stop — exactly what the negative
    // half-width used to short-circuit.
    Stopping s = new Stopping(0.05, precision, null, 20_000_000, null, null, 300, false);
    return new SimulationRequest(m, 42L, s, List.of("response-time"));
  }

  @Test
  void successMeansThePrecisionTargetWasActuallyMet() {
    double precision = 0.02;
    SimulationResponse r = service.simulate(mm1(precision));
    int checked = 0;
    for (MeasureResult m : r.measures()) {
      if (!m.success()) continue;
      double halfWidth = (m.upper() - m.lower()) / 2.0;
      // Signed, and checked before it becomes a ratio. Under issue #12 confInt was negative, so
      // lower = mean - confInt was the LARGER endpoint and this half-width came out negative —
      // which satisfies `achieved <= precision` at any sample count. Without this assertion the
      // one below would pass on precisely the regression this test exists to catch.
      assertTrue(halfWidth > 0,
          "measure " + m.type() + " has a non-positive CI half-width " + halfWidth
              + " (CI=[" + m.lower() + ", " + m.upper() + "]) — an inverted interval means the "
              + "t-quantile came from the wrong tail, which also disables the stopping rule");
      double achieved = halfWidth / m.mean();
      assertTrue(achieved <= precision,
          "measure " + m.type() + " reports success=true but its achieved relative half-width is "
              + achieved + ", missing the requested precision of " + precision
              + " (mean=" + m.mean() + ", CI=[" + m.lower() + ", " + m.upper() + "], "
              + "samples=" + m.samplesAnalyzed() + ")");
      checked++;
    }
    // The loop body is the entire test, so an empty one verifies nothing. This model at precision
    // 0.02 converges in ~1.4M samples, well inside both the 20M ceiling and the 300s wall clock, so
    // a measure failing to report success is a real result worth failing on rather than skipping.
    assertTrue(checked > 0,
        "precondition: at least one measure must report success=true for this test to check "
            + "anything, but none of " + r.measures().size() + " did (completed=" + r.completed()
            + ", wallClock=" + r.wallClockSeconds() + "s)");
  }

  @Test
  void confidenceIntervalIsOrderedAndBracketsItsMean() {
    SimulationResponse r = service.simulate(mm1(0.02));
    assertTrue(!r.measures().isEmpty(), "expected at least one measure");
    for (MeasureResult m : r.measures()) {
      assertTrue(m.lower() <= m.upper(),
          "CI must not be inverted: [" + m.lower() + ", " + m.upper() + "] for " + m.type()
              + " — a negative half-width means the t-quantile came from the wrong tail");
      assertTrue(m.lower() <= m.mean() && m.mean() <= m.upper(),
          "CI [" + m.lower() + ", " + m.upper() + "] must bracket its own mean " + m.mean());
    }
  }
}
