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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.transform.dom.DOMSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import qsim.contract.ValidationException;
import qsim.distribution.CanonicalDistribution;
import qsim.distribution.DistParam;
import qsim.distribution.DistributionResolver;
import qsim.model.*;

public class JsimgWriter {

  private final DistributionResolver resolver = new DistributionResolver();

  public String toXmlString(NetworkModel model, Stopping stopping, long seed, List<MeasureSpec> measures) {
    return Xml.serialize(toDocument(model, stopping, seed, measures));
  }

  public Document toDocument(NetworkModel model, Stopping stopping, long seed, List<MeasureSpec> measures) {
    Document doc = Xml.newDocument();
    Element sim = Xml.child(doc, "sim",
        "name", model.name(),
        "seed", Long.toString(seed),
        "maxSamples", stopping == null || stopping.maxSamples() == null ? "1000000" : stopping.maxSamples().toString(),
        "maxEvents", stopping == null || stopping.maxEvents() == null ? "-1" : stopping.maxEvents().toString(),
        "maxSimulated", stopping == null || stopping.maxSimulatedTime() == null ? "-1.0" : stopping.maxSimulatedTime().toString(),
        "disableStatisticStop", stopping != null && Boolean.TRUE.equals(stopping.disableStatisticStop()) ? "true" : "false",
        "polling", "1.0");
    // Declare the xsi namespace binding the engine requires (Task 2). Set as a real
    // namespace declaration so it is exempt from XSD validation.
    sim.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:xsi",
        "http://www.w3.org/2001/XMLSchema-instance");

    writeUserClasses(sim, model);
    for (Node n : model.nodes()) {
      writeNode(sim, model, n);
    }
    if (measures != null) {
      for (MeasureSpec m : measures) {
        writeMeasure(sim, m, stopping);
      }
    }
    writeConnections(sim, model);
    return doc;
  }

  // ---- user classes --------------------------------------------------------

  private void writeUserClasses(Element sim, NetworkModel model) {
    for (JobClass c : model.classes()) {
      if ("open".equals(c.type())) {
        Xml.child(sim, "userClass",
            "name", c.name(), "type", "open", "priority", "0",
            "referenceSource", sourceOf(model, c.name()));
      } else {
        // closed: filled in Task 8 (customers + reference station)
        writeClosedUserClass(sim, model, c);
      }
    }
  }

  /** Name of the single source whose arrivals include this open class. */
  private String sourceOf(NetworkModel model, String className) {
    return model.nodes().stream()
        .filter(n -> n instanceof SourceNode)
        .map(n -> (SourceNode) n)
        .filter(s -> s.arrivals() != null && s.arrivals().containsKey(className))
        .map(SourceNode::name)
        .findFirst()
        .orElseThrow(() -> new ValidationException(ValidationException.Kind.UNPROCESSABLE,
            List.of("open class '" + className + "' has no source")));
  }

  /** Overridden behavior lives in Task 8; open-only build never reaches a closed class. */
  protected void writeClosedUserClass(Element sim, NetworkModel model, JobClass c) {
    throw new ValidationException(ValidationException.Kind.UNPROCESSABLE,
        List.of("closed classes not yet supported"));
  }

  // ---- nodes ---------------------------------------------------------------

  private void writeNode(Element sim, NetworkModel model, Node n) {
    Element node = Xml.child(sim, "node", "name", n.name());
    if (n instanceof SourceNode src) {
      writeRandomSource(node, model, src);
      writeServiceTunnel(node);
      writeRouter(node, model, n.name());
    } else if (n instanceof QueueNode q) {
      writeQueueSection(node, q);
      writeServer(node, q);
      writeRouter(node, model, n.name());
    } else if (n instanceof SinkNode) {
      Xml.child(node, "section", "className", "JobSink");
    } else {
      writeNonOpenNode(sim, node, model, n); // delay / fork-join → Task 8
    }
  }

  /** Delay and fork-join sections are added in Task 8. */
  protected void writeNonOpenNode(Element sim, Element node, NetworkModel model, Node n) {
    throw new ValidationException(ValidationException.Kind.UNPROCESSABLE,
        List.of("node type '" + n.type() + "' not yet supported"));
  }

  private void writeRandomSource(Element node, NetworkModel model, SourceNode src) {
    Element section = Xml.child(node, "section", "className", "RandomSource");
    // ServiceStrategy parameter: one entry per open class served here.
    Element param = Xml.child(section, "parameter",
        "classPath", "jmt.engine.NetStrategies.ServiceStrategy",
        "name", "ServiceStrategy", "array", "true");
    for (Map.Entry<String, ArrivalSpec> e : src.arrivals().entrySet()) {
      writeServiceStrategyEntry(param, e.getKey(), e.getValue().distribution());
    }
  }

  private void writeServiceTunnel(Element node) {
    Xml.child(node, "section", "className", "ServiceTunnel");
  }

  private void writeQueueSection(Element node, QueueNode q) {
    Element section = Xml.child(node, "section", "className", "Queue");
    Xml.textEl(Xml.child(section, "parameter", "classPath", "java.lang.Integer", "name", "size"),
        "value", q.capacity() == null ? "-1" : q.capacity().toString());
    Xml.textEl(Xml.child(section, "parameter", "classPath", "java.lang.String", "name", "dropStrategy"),
        "value", q.capacity() == null ? "waiting queue" : "drop");
    // FCFS get/put strategies are the JMT defaults for the schema; scheduling honored on Server.
  }

  private void writeServer(Element node, QueueNode q) {
    Element section = Xml.child(node, "section", "className", "Server");
    Xml.textEl(Xml.child(section, "parameter", "classPath", "java.lang.Integer", "name", "maxJobs"),
        "value", Integer.toString(q.servers()));
    Xml.textEl(Xml.child(section, "parameter", "classPath", "java.lang.Integer", "name", "numberOfVisits"),
        "value", "0");
    Element svc = Xml.child(section, "parameter",
        "classPath", "jmt.engine.NetStrategies.ServiceStrategy",
        "name", "ServiceStrategy", "array", "true");
    for (Map.Entry<String, ServiceSpec> e : q.service().entrySet()) {
      writeServiceStrategyEntry(svc, e.getKey(), e.getValue().distribution());
    }
  }

  // ---- shared: a distribution as a ServiceStrategy array entry --------------

  /** Emits one refClass subParameter + the ServiceTimeStrategy holding the distribution. */
  void writeServiceStrategyEntry(Element serviceStrategyParam, String className, Distribution dist) {
    CanonicalDistribution c = resolver.resolve(dist);
    Element refClass = Xml.child(serviceStrategyParam, "refClass");
    refClass.appendChild(refClass.getOwnerDocument().createTextNode(className));
    Element strat = Xml.child(serviceStrategyParam, "subParameter",
        "classPath", "jmt.engine.NetStrategies.ServiceStrategies.ServiceTimeStrategy",
        "name", "ServiceTimeStrategy");
    // distribution wrapper (empty) + its Par block
    Xml.child(strat, "subParameter", "classPath", c.distributionClass(), "name", c.label());
    Element par = Xml.child(strat, "subParameter",
        "classPath", c.parameterClass(), "name", "distrPar");
    for (DistParam p : c.params()) {
      Element sp = Xml.child(par, "subParameter", "classPath", p.javaType(), "name", p.name());
      Xml.textEl(sp, "value", p.value());
    }
  }

  // ---- routing -------------------------------------------------------------

  private void writeRouter(Element node, NetworkModel model, String fromNode) {
    Element section = Xml.child(node, "section", "className", "Router");
    Element param = Xml.child(section, "parameter",
        "classPath", "jmt.engine.NetStrategies.RoutingStrategy",
        "name", "RoutingStrategy", "array", "true");
    for (JobClass c : model.classes()) {
      List<RoutingEdge> edges = outEdges(model, c.name(), fromNode);
      Element refClass = Xml.child(param, "refClass");
      refClass.appendChild(refClass.getOwnerDocument().createTextNode(c.name()));
      if (edges.size() <= 1) {
        Xml.child(param, "subParameter",
            "classPath", "jmt.engine.NetStrategies.RoutingStrategies.RandomStrategy",
            "name", "Random");
      } else {
        Element emp = Xml.child(param, "subParameter",
            "classPath", "jmt.engine.NetStrategies.RoutingStrategies.EmpiricalStrategy",
            "name", "Probabilities");
        Element entries = Xml.child(emp, "subParameter",
            "classPath", "jmt.engine.random.EmpiricalEntry", "name", "EmpiricalEntryArray", "array", "true");
        for (RoutingEdge edge : edges) {
          Element entry = Xml.child(entries, "subParameter",
              "classPath", "jmt.engine.random.EmpiricalEntry", "name", "EmpiricalEntry");
          Xml.textEl(Xml.child(entry, "subParameter", "classPath", "java.lang.String", "name", "stationName"),
              "value", edge.to());
          double prob = edge.probability() == null ? 1.0 / edges.size() : edge.probability();
          Xml.textEl(Xml.child(entry, "subParameter", "classPath", "java.lang.Double", "name", "probability"),
              "value", Double.toString(prob));
        }
      }
    }
  }

  private static List<RoutingEdge> outEdges(NetworkModel model, String className, String fromNode) {
    List<RoutingEdge> out = new ArrayList<>();
    List<RoutingEdge> edges = model.routing() == null ? List.of() : model.routing().getOrDefault(className, List.of());
    for (RoutingEdge e : edges) {
      if (e.from().equals(fromNode)) {
        out.add(e);
      }
    }
    return out;
  }

  private void writeConnections(Element sim, NetworkModel model) {
    // Distinct (from,to) pairs across all classes — connections are class-agnostic in JSIMG.
    var seen = new java.util.LinkedHashSet<String>();
    if (model.routing() != null) {
      for (List<RoutingEdge> edges : model.routing().values()) {
        for (RoutingEdge e : edges) {
          if (seen.add(e.from() + "->" + e.to())) {
            Xml.child(sim, "connection", "source", e.from(), "target", e.to());
          }
        }
      }
    }
  }

  // ---- measures ------------------------------------------------------------

  private void writeMeasure(Element sim, MeasureSpec m, Stopping stopping) {
    String alpha = stopping == null || stopping.alpha() == null ? "0.01" : Double.toString(1.0 - stopping.alpha());
    String precision = stopping == null || stopping.precision() == null ? "0.03" : stopping.precision().toString();
    Xml.child(sim, "measure",
        "name", m.name(),
        "type", m.jmtType(),
        "referenceNode", m.referenceNode(),
        "referenceUserClass", m.referenceUserClass(),
        "nodeType", m.nodeType(),
        "alpha", alpha,
        "precision", precision,
        "verbose", "false");
  }

  // ---- XSD validation ------------------------------------------------------

  public void validate(Document doc) {
    try {
      SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
      // Resolve via the resource URL so the schema's relative <xs:include Archive.xsd>
      // (which defines the shared jdouble type) resolves within the JMT jar.
      var xsdUrl = JsimgWriter.class.getResource("/jmt/common/xml/SIMmodeldefinition.xsd");
      if (xsdUrl == null) {
        throw new IllegalStateException("SIMmodeldefinition.xsd not found on classpath (JMT jar missing?)");
      }
      Schema schema = sf.newSchema(new javax.xml.transform.stream.StreamSource(xsdUrl.toExternalForm()));
      Validator v = schema.newValidator();
      v.validate(new DOMSource(doc));
    } catch (org.xml.sax.SAXException e) {
      throw new ValidationException(ValidationException.Kind.UNPROCESSABLE,
          List.of("generated JSIMG failed XSD validation: " + e.getMessage()));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("XSD validation I/O error", e);
    }
  }
}
