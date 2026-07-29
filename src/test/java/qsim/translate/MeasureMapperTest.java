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

  /** Open fork-join: src -> fj(2 branches) -> sink. */
  private NetworkModel forkModel() {
    return new NetworkModel("m",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
                new ForkJoinNode("fj", "fork-join",
                    List.of(new Branch(Map.of("web", new ServiceSpec(exp(5.0)))),
                            new Branch(Map.of("web", new ServiceSpec(exp(10.0))))),
                    "all"),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "fj", null), new RoutingEdge("fj", "snk", null))));
  }

  /**
   * A station "Response Time" at a fork-join's Join station is only the per-sibling
   * synchronization wait, not the fork->join sojourn; JMT has a dedicated measure type for the
   * latter. Anchored on the fork-join's own name (the JSIMG Fork station), which is where the
   * engine keeps the fork-join job list.
   */
  @Test
  void responseTimeOnForkJoinUsesForkJoinMeasureType() {
    List<MeasureSpec> specs = mapper.map(forkModel(), List.of("response-time"));
    assertEquals(1, specs.size());
    MeasureSpec s = specs.get(0);
    assertEquals("Fork Join Response Time", s.jmtType());
    assertEquals("fj", s.referenceNode());
    assertEquals("web", s.referenceUserClass());
    assertEquals("station", s.nodeType());
  }

  /** Every class served by the fork-join gets the fork-join type, not just the first. */
  @Test
  void responseTimeOnMultiClassForkJoinUsesForkJoinMeasureTypeForEveryClass() {
    NetworkModel m = new NetworkModel("m",
        List.of(new JobClass("web", "open", null, null), new JobClass("api", "open", null, null)),
        List.of(new SourceNode("src", "source",
                    Map.of("web", new ArrivalSpec(exp(1.0)), "api", new ArrivalSpec(exp(1.0)))),
                new ForkJoinNode("fj", "fork-join",
                    List.of(new Branch(Map.of("web", new ServiceSpec(exp(5.0)),
                                              "api", new ServiceSpec(exp(5.0)))),
                            new Branch(Map.of("web", new ServiceSpec(exp(10.0)),
                                              "api", new ServiceSpec(exp(10.0))))),
                    "all"),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "fj", null), new RoutingEdge("fj", "snk", null)),
               "api", List.of(new RoutingEdge("src", "fj", null), new RoutingEdge("fj", "snk", null))));
    List<MeasureSpec> specs = mapper.map(m, List.of("response-time"));
    assertEquals(2, specs.size());
    assertTrue(specs.stream().allMatch(s -> s.jmtType().equals("Fork Join Response Time")),
        "every class must get the fork-join measure type, got: " + specs);
    assertTrue(specs.stream().allMatch(s -> s.referenceNode().equals("fj")));
  }

  @Test
  void responseTimeOnPlainStationKeepsStationMeasureType() {
    List<MeasureSpec> specs = mapper.map(model(), List.of("response-time"));
    assertEquals(1, specs.size());
    assertEquals("Response Time", specs.get(0).jmtType());
    assertEquals("q", specs.get(0).referenceNode());
  }

  @Test
  void unknownMeasureRejected() {
    ValidationException ex = assertThrows(ValidationException.class,
        () -> mapper.map(model(), List.of("teleportation-latency")));
    assertEquals(ValidationException.Kind.BAD_REQUEST, ex.kind());
  }
}
