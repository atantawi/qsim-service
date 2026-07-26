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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import qsim.model.Distribution;
import org.junit.jupiter.api.Test;

class DistributionResolverTest {

  private final DistributionResolver resolver = new DistributionResolver();

  @Test
  void namedExponentialMapsRateToLambda() {
    CanonicalDistribution c = resolver.resolve(new Distribution("exponential", 10.0, null, null, null));
    assertEquals("jmt.engine.random.Exponential", c.distributionClass());
    assertEquals("jmt.engine.random.ExponentialPar", c.parameterClass());
    assertEquals(1, c.params().size());
    assertEquals("lambda", c.params().get(0).name());
    assertEquals("10.0", c.params().get(0).value());
  }

  @Test
  void namedDeterministicMapsValueToT() {
    CanonicalDistribution c = resolver.resolve(new Distribution("deterministic", null, 0.25, null, null));
    assertEquals("jmt.engine.random.DeterministicDistr", c.distributionClass());
    assertEquals("t", c.params().get(0).name());
    assertEquals("0.25", c.params().get(0).value());
  }

  @Test
  void momentScvOneIsExponential() {
    CanonicalDistribution c = resolver.resolve(new Distribution(null, null, null, 0.5, 1.0));
    assertEquals("jmt.engine.random.Exponential", c.distributionClass());
    assertEquals("lambda", c.params().get(0).name());
    assertEquals("2.0", c.params().get(0).value()); // 1/mean
  }

  @Test
  void momentScvZeroIsDeterministic() {
    CanonicalDistribution c = resolver.resolve(new Distribution(null, null, null, 0.5, 0.0));
    assertEquals("jmt.engine.random.DeterministicDistr", c.distributionClass());
    assertEquals("0.5", c.params().get(0).value());
  }

  @Test
  void momentGeneralScvIsGamma() {
    CanonicalDistribution c = resolver.resolve(new Distribution(null, null, null, 0.5, 2.0));
    assertEquals("jmt.engine.random.GammaDistr", c.distributionClass());
    assertEquals("alpha", c.params().get(0).name());
    assertEquals("0.5", c.params().get(0).value());  // 1/scv
    assertEquals("beta", c.params().get(1).name());
    assertEquals("1.0", c.params().get(1).value());  // mean*scv
  }

  @Test
  void invalidInputsThrow() {
    assertThrows(IllegalArgumentException.class,
        () -> resolver.resolve(new Distribution("exponential", -1.0, null, null, null)));
    assertThrows(IllegalArgumentException.class,
        () -> resolver.resolve(new Distribution(null, null, null, 0.0, 1.0)));   // mean must be > 0
    assertThrows(IllegalArgumentException.class,
        () -> resolver.resolve(new Distribution(null, null, null, 1.0, -0.5))); // scv >= 0
    assertThrows(IllegalArgumentException.class,
        () -> resolver.resolve(new Distribution(null, null, null, null, null))); // neither form
  }
}
