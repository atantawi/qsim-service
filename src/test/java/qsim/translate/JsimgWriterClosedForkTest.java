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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import qsim.contract.ValidationException;
import qsim.model.*;

class JsimgWriterClosedForkTest {

  private final JsimgWriter writer = new JsimgWriter();
  private Distribution exp(double r) { return new Distribution("exponential", r, null, null, null); }
  private Stopping stopping() { return new Stopping(0.05, 0.05, 10000, 1_000_000, null, null, 120, false); }

  /** Closed machine-repair: think(delay) -> q(queue) -> think. */
  private NetworkModel closedNet() {
    return new NetworkModel("closed",
        List.of(new JobClass("batch", "closed", 15, "think")),
        List.of(new DelayNode("think", "delay", Map.of("batch", new ServiceSpec(exp(0.2)))),
                new QueueNode("q", "queue", 2, "fcfs", null, Map.of("batch", new ServiceSpec(exp(3.0))))),
        Map.of("batch", List.of(new RoutingEdge("think", "q", 1.0), new RoutingEdge("q", "think", 1.0))));
  }

  /** Open fork-join: src -> fj(2 branches) -> sink. */
  private NetworkModel forkNet() {
    return new NetworkModel("fork",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
                new ForkJoinNode("fj", "fork-join",
                    List.of(new Branch(Map.of("web", new ServiceSpec(exp(4.0)))),
                            new Branch(Map.of("web", new ServiceSpec(exp(8.0))))),
                    "all"),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "fj", null), new RoutingEdge("fj", "snk", null))));
  }

  @Test
  void closedUserClassHasCustomersAndReference() throws Exception {
    Document doc = writer.toDocument(closedNet(), stopping(), 7L, List.of());
    XPath xp = XPathFactory.newInstance().newXPath();
    assertEquals("closed", xp.evaluate("/sim/userClass/@type", doc));
    assertEquals("15", xp.evaluate("/sim/userClass/@customers", doc));
    assertEquals("think", xp.evaluate("/sim/userClass/@referenceSource", doc));
  }

  @Test
  void delayNodeHasDelaySection() throws Exception {
    Document doc = writer.toDocument(closedNet(), stopping(), 7L, List.of());
    XPath xp = XPathFactory.newInstance().newXPath();
    assertEquals("1", xp.evaluate("count(/sim/node[@name='think']/section[@className='Delay'])", doc));
    assertDoesNotThrow(() -> writer.validate(doc));
  }

  @Test
  void forkJoinExpandsToForkBranchesAndJoin() throws Exception {
    Document doc = writer.toDocument(forkNet(), stopping(), 7L, List.of());
    XPath xp = XPathFactory.newInstance().newXPath();
    // fork node + 2 branch stations + join node + src + sink = 6 nodes
    assertEquals("6", xp.evaluate("count(/sim/node)", doc));
    assertEquals("1", xp.evaluate("count(/sim/node[@name='fj']/section[@className='Fork'])", doc));
    assertEquals("1", xp.evaluate("count(/sim/node[@name='fj__join']/section[@className='Join'])", doc));
    assertEquals("1", xp.evaluate("count(/sim/node[@name='fj__b0'])", doc));
    assertEquals("1", xp.evaluate("count(/sim/node[@name='fj__b1'])", doc));
    // external edge src->fj stays; internal fork->branch->join added
    assertEquals("1", xp.evaluate("count(/sim/connection[@source='src'][@target='fj'])", doc));
    assertEquals("1", xp.evaluate("count(/sim/connection[@source='fj'][@target='fj__b0'])", doc));
    assertEquals("1", xp.evaluate("count(/sim/connection[@source='fj__b0'][@target='fj__join'])", doc));
    // edge out of fj originates from the join
    assertEquals("1", xp.evaluate("count(/sim/connection[@source='fj__join'][@target='snk'])", doc));
    assertDoesNotThrow(() -> writer.validate(doc));
  }

  @Test
  void measureOnForkJoinRemapsToJoinStation() throws Exception {
    var measure = new MeasureSpec("fj_web_rt", "Response Time", "fj", "web", "station");
    Document doc = writer.toDocument(forkNet(), stopping(), 7L, List.of(measure));
    XPath xp = XPathFactory.newInstance().newXPath();
    assertEquals("fj__join", xp.evaluate("/sim/measure[@name='fj_web_rt']/@referenceNode", doc));
    assertEquals("0", xp.evaluate("count(/sim/measure[@referenceNode='fj'])", doc));
    assertDoesNotThrow(() -> writer.validate(doc));
  }

  @Test
  void forkJoinWithMismatchedBranchClassesIsRejected() {
    NetworkModel model = new NetworkModel("fork",
        List.of(new JobClass("web", "open", null, null), new JobClass("api", "open", null, null)),
        List.of(new SourceNode("src", "source",
                    Map.of("web", new ArrivalSpec(exp(1.0)), "api", new ArrivalSpec(exp(1.0)))),
                new ForkJoinNode("fj", "fork-join",
                    List.of(new Branch(Map.of("web", new ServiceSpec(exp(4.0)), "api", new ServiceSpec(exp(4.0)))),
                            new Branch(Map.of("web", new ServiceSpec(exp(8.0))))),
                    "all"),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "fj", null), new RoutingEdge("fj", "snk", null)),
               "api", List.of(new RoutingEdge("src", "fj", null), new RoutingEdge("fj", "snk", null))));
    ValidationException ex = assertThrows(ValidationException.class,
        () -> writer.toDocument(model, stopping(), 7L, List.of()));
    assertEquals(ValidationException.Kind.UNPROCESSABLE, ex.kind());
    assertTrue(ex.getMessage().contains("fj"));
    assertTrue(ex.getMessage().contains("api"));
  }
}
