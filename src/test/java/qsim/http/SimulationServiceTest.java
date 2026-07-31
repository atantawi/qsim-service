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
package qsim.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qsim.contract.ValidationException;
import qsim.engine.JmtRunner;
import qsim.engine.RunResult;
import qsim.model.*;

class SimulationServiceTest {

  private Distribution exp(double r) { return new Distribution("exponential", r, null, null, null); }

  /** M/M/1, lambda=1, mu=2 -> rho = U = 0.5. */
  private SimulationRequest mm1() {
    NetworkModel m = new NetworkModel("mm1",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
                new QueueNode("q", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(2.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));
    Stopping s = new Stopping(0.05, 0.03, 10000, 500_000, null, null, 60, false);
    return new SimulationRequest(m, 12345L, s, List.of("utilization", "response-time"));
  }

  @Test
  void runsEndToEndAndEchoesRequestMetadata() {
    SimulationService service = new SimulationService(Config.defaults());
    SimulationResponse resp = service.simulate(mm1());

    assertEquals("mm1", resp.modelName());
    assertEquals("simulation", resp.solutionMethod());
    assertEquals(12345L, resp.seed());
    assertTrue(resp.wallClockSeconds() >= 0.0);
    assertFalse(resp.measures().isEmpty());
    // every measure references a domain node name (never an internal fj__ name)
    assertTrue(resp.measures().stream().allMatch(mr -> !mr.station().contains("__")));
  }

  @Test
  void utilizationBracketsHalf() {
    SimulationService service = new SimulationService(Config.defaults());
    SimulationResponse resp = service.simulate(mm1());
    MeasureResult u = resp.measures().stream()
        .filter(mr -> mr.type().equals("utilization") && mr.station().equals("q"))
        .findFirst().orElseThrow();
    // U = rho = 0.5; the CI must bracket it (sanity gate; full golden checks in Task 13)
    assertTrue(u.lower() <= 0.5 && 0.5 <= u.upper(),
        "U CI [" + u.lower() + "," + u.upper() + "] must bracket 0.5");
  }

  @Test
  void missingSeedIsGeneratedAndEchoed() {
    SimulationService service = new SimulationService(Config.defaults());
    SimulationRequest noSeed = new SimulationRequest(mm1().model(), null, mm1().stopping(),
        List.of("utilization"));
    SimulationResponse resp = service.simulate(noSeed);
    assertTrue(resp.seed() != null, "service must generate and echo a seed when none is supplied");
  }

  // ---- sample-bound guard --------------------------------------------------
  //
  // Now that minSamples actually reaches the engine (issue #10), a floor above the ceiling is a
  // request the engine cannot satisfy: addSample terminates at maxData while the floor only gates
  // the CI stop rule, so the run would end short and report completed:false with no explanation.
  // Rejecting is the point — clamping the floor down to the ceiling would silently deliver less
  // than was asked for, which is the shape of #10 itself.

  /** A stopping rule that varies only the two sample bounds. */
  private Stopping bounds(Integer minSamples, Integer maxSamples) {
    return new Stopping(0.05, 0.03, minSamples, maxSamples, null, null, 60, false);
  }

  private ValidationException rejected(Stopping s) {
    SimulationService service = new SimulationService(Config.defaults());
    return assertThrows(ValidationException.class, () -> service.validateStopping(s));
  }

  @Test
  void rejectsAFloorAboveTheCeiling() {
    ValidationException e = rejected(bounds(500_000, 100_000));
    assertEquals(ValidationException.Kind.BAD_REQUEST, e.kind(), "a bad bound is a 400, not a 422");
    assertTrue(e.getMessage().contains("500000") && e.getMessage().contains("100000"),
        "the message must name both bounds so the caller can see the contradiction: " + e.getMessage());
  }

  @Test
  void rejectsANegativeFloor() {
    assertEquals(ValidationException.Kind.BAD_REQUEST, rejected(bounds(-1, 100_000)).kind());
  }

  /** A floor equal to the ceiling is satisfiable — the engine can reach it exactly. */
  @Test
  void acceptsAFloorEqualToTheCeiling() {
    SimulationService service = new SimulationService(Config.defaults());
    assertDoesNotThrow(() -> service.validateStopping(bounds(100_000, 100_000)));
  }

  /** Zero is the documented way to ask for no floor at all. */
  @Test
  void acceptsAZeroFloor() {
    SimulationService service = new SimulationService(Config.defaults());
    assertDoesNotThrow(() -> service.validateStopping(bounds(0, 100_000)));
  }

  /**
   * The common shape: a caller raises the floor and never mentions a ceiling, so the contradiction
   * is against the *default* maxSamples. The check therefore has to run on the effective stopping
   * rule rather than the raw request, and the message has to say where the ceiling came from.
   */
  @Test
  void rejectsAFloorThatOnlyContradictsTheDefaultedCeiling() {
    SimulationService service = new SimulationService(Config.defaults());
    Stopping requested = bounds(Config.defaults().defaultMaxSamples() + 1, null);
    ValidationException e = assertThrows(ValidationException.class,
        () -> service.validateStopping(service.effectiveStopping(requested)));
    assertTrue(e.getMessage().contains("QSIM_DEFAULT_MAX_SAMPLES"),
        "the caller never sent a ceiling, so the message must attribute it to the default: "
            + e.getMessage());
  }

  /** The guard must fire before the engine is started, not after a wasted run. */
  @Test
  void rejectsBeforeInvokingTheEngine() {
    SimulationService service = new SimulationService(Config.defaults(), new JmtRunner() {
      @Override
      public RunResult run(String xml, long seed, Integer maxWallClockSeconds, boolean terminal) {
        throw new AssertionError("bad sample bounds must be rejected before the engine is invoked");
      }
    });
    SimulationRequest req = new SimulationRequest(mm1().model(), 42L, bounds(500_000, 100_000),
        List.of("utilization"));
    assertEquals(ValidationException.Kind.BAD_REQUEST,
        assertThrows(ValidationException.class, () -> service.simulate(req)).kind());
  }
}
