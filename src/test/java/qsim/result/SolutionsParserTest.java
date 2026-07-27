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
    assertEquals(0.05, u.alpha());                // 1 - 0.95 (confidence -> significance)

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

  @Test
  void completedFalseWhenAnyMeasureUnsuccessful() throws Exception {
    SolutionsParser.Parsed p = new SolutionsParser().parse(resource("/results/mm1.solutions.xml"));
    assertFalse(p.completed());                   // second measure (fj__join) has successful="false"
  }

  @Test
  void invertedLowerUpperAreNormalizedSoLowerNeverExceedsUpper() throws Exception {
    // Mimics real terminal-simulation JMT output where lowerLimit/upperLimit are swapped
    // relative to their names (see Task 11 investigation).
    SolutionsParser.Parsed p = new SolutionsParser().parse(resource("/results/inverted-bounds.solutions.xml"));
    assertEquals(1, p.measures().size());

    MeasureResult u = p.measures().get(0);
    assertTrue(u.lower() <= u.upper(), "lower must not exceed upper after normalization");
    assertEquals(0.4297696235625058, u.lower()); // the smaller raw value (raw upperLimit attribute)
    assertEquals(0.5250552074712409, u.upper()); // the larger raw value (raw lowerLimit attribute)
  }
}
