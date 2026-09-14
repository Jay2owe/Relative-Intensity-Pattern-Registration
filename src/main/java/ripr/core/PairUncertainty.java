/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import java.util.Arrays;

/**
 * Direction-aware uncertainty of one pair transform, in pixels and radians.
 *
 * <p>The matrix is covariance of {@code [dx, dy]} or {@code [dx, dy, theta]}; it is not a
 * repackaged residual or correlation score. Instances are immutable and return defensive copies.
 */
public final class PairUncertainty {

    /** Smallest retained standard deviation in pixel-equivalent coordinates. */
    public static final double MIN_STD_PIXELS = 0.02;
    /** Largest retained standard deviation in pixel-equivalent coordinates. */
    public static final double MAX_STD_PIXELS = 20.0;

    public final int dimensions;
    /** Accepted correlation minus the best spatially distinct alternative; NaN if inapplicable. */
    public final double peakAmbiguity;
    /** True only after a calibration external to the fitted pair has been frozen. */
    public final boolean calibrated;
    public final boolean eigenvalueFloored;
    public final boolean eigenvalueCapped;

    private final double[] covariance;
    private final double[] information;

    private PairUncertainty(int dimensions, double[] covariance, double[] information,
                            double peakAmbiguity, boolean calibrated,
                            boolean floored, boolean capped) {
        this.dimensions = dimensions;
        this.covariance = covariance;
        this.information = information;
        this.peakAmbiguity = peakAmbiguity;
        this.calibrated = calibrated;
        this.eigenvalueFloored = floored;
        this.eigenvalueCapped = capped;
    }

    public static PairUncertainty unavailable(int dimensions) {
        checkDimensions(dimensions);
        return new PairUncertainty(dimensions, null, null, Double.NaN,
                false, false, false);
    }

    /** Build from an already validated physical covariance matrix. */
    public static PairUncertainty fromCovariance(
            int dimensions, double[] rowMajorCovariance,
            double peakAmbiguity, boolean calibrated,
            boolean floored, boolean capped) {
        checkDimensions(dimensions);
        if (rowMajorCovariance == null
                || rowMajorCovariance.length != dimensions * dimensions) {
            throw new IllegalArgumentException("covariance must contain "
                    + (dimensions * dimensions) + " values");
        }
        double[] symmetric = symmetrize(rowMajorCovariance, dimensions);
        double[] inverse = inverseSpd(symmetric, dimensions);
        if (inverse == null) {
            throw new IllegalArgumentException("covariance must be finite, symmetric positive definite");
        }
        return new PairUncertainty(dimensions, symmetric, inverse, peakAmbiguity,
                calibrated, floored, capped);
    }

    public boolean available() {
        return covariance != null;
    }

    public double[] covariance() {
        return covariance == null ? null : covariance.clone();
    }

    public double[] information() {
        return information == null ? null : information.clone();
    }

    /** Propagate covariance through {@link Transform#inverse()}. */
    public PairUncertainty inverseFor(Transform forward) {
        if (!available()) return unavailable(dimensions);
        if (forward == null) throw new IllegalArgumentException("forward transform is null");
        double c = Math.cos(forward.theta);
        double s = Math.sin(forward.theta);
        double[] jacobian;
        if (dimensions == 2) {
            jacobian = new double[]{-c, -s, s, -c};
        } else {
            jacobian = new double[]{
                    -c, -s, s * forward.dx - c * forward.dy,
                    s, -c, c * forward.dx + s * forward.dy,
                    0, 0, -1
            };
        }
        double[] propagated = congruence(jacobian, covariance, dimensions);
        return fromCovariance(dimensions, propagated, peakAmbiguity, calibrated,
                eigenvalueFloored, eigenvalueCapped);
    }

    /** Scale dx/dy uncertainty between image resolutions while retaining radians. */
    public PairUncertainty scaleTranslations(double factor) {
        if (!available()) return unavailable(dimensions);
        if (!(factor > 0) || !Double.isFinite(factor)) {
            throw new IllegalArgumentException("translation scale must be finite and > 0");
        }
        double[] scaled = covariance.clone();
        for (int row = 0; row < dimensions; row++) {
            double rowScale = row < 2 ? factor : 1.0;
            for (int col = 0; col < dimensions; col++) {
                double colScale = col < 2 ? factor : 1.0;
                scaled[row * dimensions + col] *= rowScale * colScale;
            }
        }
        return fromCovariance(dimensions, scaled, peakAmbiguity, calibrated,
                eigenvalueFloored, eigenvalueCapped);
    }

    /**
     * Full-resolution robust sandwich covariance for the accepted log-ratio transform.
     */
    static PairUncertainty estimateLogRatio(
            LogPlane a, LogPlane b, PairAligner.Options options, Transform transform) {
        int dimensions = options.fitRotation ? 3 : 2;
        int stride = PairAligner.strideFor(a.width, a.height, options.maxSamples);
        int capacity = ((a.width + stride - 1) / stride)
                * ((a.height + stride - 1) / stride);
        double[] residual = new double[capacity];
        double[] jacobian = new double[capacity * dimensions];
        double[] mapped = new double[2];
        double cx = (a.width - 1) / 2.0;
        double cy = (a.height - 1) / 2.0;
        double sin = Math.sin(transform.theta);
        double cos = Math.cos(transform.theta);
        int n = 0;
        for (int y = 0; y < a.height; y += stride) {
            int row = y * a.width;
            for (int x = 0; x < a.width; x += stride) {
                int index = row + x;
                if (!a.valid[index]) continue;
                transform.apply(x, y, cx, cy, mapped);
                float value = b.sample(mapped[0], mapped[1]);
                float gx = b.sampleGx(mapped[0], mapped[1]);
                float gy = b.sampleGy(mapped[0], mapped[1]);
                if (Float.isNaN(value) || Float.isNaN(gx) || Float.isNaN(gy)) continue;
                residual[n] = value - a.v[index];
                int base = n * dimensions;
                jacobian[base] = gx;
                jacobian[base + 1] = gy;
                if (dimensions == 3) {
                    double u = x - cx;
                    double v = y - cy;
                    jacobian[base + 2] = gx * (-u * sin - v * cos)
                            + gy * (u * cos - v * sin);
                }
                n++;
            }
        }
        if (n < dimensions + 2) return unavailable(dimensions);

        double[] order = Arrays.copyOf(residual, n);
        double gain = options.profileGain ? RobustNorm.select(order, n, n / 2) : 0.0;
        double[] centredResidual = new double[n];
        for (int i = 0; i < n; i++) centredResidual[i] = residual[i] - gain;
        double scale = RobustNorm.scale(centredResidual.clone(), n, options.scaleFloor);
        double threshold = options.norm.threshold(scale);

        double[] weights = new double[n];
        double[] meanJ = new double[dimensions];
        double sumWeight = 0;
        for (int i = 0; i < n; i++) {
            double weight = options.norm.weight(centredResidual[i], threshold);
            if (!Double.isFinite(weight) || weight < 0) return unavailable(dimensions);
            weights[i] = weight;
            sumWeight += weight;
            for (int d = 0; d < dimensions; d++) {
                meanJ[d] += weight * jacobian[i * dimensions + d];
            }
        }
        if (!(sumWeight > dimensions + 1)) return unavailable(dimensions);
        for (int d = 0; d < dimensions; d++) meanJ[d] /= sumWeight;

        double[] bread = new double[dimensions * dimensions];
        double[] meat = new double[dimensions * dimensions];
        for (int i = 0; i < n; i++) {
            double weight = weights[i];
            if (!(weight > 0)) continue;
            double psi2 = weight * weight * centredResidual[i] * centredResidual[i];
            int base = i * dimensions;
            for (int row = 0; row < dimensions; row++) {
                double jr = jacobian[base + row] - meanJ[row];
                for (int col = 0; col < dimensions; col++) {
                    double product = jr * (jacobian[base + col] - meanJ[col]);
                    bread[row * dimensions + col] += weight * product;
                    meat[row * dimensions + col] += psi2 * product;
                }
            }
        }
        double[] inverseBread = inverseSpd(symmetrize(bread, dimensions), dimensions);
        if (inverseBread == null) return unavailable(dimensions);
        double[] covariance = multiply(multiply(inverseBread, meat, dimensions),
                inverseBread, dimensions);
        double radius = rmsRadius(a.width, a.height);
        return bounded(dimensions, covariance, Double.NaN, false, radius,
                options.uncertaintyMinimumStdPixels,
                options.uncertaintyMaximumStdPixels);
    }

    /** Build and clamp a physical covariance in pixel-equivalent eigen coordinates. */
    static PairUncertainty bounded(int dimensions, double[] physicalCovariance,
                                   double peakAmbiguity, boolean calibrated,
                                   double rotationRadiusPixels) {
        return bounded(dimensions, physicalCovariance, peakAmbiguity, calibrated,
                rotationRadiusPixels, MIN_STD_PIXELS, MAX_STD_PIXELS);
    }

    /** Build and clamp using an explicitly frozen development policy. */
    static PairUncertainty bounded(int dimensions, double[] physicalCovariance,
                                   double peakAmbiguity, boolean calibrated,
                                   double rotationRadiusPixels,
                                   double minimumStdPixels, double maximumStdPixels) {
        checkDimensions(dimensions);
        if (physicalCovariance == null
                || physicalCovariance.length != dimensions * dimensions) {
            return unavailable(dimensions);
        }
        double radius = dimensions == 3 ? rotationRadiusPixels : 1.0;
        if (!(radius > 0) || !Double.isFinite(radius)) return unavailable(dimensions);
        if (!(minimumStdPixels > 0) || !Double.isFinite(minimumStdPixels)
                || !(maximumStdPixels >= minimumStdPixels)
                || !Double.isFinite(maximumStdPixels)) {
            return unavailable(dimensions);
        }
        double[] equivalent = symmetrize(physicalCovariance, dimensions);
        for (int row = 0; row < dimensions; row++) {
            double rowScale = row == 2 ? radius : 1.0;
            for (int col = 0; col < dimensions; col++) {
                double colScale = col == 2 ? radius : 1.0;
                equivalent[row * dimensions + col] *= rowScale * colScale;
            }
        }
        Eigen eigen = eigen(equivalent, dimensions);
        if (eigen == null) return unavailable(dimensions);
        double min = minimumStdPixels * minimumStdPixels;
        double max = maximumStdPixels * maximumStdPixels;
        boolean floored = false;
        boolean capped = false;
        for (int i = 0; i < dimensions; i++) {
            double value = eigen.values[i];
            if (!Double.isFinite(value) || value < -1e-10 * Math.max(1.0, max)) {
                return unavailable(dimensions);
            }
            if (value < min) {
                eigen.values[i] = min;
                floored = true;
            } else if (value > max) {
                eigen.values[i] = max;
                capped = true;
            }
        }
        double[] boundedEquivalent = reconstruct(eigen, dimensions);
        for (int row = 0; row < dimensions; row++) {
            double rowScale = row == 2 ? radius : 1.0;
            for (int col = 0; col < dimensions; col++) {
                double colScale = col == 2 ? radius : 1.0;
                boundedEquivalent[row * dimensions + col] /= rowScale * colScale;
            }
        }
        try {
            return fromCovariance(dimensions, boundedEquivalent, peakAmbiguity, calibrated,
                    floored, capped);
        } catch (IllegalArgumentException invalid) {
            return unavailable(dimensions);
        }
    }

    static double rmsRadius(int width, int height) {
        return Math.sqrt(Math.max(1.0,
                (width * (double) width + height * (double) height - 2.0) / 12.0));
    }

    private static void checkDimensions(int dimensions) {
        if (dimensions != 2 && dimensions != 3) {
            throw new IllegalArgumentException("uncertainty dimensions must be 2 or 3");
        }
    }

    private static double[] symmetrize(double[] matrix, int dimensions) {
        double[] out = matrix.clone();
        for (int row = 0; row < dimensions; row++) {
            for (int col = 0; col < dimensions; col++) {
                double value = 0.5 * (matrix[row * dimensions + col]
                        + matrix[col * dimensions + row]);
                if (!Double.isFinite(value)) return fillNaN(dimensions * dimensions);
                out[row * dimensions + col] = value;
            }
        }
        return out;
    }

    private static double[] fillNaN(int count) {
        double[] out = new double[count];
        Arrays.fill(out, Double.NaN);
        return out;
    }

    /** Inverse through Cholesky; null means non-finite or non-positive-definite. */
    static double[] inverseSpd(double[] matrix, int dimensions) {
        if (matrix == null || matrix.length != dimensions * dimensions) return null;
        double[] lower = new double[matrix.length];
        for (int row = 0; row < dimensions; row++) {
            for (int col = 0; col <= row; col++) {
                double sum = matrix[row * dimensions + col];
                if (!Double.isFinite(sum)) return null;
                for (int k = 0; k < col; k++) {
                    sum -= lower[row * dimensions + k] * lower[col * dimensions + k];
                }
                if (row == col) {
                    if (!(sum > 0)) return null;
                    lower[row * dimensions + col] = Math.sqrt(sum);
                } else {
                    lower[row * dimensions + col] = sum
                            / lower[col * dimensions + col];
                }
            }
        }
        double[] inverse = new double[matrix.length];
        for (int column = 0; column < dimensions; column++) {
            double[] y = new double[dimensions];
            for (int row = 0; row < dimensions; row++) {
                double value = row == column ? 1.0 : 0.0;
                for (int k = 0; k < row; k++) {
                    value -= lower[row * dimensions + k] * y[k];
                }
                y[row] = value / lower[row * dimensions + row];
            }
            for (int row = dimensions - 1; row >= 0; row--) {
                double value = y[row];
                for (int k = row + 1; k < dimensions; k++) {
                    value -= lower[k * dimensions + row]
                            * inverse[k * dimensions + column];
                }
                inverse[row * dimensions + column] = value
                        / lower[row * dimensions + row];
            }
        }
        return symmetrize(inverse, dimensions);
    }

    private static double[] congruence(double[] jacobian, double[] matrix, int dimensions) {
        return multiply(multiply(jacobian, matrix, dimensions),
                transpose(jacobian, dimensions), dimensions);
    }

    private static double[] multiply(double[] left, double[] right, int dimensions) {
        double[] out = new double[dimensions * dimensions];
        for (int row = 0; row < dimensions; row++) {
            for (int col = 0; col < dimensions; col++) {
                double sum = 0;
                for (int k = 0; k < dimensions; k++) {
                    sum += left[row * dimensions + k] * right[k * dimensions + col];
                }
                out[row * dimensions + col] = sum;
            }
        }
        return out;
    }

    private static double[] transpose(double[] matrix, int dimensions) {
        double[] out = new double[matrix.length];
        for (int row = 0; row < dimensions; row++) {
            for (int col = 0; col < dimensions; col++) {
                out[col * dimensions + row] = matrix[row * dimensions + col];
            }
        }
        return out;
    }

    private static final class Eigen {
        final double[] values;
        final double[] vectors;

        Eigen(double[] values, double[] vectors) {
            this.values = values;
            this.vectors = vectors;
        }
    }

    /** Jacobi decomposition for the only supported sizes (2 and 3). */
    private static Eigen eigen(double[] matrix, int dimensions) {
        double[] a = symmetrize(matrix, dimensions);
        for (double value : a) if (!Double.isFinite(value)) return null;
        double[] vectors = new double[dimensions * dimensions];
        for (int i = 0; i < dimensions; i++) vectors[i * dimensions + i] = 1.0;
        for (int iteration = 0; iteration < 32; iteration++) {
            int p = 0;
            int q = 1;
            double largest = Math.abs(a[q]);
            for (int row = 0; row < dimensions; row++) {
                for (int col = row + 1; col < dimensions; col++) {
                    double value = Math.abs(a[row * dimensions + col]);
                    if (value > largest) {
                        largest = value;
                        p = row;
                        q = col;
                    }
                }
            }
            if (largest < 1e-14) break;
            double app = a[p * dimensions + p];
            double aqq = a[q * dimensions + q];
            double apq = a[p * dimensions + q];
            double angle = 0.5 * Math.atan2(2 * apq, aqq - app);
            double c = Math.cos(angle);
            double s = Math.sin(angle);
            for (int k = 0; k < dimensions; k++) {
                if (k == p || k == q) continue;
                double akp = a[k * dimensions + p];
                double akq = a[k * dimensions + q];
                double newP = c * akp - s * akq;
                double newQ = s * akp + c * akq;
                a[k * dimensions + p] = newP;
                a[p * dimensions + k] = newP;
                a[k * dimensions + q] = newQ;
                a[q * dimensions + k] = newQ;
            }
            a[p * dimensions + p] = c * c * app - 2 * s * c * apq + s * s * aqq;
            a[q * dimensions + q] = s * s * app + 2 * s * c * apq + c * c * aqq;
            a[p * dimensions + q] = 0;
            a[q * dimensions + p] = 0;
            for (int k = 0; k < dimensions; k++) {
                double vkp = vectors[k * dimensions + p];
                double vkq = vectors[k * dimensions + q];
                vectors[k * dimensions + p] = c * vkp - s * vkq;
                vectors[k * dimensions + q] = s * vkp + c * vkq;
            }
        }
        double[] values = new double[dimensions];
        for (int i = 0; i < dimensions; i++) values[i] = a[i * dimensions + i];
        return new Eigen(values, vectors);
    }

    private static double[] reconstruct(Eigen eigen, int dimensions) {
        double[] out = new double[dimensions * dimensions];
        for (int component = 0; component < dimensions; component++) {
            double value = eigen.values[component];
            for (int row = 0; row < dimensions; row++) {
                double vr = eigen.vectors[row * dimensions + component];
                for (int col = 0; col < dimensions; col++) {
                    out[row * dimensions + col] += value * vr
                            * eigen.vectors[col * dimensions + component];
                }
            }
        }
        return symmetrize(out, dimensions);
    }
}
