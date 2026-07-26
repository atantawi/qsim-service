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
package qsim.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import qsim.http.Config;
import qsim.http.Json;
import qsim.http.SimulationService;
import qsim.model.MeasureResult;
import qsim.model.SimulationRequest;
import qsim.model.SimulationResponse;

class FixtureIntegrationTest {

  private final SimulationService service = new SimulationService(Config.defaults());

  private SimulationRequest load(String path) throws Exception {
    try (var in = getClass().getResourceAsStream(path)) {
      return Json.MAPPER.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8),
          SimulationRequest.class);
    }
  }

  @Test
  void mm1FixtureBracketsUtilization() throws Exception {
    SimulationResponse r = service.simulate(load("/fixtures/mm1.json"));
    MeasureResult u = r.measures().stream()
        .filter(m -> m.type().equals("utilization") && m.station().equals("q"))
        .findFirst().orElseThrow();
    assertTrue(u.lower() <= 0.5 && 0.5 <= u.upper());
  }

  @Test
  void qopt3StationRunsAndMeasuresEveryStation() throws Exception {
    SimulationResponse r = service.simulate(load("/fixtures/qopt-3station.json"));
    assertEquals("qopt-3station", r.modelName());
    // utilization present for both single-server queues
    assertTrue(r.measures().stream().anyMatch(m -> m.station().equals("mm1") && m.type().equals("utilization")));
    assertTrue(r.measures().stream().anyMatch(m -> m.station().equals("md1") && m.type().equals("utilization")));
    // fork-join measures collapse to the domain node name "fj" (never internal fj__ names)
    assertTrue(r.measures().stream().noneMatch(m -> m.station().contains("__")));
    // known utilizations: rho_mm1 = 1/3, rho_md1 = 1*0.25 = 0.25
    MeasureResult umm1 = r.measures().stream()
        .filter(m -> m.station().equals("mm1") && m.type().equals("utilization")).findFirst().orElseThrow();
    assertTrue(umm1.lower() <= 1.0 / 3 && 1.0 / 3 <= umm1.upper(),
        "mm1 U CI must bracket 1/3");
  }
}
