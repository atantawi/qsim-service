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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import qsim.model.*;
import qsim.translate.JsimgWriter;
import qsim.translate.MeasureMapper;
import qsim.translate.MeasureSpec;

/**
 * Gates issue #10: {@code stopping.minSamples} was validated, defaulted and stored, but never
 * emitted into the JSIMG document — so the sample floor silently had no effect and callers got a
 * run that stopped far short of what they asked for (10,880 samples against a 1,000,000 floor in
 * the report).
 *
 * <p>Only a real-engine run can prove the fix: the floor is enforced inside JMT's data analyzer,
 * so the evidence is the {@code analyzedSamples} the engine reports back. An M/M/1 at rho=0.8
 * converges on its own in ~20k samples, well under the floor asserted here, so the assertion fails
 * whenever the attribute stops reaching the engine.
 */
class MinSamplesFloorTest {

  private static final int FLOOR = 300_000;

  private Distribution exp(double r) {
    return new Distribution("exponential", r, null, null, null);
  }

  private NetworkModel mm1() {
    return new NetworkModel("mm1",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(0.8)))),
                new QueueNode("q", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(1.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));
  }

  /**
   * A loose precision the CI rule satisfies well before the floor, so the floor is demonstrably what
   * extends the run. A tight precision would make this vacuous: once issue #12 was fixed the
   * precision target binds on its own and drives the run past any modest floor regardless of whether
   * the attribute reached the engine at all.
   */
  private static final double LOOSE_PRECISION = 0.10;

  private Stopping stopping(Integer floor) {
    return new Stopping(0.05, LOOSE_PRECISION, floor, 20_000_000, null, null, 300, false);
  }

  private int analyzedSamples(Stopping stopping) throws Exception {
    JsimgWriter writer = new JsimgWriter();
    List<MeasureSpec> measures = new MeasureMapper().map(mm1(), List.of("response-time"));
    String xml = writer.toXmlString(mm1(), stopping, 42L, measures);
    JmtRunner runner = new JmtRunner();
    RunResult result = runner.run(xml, 42L, 300, true);
    try {
      String out = Files.readString(Path.of(result.outputFile().toURI()));
      Matcher m = Pattern.compile("analyzedSamples=\"(\\d+)\"").matcher(out);
      assertTrue(m.find(), "engine output must report analyzedSamples: " + out);
      return Integer.parseInt(m.group(1));
    } finally {
      runner.cleanup(result);
    }
  }

  @Test
  void engineHonoursTheMinSamplesFloor() throws Exception {
    // Control: with no floor the CI rule stops this model well short of FLOOR. Without this the
    // assertion below could pass on a run the floor never influenced.
    int unfloored = analyzedSamples(stopping(null));
    assertTrue(unfloored < FLOOR,
        "precondition: the CI rule must stop below " + FLOOR + " for the floor to be what extends "
            + "the run, but it already analyzed " + unfloored + " — raise FLOOR or loosen precision");

    int floored = analyzedSamples(stopping(FLOOR));
    assertTrue(floored >= FLOOR,
        "minSamples floor of " + FLOOR + " must reach the engine, but it analyzed only " + floored
            + " (unfloored control: " + unfloored + ")");
  }
}
