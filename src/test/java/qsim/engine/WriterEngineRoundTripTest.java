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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import qsim.model.ArrivalSpec;
import qsim.model.Distribution;
import qsim.model.JobClass;
import qsim.model.NetworkModel;
import qsim.model.QueueNode;
import qsim.model.RoutingEdge;
import qsim.model.ServiceSpec;
import qsim.model.SinkNode;
import qsim.model.SourceNode;
import qsim.model.Stopping;
import qsim.translate.JsimgWriter;
import qsim.translate.MeasureMapper;

/**
 * Gates the Task 8 open-{@code QueueNode} writer bug: the real engine's reflective loader rejects
 * the minimal {@code Queue}/{@code Server} shape Task 7 emitted for open queue nodes (proven by two
 * real-engine runs — {@code LoadException} -&gt; {@code NoSuchMethodException} on
 * {@code jmt.engine.NodeSections.Queue.<init>}). This test builds a minimal open M/M/1 model in
 * code, writes it with {@link JsimgWriter}, and actually runs it through {@link JmtRunner} on the
 * real engine — the only way to confirm the model LOADS, not just that it is XSD-valid.
 */
class WriterEngineRoundTripTest {

  private Distribution exp(double r) {
    return new Distribution("exponential", r, null, null, null);
  }

  private NetworkModel mm1() {
    return new NetworkModel("mm1",
        List.of(new JobClass("web", "open", null, null)),
        List.of(new SourceNode("src", "source", Map.of("web", new ArrivalSpec(exp(1.0)))),
                new QueueNode("q", "queue", 1, "fcfs", null, Map.of("web", new ServiceSpec(exp(2.0)))),
                new SinkNode("snk", "sink")),
        Map.of("web", List.of(new RoutingEdge("src", "q", null), new RoutingEdge("q", "snk", null))));
  }

  private Stopping stopping() {
    return new Stopping(0.05, 0.05, 10000, 100000, null, null, 60, false);
  }

  @Test
  void openQueueNodeModelLoadsAndRunsOnRealEngine() throws Exception {
    NetworkModel model = mm1();
    JsimgWriter writer = new JsimgWriter();
    List<qsim.translate.MeasureSpec> measures = new MeasureMapper().map(model, null);

    Document doc = writer.toDocument(model, stopping(), 42L, measures);
    assertDoesNotThrow(() -> writer.validate(doc), "generated JSIMG must be XSD-valid");
    String xml = writer.toXmlString(model, stopping(), 42L, measures);

    JmtRunner runner = new JmtRunner();
    RunResult result = runner.run(xml, 42L, 60, true);
    try {
      assertTrue(result.outputFile().exists(), "engine must load the model and produce an output file");
      String out = Files.readString(Path.of(result.outputFile().toURI()));
      assertTrue(out.contains("<measure"), "engine output must contain measures (proves the QueueNode loaded)");
    } finally {
      runner.cleanup(result);
    }
  }
}
