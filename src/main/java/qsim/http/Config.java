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
package qsim.http;

public record Config(int port, String tempDir, double defaultAlpha, double defaultPrecision,
                     int defaultMinSamples, int defaultMaxSamples, int defaultMaxWallClockSeconds) {

  public static Config defaults() {
    return new Config(8080, System.getProperty("java.io.tmpdir"),
        0.05, 0.05, 10_000, 1_000_000, 120);
  }

  public static Config fromEnv() {
    Config d = defaults();
    return new Config(
        envInt("QSIM_PORT", d.port()),
        env("QSIM_TEMP_DIR", d.tempDir()),
        envDouble("QSIM_DEFAULT_ALPHA", d.defaultAlpha()),
        envDouble("QSIM_DEFAULT_PRECISION", d.defaultPrecision()),
        envInt("QSIM_DEFAULT_MIN_SAMPLES", d.defaultMinSamples()),
        envInt("QSIM_DEFAULT_MAX_SAMPLES", d.defaultMaxSamples()),
        envInt("QSIM_DEFAULT_MAX_WALLCLOCK_SECONDS", d.defaultMaxWallClockSeconds()));
  }

  private static String env(String k, String dflt) {
    String v = System.getenv(k);
    return v == null || v.isBlank() ? dflt : v;
  }
  private static int envInt(String k, int dflt) {
    String v = System.getenv(k);
    return v == null || v.isBlank() ? dflt : Integer.parseInt(v.trim());
  }
  private static double envDouble(String k, double dflt) {
    String v = System.getenv(k);
    return v == null || v.isBlank() ? dflt : Double.parseDouble(v.trim());
  }
}
