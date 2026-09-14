/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.core.Transform;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The tuning adapter must report the current solver faithfully before any approach is tried. */
public class RigidRotationA000Test {
    @Test
    public void knownRigidFixtureProducesFiniteTruthBasedMetrics() {
        int size = 64;
        ThevenazProtocolBenchmark.Frames frames = ThevenazProtocolBenchmark.generate(
                ThevenazProtocolBenchmark.rotationFixture(size, size), size, size,
                new Transform(0.75, -0.5, Math.toRadians(1.0)));
        RigidRotationA000.Measurement measurement = RigidRotationA000.measure(frames);
        assertTrue(Double.isFinite(measurement.central50));
        assertTrue(Double.isFinite(measurement.full));
        assertTrue(measurement.runtimeMs >= 0);
        assertEquals(frames.truth.thetaDegrees(), measurement.truth.thetaDegrees(), 0.0);
    }
}
