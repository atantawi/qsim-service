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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
      // XXE hardening: disable DOCTYPEs and external entities
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setFeature("http://xml.org/sax/features/external-general-entities", false);
      f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      f.setXIncludeAware(false);
      f.setExpandEntityReferences(false);
      Document doc = f.newDocumentBuilder().parse(output);
      NodeList measures = doc.getElementsByTagName("measure");
      List<MeasureResult> results = new ArrayList<>();
      boolean completed = true;
      for (int i = 0; i < measures.getLength(); i++) {
        Element m = (Element) measures.item(i);
        boolean success = Boolean.parseBoolean(m.getAttribute("successful"));
        completed &= success;
        // JMT's terminal-simulation output has been observed to report lowerLimit/upperLimit
        // swapped relative to their names (see Task 11 investigation); normalize here so a
        // confidence interval we hand to clients always satisfies lower <= upper.
        Double lower = parseD(m.getAttribute("lowerLimit"));
        Double upper = parseD(m.getAttribute("upperLimit"));
        if (lower != null && upper != null && lower > upper) {
          Double swap = lower;
          lower = upper;
          upper = swap;
        }
        results.add(new MeasureResult(
            domainStation(m.getAttribute("station")),
            m.getAttribute("class"),
            REVERSE.getOrDefault(m.getAttribute("measureType"), m.getAttribute("measureType")),
            parseD(m.getAttribute("meanValue")),
            lower,
            upper,
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
    // Strip only TRUE suffixes (not substrings): __join at end, or __b followed by digits at end
    if (station.endsWith("__join")) {
      return station.substring(0, station.length() - "__join".length());
    }
    Matcher mb = BRANCH_SUFFIX_PATTERN.matcher(station);
    if (mb.find()) {
      return station.substring(0, mb.start());
    }
    return station;
  }

  private static final Pattern BRANCH_SUFFIX_PATTERN = Pattern.compile("__b\\d+$");

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
