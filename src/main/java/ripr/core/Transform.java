/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

/**
 * A rigid 2-D transform, immutable: rotate by {@code theta} about a supplied centre,
 * <em>then</em> translate by {@code (dx, dy)}.
 *
 * <p>The order matters and is fixed by this class so that composition has a closed form.
 * With {@code W(x) = R(theta)(x - c) + c + t}, composing {@code W2} after {@code W1} gives
 *
 * <pre>
 *   theta = theta1 + theta2                (exact; rotations about a common centre commute)
 *   t     = R(theta2) t1 + t2
 * </pre>
 *
 * so rotation angles add exactly and translations pick up a rotation of the earlier one.
 * {@link #then(Transform)} implements that exactly. Parameter-space addition
 * ({@link #plus(Transform)}) is only a first-order convenience and is not used by multi-lag
 * reconciliation. {@link Reconciler} solves the exact rigid constraints by keeping the observed
 * rotation fixed in each translation row.
 *
 * <p>Sign convention, fixed once here and relied on everywhere: a {@code Transform} returned
 * by {@link PairAligner} is the motion of the image <em>content</em> from the first frame to
 * the second. A feature at {@code x} in frame A sits at {@code W(x)} in frame B. To hold the
 * field still, {@link Warper} applies the <em>inverse</em>.
 */
public final class Transform {

    /** Translation in x (columns), pixels. */
    public final double dx;
    /** Translation in y (rows), pixels. */
    public final double dy;
    /** Rotation about the supplied centre, radians, positive from +x toward +y. */
    public final double theta;

    public static final Transform IDENTITY = new Transform(0, 0, 0);

    public Transform(double dx, double dy, double theta) {
        this.dx = dx;
        this.dy = dy;
        this.theta = theta;
    }

    public static Transform translation(double dx, double dy) {
        return new Transform(dx, dy, 0);
    }

    /** True when this transform has no rotation component at all. Lets callers take the
     *  bit-exact integer-copy path in {@link Warper} instead of resampling. */
    public boolean isPureTranslation() {
        return theta == 0.0;
    }

    /** Exact composition: this transform first, then {@code next}. */
    public Transform then(Transform next) {
        double c = Math.cos(next.theta);
        double s = Math.sin(next.theta);
        return new Transform(
                c * dx - s * dy + next.dx,
                s * dx + c * dy + next.dy,
                theta + next.theta);
    }

    /** Parameter-space sum. First-order in {@code theta}; see the class comment. */
    public Transform plus(Transform other) {
        return new Transform(dx + other.dx, dy + other.dy, theta + other.theta);
    }

    public Transform negate() {
        return new Transform(-dx, -dy, -theta);
    }

    /** Exact inverse. {@code t.then(t.inverse())} is the identity to rounding. */
    public Transform inverse() {
        double c = Math.cos(-theta);
        double s = Math.sin(-theta);
        return new Transform(-(c * dx - s * dy), -(s * dx + c * dy), -theta);
    }

    /** Scale the translation by {@code f}, leaving the angle alone. Used to carry a
     *  transform between pyramid levels, where a pixel doubles in size but an angle
     *  does not change. */
    public Transform scaleTranslation(double f) {
        return new Transform(dx * f, dy * f, theta);
    }

    /**
     * Map {@code (x, y)} through this transform about centre {@code (cx, cy)},
     * writing {@code {x', y'}} into {@code out}.
     */
    public void apply(double x, double y, double cx, double cy, double[] out) {
        if (theta == 0.0) {
            out[0] = x + dx;
            out[1] = y + dy;
            return;
        }
        double c = Math.cos(theta);
        double s = Math.sin(theta);
        double u = x - cx;
        double v = y - cy;
        out[0] = c * u - s * v + cx + dx;
        out[1] = s * u + c * v + cy + dy;
    }

    /** Translation magnitude, pixels. */
    public double magnitude() {
        return Math.hypot(dx, dy);
    }

    public double thetaDegrees() {
        return Math.toDegrees(theta);
    }

    @Override
    public String toString() {
        return String.format("Transform[dx=%.4f dy=%.4f theta=%.5f deg]", dx, dy, thetaDegrees());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Transform)) return false;
        Transform t = (Transform) o;
        return Double.compare(dx, t.dx) == 0
                && Double.compare(dy, t.dy) == 0
                && Double.compare(theta, t.theta) == 0;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(dx) * 31 * 31 + Double.hashCode(dy) * 31 + Double.hashCode(theta);
    }
}
