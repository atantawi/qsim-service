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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import qsim.contract.ValidationException;
import qsim.model.*;

public class MeasureMapper {

  private static final List<String> DEFAULTS =
      List.of("response-time", "utilization", "throughput", "queue-length");

  // domain type -> JMT measure "type" string (station-level)
  private static final Map<String, String> STATION = new LinkedHashMap<>();
  // domain type -> JMT measure "type" string (system-level)
  private static final Map<String, String> SYSTEM = new LinkedHashMap<>();
  /**
   * Fork-join nodes: domain type -> JMT's dedicated fork-region measure string, overriding
   * {@link #STATION}. A station-level {@code "Response Time"} taken anywhere inside the writer's
   * fork/branch/join expansion measures a single station's residence time — at the join station
   * that is only the per-sibling synchronization wait, which is not the fork-to-join sojourn a
   * caller means by {@code response-time} on a fork-join node, and can even fall below a single
   * branch's response time (issue #6). JMT tracks the sojourn under
   * {@code SimConstants.FORK_JOIN_RESPONSE_TIME}, whose job list lives in the *fork* station's
   * input section, so these measures stay anchored on the domain node name.
   */
  private static final Map<String, String> FORK_JOIN_STATION =
      Map.of("response-time", "Fork Join Response Time");

  /**
   * JMT measure types that {@link JsimgWriter} must leave anchored on the fork station instead of
   * remapping onto the internal join station.
   */
  static final Set<String> FORK_JOIN_TYPES = Set.copyOf(FORK_JOIN_STATION.values());

  static {
    STATION.put("response-time", "Response Time");
    STATION.put("residence-time", "Residence Time");
    STATION.put("queue-time", "Queue Time");
    STATION.put("queue-length", "Number of Customers");
    STATION.put("utilization", "Utilization");
    STATION.put("throughput", "Throughput");
    STATION.put("drop-rate", "Drop Rate");
    SYSTEM.put("system-response-time", "System Response Time");
  }

  public static final Set<String> SUPPORTED;
  static {
    var all = new java.util.HashSet<String>();
    all.addAll(STATION.keySet());
    all.addAll(SYSTEM.keySet());
    SUPPORTED = Set.copyOf(all);
  }

  public List<MeasureSpec> map(NetworkModel model, List<String> requested) {
    List<String> types = (requested == null || requested.isEmpty()) ? DEFAULTS : requested;
    for (String t : types) {
      if (!SUPPORTED.contains(t)) {
        throw new ValidationException(ValidationException.Kind.BAD_REQUEST,
            List.of("unsupported measure type: '" + t + "'; supported: " + SUPPORTED));
      }
    }
    List<MeasureSpec> specs = new ArrayList<>();
    for (String t : types) {
      if (STATION.containsKey(t)) {
        String jmt = STATION.get(t);
        for (Node n : model.nodes()) {
          String jmtForNode = n instanceof ForkJoinNode ? FORK_JOIN_STATION.getOrDefault(t, jmt) : jmt;
          for (String clazz : servedClasses(n)) {
            specs.add(new MeasureSpec(n.name() + "_" + clazz + "_" + t, jmtForNode,
                n.name(), clazz, "station"));
          }
        }
      } else { // system-level
        String jmt = SYSTEM.get(t);
        for (JobClass c : model.classes()) {
          specs.add(new MeasureSpec("system_" + c.name() + "_" + t, jmt, "", c.name(), ""));
        }
      }
    }
    return specs;
  }

  /** Classes with service defined at this node (queue/delay/fork-join). Sources/sinks yield none. */
  private static List<String> servedClasses(Node n) {
    if (n instanceof QueueNode q) {
      return new ArrayList<>(q.service().keySet());
    }
    if (n instanceof DelayNode d) {
      return new ArrayList<>(d.service().keySet());
    }
    if (n instanceof ForkJoinNode fj) {
      var set = new java.util.LinkedHashSet<String>();
      for (Branch b : fj.branches()) {
        set.addAll(b.service().keySet());
      }
      return new ArrayList<>(set);
    }
    return List.of();
  }
}
