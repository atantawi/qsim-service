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
package qsim.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URL;
import org.junit.jupiter.api.Test;
import qsim.model.MeasureResult;

class SolutionsParserTest {

  private File resource(String path) throws Exception {
    URL u = getClass().getResource(path);
    return new File(u.toURI());
  }

  @Test
  void parsesMeasuresAndMapsTypeAndStation() throws Exception {
    SolutionsParser.Parsed p = new SolutionsParser().parse(resource("/results/mm1.solutions.xml"));
    assertEquals(4, p.measures().size());

    MeasureResult u = p.measures().get(0);
    assertEquals("q", u.station());
    assertEquals("web", u.jobClass());
    assertEquals("utilization", u.type());        // reverse-mapped from "Utilization"
    assertEquals(0.5012, u.mean());
    assertEquals(0.4901, u.lower());
    assertEquals(true, u.success());
    assertEquals(45000, u.samplesAnalyzed());
    assertEquals(0.05, u.alpha());                // significance level, passed through verbatim

    MeasureResult rt = p.measures().get(1);
    assertEquals("fj", rt.station());             // fj__join -> fj (join suffix stripped)
    assertEquals("response-time", rt.type());

    MeasureResult branch = p.measures().get(2);
    assertEquals("fj", branch.station());         // fj__b0 -> fj (branch suffix stripped)
    assertEquals("queue-length", branch.type());

    MeasureResult noStrip = p.measures().get(3);
    assertEquals("queue__buffer", noStrip.station()); // queue__buffer NOT stripped (not a true fork-join suffix)
    assertEquals("utilization", noStrip.type());
  }

  /** The fork-join response time is what a caller asked for as "response-time" on that node. */
  @Test
  void forkJoinResponseTimeReversesToResponseTime() throws Exception {
    SolutionsParser.Parsed p = new SolutionsParser().parse(resource("/results/fork-join.solutions.xml"));
    assertEquals(1, p.measures().size());
    MeasureResult rt = p.measures().get(0);
    assertEquals("fj", rt.station());
    assertEquals("response-time", rt.type());
    assertEquals(0.2884507654809945, rt.mean());
  }

  @Test
  void completedFalseWhenAnyMeasureUnsuccessful() throws Exception {
    SolutionsParser.Parsed p = new SolutionsParser().parse(resource("/results/mm1.solutions.xml"));
    assertFalse(p.completed());                   // second measure (fj__join) has successful="false"
  }

  /**
   * Issue #12: limits are reported verbatim; the parser must NOT reorder them.
   *
   * <p>This fixture records the inverted output the service used to produce. Those limits were never
   * a JMT quirk (as the Task 11 investigation concluded) — {@code getLowerLimit()} is
   * {@code mean - confInt}, so they invert exactly when {@code confInt} is negative, which is what
   * writing {@code 1 - alpha} caused. The old silent swap turned that signal into a plausible-looking
   * interval and hid the defect for months. With alpha written correctly the limits order naturally,
   * so any future inversion is a real regression and must stay visible rather than be normalized away.
   */
  @Test
  void invertedLimitsArePassedThroughNotSilentlyReordered() throws Exception {
    SolutionsParser.Parsed p = new SolutionsParser().parse(resource("/results/inverted-bounds.solutions.xml"));
    assertEquals(1, p.measures().size());

    MeasureResult u = p.measures().get(0);
    assertEquals(0.5250552074712409, u.lower()); // raw lowerLimit attribute, not reordered
    assertEquals(0.4297696235625058, u.upper()); // raw upperLimit attribute, not reordered
  }
}
