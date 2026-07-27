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
package qsim.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import qsim.contract.ValidationException;
import qsim.model.*;
import org.junit.jupiter.api.Test;

class MeasureMapperTest {

  private final MeasureMapper mapper = new MeasureMapper();
  private Distribution exp(double r) { return new Distribution("exponential", r, null, null, null); }

  private NetworkModel model() {
    return new NetworkModel("m",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
                new QueueNode("q", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(2.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));
  }

  @Test
  void mapsStationMeasureOverServedClasses() {
    List<MeasureSpec> specs = mapper.map(model(), List.of("utilization"));
    assertEquals(1, specs.size());
    MeasureSpec s = specs.get(0);
    assertEquals("Utilization", s.jmtType());
    assertEquals("q", s.referenceNode());
    assertEquals("web", s.referenceUserClass());
    assertEquals("station", s.nodeType());
  }

  @Test
  void mapsSystemResponseTimePerClass() {
    List<MeasureSpec> specs = mapper.map(model(), List.of("system-response-time"));
    assertEquals(1, specs.size());
    assertEquals("System Response Time", specs.get(0).jmtType());
    assertEquals("", specs.get(0).referenceNode());
    assertEquals("web", specs.get(0).referenceUserClass());
  }

  @Test
  void defaultSetWhenNull() {
    List<MeasureSpec> specs = mapper.map(model(), null);
    // 4 default types x 1 served station x 1 class = 4
    assertEquals(4, specs.size());
    assertTrue(specs.stream().anyMatch(s -> s.jmtType().equals("Number of Customers")));
  }

  @Test
  void unknownMeasureRejected() {
    ValidationException ex = assertThrows(ValidationException.class,
        () -> mapper.map(model(), List.of("teleportation-latency")));
    assertEquals(ValidationException.Kind.BAD_REQUEST, ex.kind());
  }
}
