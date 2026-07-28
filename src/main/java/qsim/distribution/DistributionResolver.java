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
package qsim.distribution;

import java.util.List;
import qsim.model.Distribution;

public class DistributionResolver {

  private static final String D = "java.lang.Double";

  public CanonicalDistribution resolve(Distribution d) {
    if (d.type() != null) {
      return resolveNamed(d);
    }
    return resolveMoment(d);
  }

  private CanonicalDistribution resolveNamed(Distribution d) {
    switch (d.type()) {
      case "exponential": {
        double rate = require(d.rate(), "exponential.rate");
        positive(rate, "exponential.rate");
        return exponential(rate);
      }
      case "deterministic": {
        double v = require(d.value() != null ? d.value() : d.mean(), "deterministic.value");
        positive(v, "deterministic.value");
        return deterministic(v);
      }
      default:
        throw new IllegalArgumentException("unsupported distribution type: " + d.type()
            + " (v1 supports exponential, deterministic, or moment form {mean, scv})");
    }
  }

  private CanonicalDistribution resolveMoment(Distribution d) {
    double mean = require(d.mean(), "distribution.mean");
    double scv = require(d.scv(), "distribution.scv");
    positive(mean, "distribution.mean");
    if (scv < 0) {
      throw new IllegalArgumentException("distribution.scv must be >= 0");
    }
    if (scv == 1.0) {
      return exponential(1.0 / mean);
    }
    if (scv == 0.0) {
      return deterministic(mean);
    }
    // Gamma in shape/SCALE form: alpha = shape, beta = scale (NOT rate).
    // JMT's GammaDistrPar names its second field "lambda", which suggests a rate, but
    // GammaDistr.nextRand and pdf both use it as a scale: E[X] = alpha*beta, Var = alpha*beta^2.
    // (GammaDistr.theorMean/theorVariance do use the rate convention and are therefore wrong,
    // but nothing in jmt.engine or here calls them.) With scale semantics this mapping inverts
    // the moments exactly: mean = alpha*beta = mean, scv = 1/alpha = scv.
    double alpha = 1.0 / scv;
    double beta = mean * scv;
    return new CanonicalDistribution("jmt.engine.random.GammaDistr",
        "jmt.engine.random.GammaDistrPar", "Gamma",
        List.of(new DistParam("alpha", D, str(alpha)), new DistParam("beta", D, str(beta))));
  }

  private CanonicalDistribution exponential(double lambda) {
    return new CanonicalDistribution("jmt.engine.random.Exponential",
        "jmt.engine.random.ExponentialPar", "Exponential",
        List.of(new DistParam("lambda", D, str(lambda))));
  }

  private CanonicalDistribution deterministic(double t) {
    return new CanonicalDistribution("jmt.engine.random.DeterministicDistr",
        "jmt.engine.random.DeterministicDistrPar", "Deterministic",
        List.of(new DistParam("t", D, str(t))));
  }

  private static double require(Double v, String field) {
    if (v == null) {
      throw new IllegalArgumentException("missing required field: " + field);
    }
    return v;
  }

  private static void positive(double v, String field) {
    if (v <= 0) {
      throw new IllegalArgumentException(field + " must be > 0");
    }
  }

  // Render doubles without locale/exponent surprises; integers stay clean (2.0 -> "2.0").
  private static String str(double v) {
    return Double.toString(v);
  }
}
