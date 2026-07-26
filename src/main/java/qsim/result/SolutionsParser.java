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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import qsim.model.MeasureResult;

public class SolutionsParser {

  public record Parsed(List<MeasureResult> measures, boolean completed) {}

  // Inverse of MeasureMapper's registry (JMT measureType string -> domain type).
  private static final Map<String, String> REVERSE = Map.ofEntries(
      Map.entry("Response Time", "response-time"),
      Map.entry("Residence Time", "residence-time"),
      Map.entry("Queue Time", "queue-time"),
      Map.entry("Number of Customers", "queue-length"),
      Map.entry("Utilization", "utilization"),
      Map.entry("Throughput", "throughput"),
      Map.entry("Drop Rate", "drop-rate"),
      Map.entry("System Response Time", "system-response-time"));

  public Parsed parse(File output) {
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      Document doc = f.newDocumentBuilder().parse(output);
      NodeList measures = doc.getElementsByTagName("measure");
      List<MeasureResult> results = new ArrayList<>();
      boolean completed = true;
      for (int i = 0; i < measures.getLength(); i++) {
        Element m = (Element) measures.item(i);
        boolean success = Boolean.parseBoolean(m.getAttribute("successful"));
        completed &= success;
        results.add(new MeasureResult(
            domainStation(m.getAttribute("station")),
            m.getAttribute("class"),
            REVERSE.getOrDefault(m.getAttribute("measureType"), m.getAttribute("measureType")),
            parseD(m.getAttribute("meanValue")),
            parseD(m.getAttribute("lowerLimit")),
            parseD(m.getAttribute("upperLimit")),
            significance(m.getAttribute("alfa")),
            parseD(m.getAttribute("precision")),
            success,
            parseI(m.getAttribute("analyzedSamples")),
            parseI(m.getAttribute("discardedSamples")),
            parseD(m.getAttribute("variance")),
            parseD(m.getAttribute("standardDeviation"))));
      }
      return new Parsed(results, completed);
    } catch (Exception e) {
      throw new IllegalStateException("cannot parse JMT solutions file " + output, e);
    }
  }

  /** Map an expanded fork-join station name back to its domain node name. */
  static String domainStation(String station) {
    if (station == null) return null;
    int join = station.indexOf("__join");
    if (join >= 0) return station.substring(0, join);
    int branch = station.indexOf("__b");
    if (branch >= 0) return station.substring(0, branch);
    return station;
  }

  private static Double significance(String alfa) {
    Double conf = parseD(alfa);
    if (conf == null) return null;
    // round 1 - confidence to 6 dp to shed float noise (0.99 -> 0.01, 0.95 -> 0.05)
    return Math.round((1.0 - conf) * 1_000_000.0) / 1_000_000.0;
  }

  private static Double parseD(String s) {
    if (s == null || s.isBlank()) return null;
    try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
  }

  private static Integer parseI(String s) {
    if (s == null || s.isBlank()) return null;
    try { return (int) Math.round(Double.parseDouble(s)); } catch (NumberFormatException e) { return null; }
  }
}
