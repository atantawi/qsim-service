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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import jmt.engine.simDispatcher.DispatcherJSIMschema;

/**
 * The ONLY class in the service that imports {@code jmt.*} — the licensing/quarantine boundary.
 *
 * <p>Each {@link #run} call constructs a fresh {@link DispatcherJSIMschema}, giving each
 * simulation clean engine state (spec §4 residual note); a subprocess fallback is deferred unless
 * a state leak between runs is later observed.
 */
public class JmtRunner {

  public RunResult run(String jsimgXml, long seed, Integer maxWallClockSeconds, boolean terminal) {
    File model = null;
    try {
      model = File.createTempFile("qsim-model-", ".xml");
      Files.writeString(model.toPath(), jsimgXml, StandardCharsets.UTF_8);

      DispatcherJSIMschema dispatcher = new DispatcherJSIMschema(model);
      dispatcher.setSimulationSeed(seed);
      dispatcher.setTerminalSimulation(terminal);
      if (maxWallClockSeconds != null && maxWallClockSeconds > 0) {
        dispatcher.setSimulationMaxDuration((long) maxWallClockSeconds * 1000L);
      }

      long start = System.nanoTime();
      boolean ok = dispatcher.solveModel();
      double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;
      if (!ok) {
        throw new EngineException("JMT solveModel() returned false for model " + model.getName(), null);
      }
      File output = dispatcher.getOutputFile();
      if (output == null || !output.exists()) {
        throw new EngineException("JMT produced no output file", null);
      }
      // The model temp file is no longer needed; the output file is returned.
      model.delete();
      return new RunResult(output, elapsed);
    } catch (EngineException e) {
      if (model != null) {
        model.delete();
      }
      throw e;
    } catch (Exception e) {
      if (model != null) {
        model.delete();
      }
      throw new EngineException("JMT engine failed: " + e.getMessage(), e);
    }
  }

  public void cleanup(RunResult result) {
    if (result != null && result.outputFile() != null) {
      result.outputFile().delete();
    }
  }
}
