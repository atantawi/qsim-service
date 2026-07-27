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
package qsim.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import qsim.model.*;
import org.junit.jupiter.api.Test;

class ContractValidatorTest {

  private final ContractValidator validator = new ContractValidator();

  private SimulationRequest req(NetworkModel model) {
    return new SimulationRequest(model, 1L, null, null);
  }

  private Distribution exp(double r) { return new Distribution("exponential", r, null, null, null); }

  @Test
  void acceptsAValidOpenModel() {
    NetworkModel m = new NetworkModel("ok",
        List.of(new JobClass("web", "open", null, null)),
        List.of(
            new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
            new QueueNode("q", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(2.0)))),
            new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));
    assertDoesNotThrow(() -> validator.validate(req(m)));
  }

  @Test
  void rejectsOpenClassNotAnchoredToExactlyOneSource() {
    NetworkModel m = new NetworkModel("bad",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SinkNode("snk", "sink")),
        Map.of("web", List.of()));
    ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(req(m)));
    assertEquals(ValidationException.Kind.UNPROCESSABLE, ex.kind());
    assertTrue(ex.details().stream().anyMatch(s -> s.contains("web") && s.contains("source")));
  }

  @Test
  void rejectsClosedClassWithoutPopulation() {
    NetworkModel m = new NetworkModel("bad",
        List.of(new JobClass("batch", "closed", null, "think")),
        List.of(new DelayNode("think", "delay", Map.of("batch", new ServiceSpec(exp(0.2))))),
        Map.of("batch", List.of(new RoutingEdge("think", "think", 1.0))));
    ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(req(m)));
    assertTrue(ex.details().stream().anyMatch(s -> s.contains("population")));
  }

  @Test
  void rejectsDanglingRoutingTarget() {
    NetworkModel m = new NetworkModel("bad",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "ghost", null))));
    ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(req(m)));
    assertTrue(ex.details().stream().anyMatch(s -> s.contains("ghost")));
  }

  @Test
  void rejectsProbabilitiesNotSummingToOne() {
    NetworkModel m = new NetworkModel("bad",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
                new QueueNode("a", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(2.0)))),
                new QueueNode("b", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(2.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(
            new RoutingEdge("src", "a", 0.5), new RoutingEdge("src", "b", 0.3),
            new RoutingEdge("a", "snk", 1.0), new RoutingEdge("b", "snk", 1.0))));
    ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(req(m)));
    assertTrue(ex.details().stream().anyMatch(s -> s.contains("probab")));
  }

  @Test
  void rejectsQueueWithZeroServersOrNegativeCapacity() {
    NetworkModel m = new NetworkModel("bad",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
                new QueueNode("q", "queue", 0, "fcfs", -5, Map.of("web", new ServiceSpec(exp(2.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));
    ValidationException ex = assertThrows(ValidationException.class, () -> validator.validate(req(m)));
    assertTrue(ex.details().stream().anyMatch(s -> s.contains("servers")));
    assertTrue(ex.details().stream().anyMatch(s -> s.contains("capacity")));
  }
}
