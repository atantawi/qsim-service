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
    assertEquals(2, p.measures().size());

    MeasureResult u = p.measures().get(0);
    assertEquals("q", u.station());
    assertEquals("web", u.jobClass());
    assertEquals("utilization", u.type());        // reverse-mapped from "Utilization"
    assertEquals(0.5012, u.mean());
    assertEquals(0.4901, u.lower());
    assertEquals(true, u.success());
    assertEquals(45000, u.samplesAnalyzed());
    assertEquals(0.05, u.alpha());                // 1 - 0.99 (confidence -> significance)

    MeasureResult rt = p.measures().get(1);
    assertEquals("fj", rt.station());             // fj__join -> fj
    assertEquals("response-time", rt.type());
  }

  @Test
  void completedFalseWhenAnyMeasureUnsuccessful() throws Exception {
    SolutionsParser.Parsed p = new SolutionsParser().parse(resource("/results/mm1.solutions.xml"));
    assertFalse(p.completed());                   // second measure has successful="false"
  }
}
