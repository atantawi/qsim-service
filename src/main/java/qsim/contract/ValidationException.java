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
package qsim.contract;

import java.util.List;

public class ValidationException extends RuntimeException {
  public enum Kind { BAD_REQUEST, UNPROCESSABLE }
  private final transient Kind kind;
  private final transient List<String> details;

  public ValidationException(Kind kind, List<String> details) {
    super(String.join("; ", details));
    this.kind = kind;
    this.details = List.copyOf(details);
  }

  public Kind kind() { return kind; }

  public List<String> details() { return details; }
}
