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
package qsim.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "type", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = SourceNode.class, name = "source"),
    @JsonSubTypes.Type(value = QueueNode.class, name = "queue"),
    @JsonSubTypes.Type(value = ForkJoinNode.class, name = "fork-join"),
    @JsonSubTypes.Type(value = DelayNode.class, name = "delay"),
    @JsonSubTypes.Type(value = SinkNode.class, name = "sink")
})
public sealed interface Node
    permits SourceNode, QueueNode, ForkJoinNode, DelayNode, SinkNode {
  String name();
  String type();
}
