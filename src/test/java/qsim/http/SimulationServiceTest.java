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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
}
