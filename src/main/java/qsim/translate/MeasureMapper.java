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
          for (String clazz : servedClasses(n)) {
            specs.add(new MeasureSpec(n.name() + "_" + clazz + "_" + t, jmt,
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
