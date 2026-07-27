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
package qsim.golden;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qsim.http.Config;
import qsim.http.SimulationService;
import qsim.model.*;

class GoldenAnalyticTest {

  private final SimulationService service = new SimulationService(Config.defaults());

  private Distribution named(String type, double rate) {
    return new Distribution(type, rate, null, null, null);
  }
  private Distribution det(double value) {
    return new Distribution("deterministic", null, value, null, null);
  }
  private Distribution moment(double mean, double scv) {
    return new Distribution(null, null, null, mean, scv);
  }

  /** Single open M/G/1 queue: source -> q -> sink. lambda arrivals, given service dist. */
  private SimulationRequest mg1(double lambda, Distribution service) {
    NetworkModel m = new NetworkModel("mg1",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source",
                    Map.of("web", new ArrivalSpec(named("exponential", lambda)))),
                new QueueNode("q", "queue", 1, "fcfs", null,
                    Map.of("web", new ServiceSpec(service))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));
    Stopping s = new Stopping(0.05, 0.03, 50_000, 2_000_000, null, null, 120, false);
    return new SimulationRequest(m, 4242L, s, List.of("utilization", "throughput", "response-time"));
  }

  private MeasureResult pick(SimulationResponse r, String type) {
    return r.measures().stream()
        .filter(m -> m.type().equals(type) && m.station().equals("q"))
        .findFirst().orElseThrow(() -> new AssertionError("missing measure " + type));
  }

  private void assertBrackets(MeasureResult m, double expected) {
    assertTrue(m.lower() <= expected && expected <= m.upper(),
        m.type() + " CI [" + m.lower() + ", " + m.upper() + "] must bracket " + expected
            + " (mean=" + m.mean() + ", success=" + m.success() + ")");
  }

  private double pkResponseTime(double lambda, double mean, double scv) {
    double rho = lambda * mean;
    return mean + (lambda * mean * mean * (1 + scv)) / (2 * (1 - rho));
  }

  @Test
  void mm1BracketsClosedForms() {
    double lambda = 1.0, mu = 2.0, mean = 1 / mu, rho = lambda * mean;
    SimulationResponse r = service.simulate(mg1(lambda, named("exponential", mu)));
    assertBrackets(pick(r, "utilization"), rho);                 // 0.5
    assertBrackets(pick(r, "throughput"), lambda);               // 1.0
    assertBrackets(pick(r, "response-time"), pkResponseTime(lambda, mean, 1.0)); // 1.0
  }

  @Test
  void md1BracketsClosedForms() {
    double lambda = 1.0, mean = 0.5;
    SimulationResponse r = service.simulate(mg1(lambda, det(mean)));
    assertBrackets(pick(r, "utilization"), lambda * mean);       // 0.5
    assertBrackets(pick(r, "response-time"), pkResponseTime(lambda, mean, 0.0)); // 0.75
  }

  @Test
  void momentGammaBracketsPkFormula() {
    double lambda = 1.0, mean = 0.5, scv = 2.0;
    SimulationResponse r = service.simulate(mg1(lambda, moment(mean, scv)));
    assertBrackets(pick(r, "utilization"), lambda * mean);       // 0.5 (mean check)
    assertBrackets(pick(r, "response-time"), pkResponseTime(lambda, mean, scv)); // 1.25 (mean + SCV)
  }

  @Test
  void identicalSeedIsDeterministicThroughTheService() {
    SimulationResponse a = service.simulate(mg1(1.0, named("exponential", 2.0)));
    SimulationResponse b = service.simulate(mg1(1.0, named("exponential", 2.0)));
    List<MeasureResult> ma = a.measures(), mb = b.measures();
    assertTrue(ma.size() == mb.size());
    for (int i = 0; i < ma.size(); i++) {
      assertTrue(ma.get(i).mean().equals(mb.get(i).mean()),
          "same seed must yield identical means for measure " + ma.get(i).type());
    }
  }

  @Test
  void forkJoinResponseTimeRespectsLowerBound() {
    // 2-branch homogeneous fork-join; T_FJ >= single-branch E[T] (rigorous lower bound).
    double lambda = 0.5, mu = 2.0, mean = 1 / mu;
    NetworkModel m = new NetworkModel("fj",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source",
                    Map.of("web", new ArrivalSpec(named("exponential", lambda)))),
                new ForkJoinNode("fj", "fork-join",
                    List.of(new Branch(Map.of("web", new ServiceSpec(named("exponential", mu)))),
                            new Branch(Map.of("web", new ServiceSpec(named("exponential", mu))))),
                    "all"),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "fj", null), new RoutingEdge("fj", "snk", null))));
    Stopping s = new Stopping(0.05, 0.05, 50_000, 2_000_000, null, null, 120, false);
    SimulationResponse r = service.simulate(new SimulationRequest(m, 4242L, s,
        List.of("system-response-time")));
    MeasureResult sys = r.measures().stream()
        .filter(x -> x.type().equals("system-response-time"))
        .findFirst().orElseThrow();
    double singleBranchET = 1 / (mu - lambda); // 1/(2-0.5) = 0.667
    assertTrue(sys.mean() >= singleBranchET * 0.95,
        "fork-join E[T]=" + sys.mean() + " must be >= slowest-branch E[T]=" + singleBranchET);
  }
}
