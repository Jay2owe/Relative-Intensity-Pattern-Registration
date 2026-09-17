/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import logratio.core.LogPlane;
import logratio.core.RobustNorm;
import logratio.core.Transform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** One robust linear tile correction from field-wide integral images at a measured pilot transform. */
final class FastTileVotes {

    private static final double SCALE_FLOOR = 1e-4;
    private static final double MIN_TEXTURE_RATIO = 0.01;

    private FastTileVotes() {
    }

    static List<ModalTileAligner.Vote> votes(LogPlane a, LogPlane b, Transform start,
                                             Transform referenceToA) {
        if (a.width != b.width || a.height != b.height) {
            throw new IllegalArgumentException("tile frames differ in size");
        }
        int width = a.width;
        int height = a.height;
        int pixels = width * height;
        double centreX = 0.5 * (width - 1);
        double centreY = 0.5 * (height - 1);
        double[] mapped = new double[2];
        double[] residualByPixel = new double[pixels];
        Arrays.fill(residualByPixel, Double.NaN);
        double[] residual = new double[pixels];
        int n = 0;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int p = row + x;
                if (!a.valid[p]) continue;
                start.apply(x, y, centreX, centreY, mapped);
                float bv = b.sample(mapped[0], mapped[1]);
                if (Float.isNaN(bv)) continue;
                double value = bv - a.v[p];
                residualByPixel[p] = value;
                residual[n++] = value;
            }
        }
        if (n == 0) return new ArrayList<>();
        double gain = select(residual, n, n / 2);
        for (int i = 0; i < n; i++) residual[i] = Math.abs(residual[i] - gain);
        double mad = select(residual, n, n / 2);
        if (!(mad > 0)) mad = select(residual, n, (int) (0.9 * (n - 1)));
        double scale = Math.max(SCALE_FLOOR, 1.4826 * mad);
        double threshold = RobustNorm.TUKEY.threshold(scale);

        int stride = width + 1;
        int integralSize = (width + 1) * (height + 1);
        double[] hxx = new double[integralSize];
        double[] hxy = new double[integralSize];
        double[] hyy = new double[integralSize];
        double[] bx = new double[integralSize];
        double[] by = new double[integralSize];
        int[] count = new int[integralSize];
        for (int y = 0; y < height; y++) {
            double rowHxx = 0, rowHxy = 0, rowHyy = 0, rowBx = 0, rowBy = 0;
            int rowCount = 0;
            int sourceRow = y * width;
            for (int x = 0; x < width; x++) {
                int source = sourceRow + x;
                double rawResidual = residualByPixel[source];
                if (Double.isFinite(rawResidual)) {
                    start.apply(x, y, centreX, centreY, mapped);
                    float gx = b.sampleGx(mapped[0], mapped[1]);
                    float gy = b.sampleGy(mapped[0], mapped[1]);
                    if (!Float.isNaN(gx) && !Float.isNaN(gy)) {
                        double r = rawResidual - gain;
                        double weight = RobustNorm.TUKEY.weight(r, threshold);
                        if (weight > 0) {
                            rowHxx += weight * gx * gx;
                            rowHxy += weight * gx * gy;
                            rowHyy += weight * gy * gy;
                            rowBx += weight * gx * r;
                            rowBy += weight * gy * r;
                            rowCount++;
                        }
                    }
                }
                int q = (y + 1) * stride + x + 1;
                hxx[q] = hxx[q - stride] + rowHxx;
                hxy[q] = hxy[q - stride] + rowHxy;
                hyy[q] = hyy[q - stride] + rowHyy;
                bx[q] = bx[q - stride] + rowBx;
                by[q] = by[q - stride] + rowBy;
                count[q] = count[q - stride] + rowCount;
            }
        }

        int tile = Math.min(ModalTileAligner.TILE_SIZE, Math.min(width, height));
        List<Integer> xs = origins(width, tile, Math.min(ModalTileAligner.TILE_STRIDE, tile));
        List<Integer> ys = origins(height, tile, Math.min(ModalTileAligner.TILE_STRIDE, tile));
        List<ModalTileAligner.Vote> votes = new ArrayList<>(xs.size() * ys.size());
        for (int referenceY0 : ys) {
            for (int referenceX0 : xs) {
                double referenceX = referenceX0 + 0.5 * tile;
                double referenceY = referenceY0 + 0.5 * tile;
                referenceToA.apply(referenceX, referenceY, centreX, centreY, mapped);
                int x0 = (int) Math.round(mapped[0] - 0.5 * tile);
                int y0 = (int) Math.round(mapped[1] - 0.5 * tile);
                int x1 = x0 + tile;
                int y1 = y0 + tile;
                if (x0 < 0 || y0 < 0 || x1 > width || y1 > height) continue;
                int used = rectangle(count, stride, x0, y0, x1, y1);
                if (used < Math.max(16, tile * tile / 4)) continue;
                double xx = rectangle(hxx, stride, x0, y0, x1, y1);
                double xy = rectangle(hxy, stride, x0, y0, x1, y1);
                double yy = rectangle(hyy, stride, x0, y0, x1, y1);
                double trace = xx + yy;
                double discriminant = Math.sqrt((xx - yy) * (xx - yy) + 4 * xy * xy);
                double small = 0.5 * (trace - discriminant);
                double large = 0.5 * (trace + discriminant);
                if (!(small > 0) || small / Math.max(small, large) < MIN_TEXTURE_RATIO) continue;
                double determinant = xx * yy - xy * xy;
                if (!(determinant > 1e-12 * (Math.abs(xx * yy) + xy * xy + 1e-30))) continue;
                double localBx = rectangle(bx, stride, x0, y0, x1, y1);
                double localBy = rectangle(by, stride, x0, y0, x1, y1);
                double stepX = (-yy * localBx + xy * localBy) / determinant;
                double stepY = (xy * localBx - xx * localBy) / determinant;
                double step = Math.hypot(stepX, stepY);
                if (step > 1.0) {
                    stepX /= step;
                    stepY /= step;
                }
                votes.add(new ModalTileAligner.Vote(start.dx + stepX, start.dy + stepY,
                        small / used, referenceX, referenceY));
            }
        }
        return votes;
    }

    private static double rectangle(double[] integral, int stride,
                                    int x0, int y0, int x1, int y1) {
        int a = y0 * stride + x0;
        int b = y0 * stride + x1;
        int c = y1 * stride + x0;
        int d = y1 * stride + x1;
        return integral[d] - integral[b] - integral[c] + integral[a];
    }

    private static int rectangle(int[] integral, int stride,
                                 int x0, int y0, int x1, int y1) {
        int a = y0 * stride + x0;
        int b = y0 * stride + x1;
        int c = y1 * stride + x0;
        int d = y1 * stride + x1;
        return integral[d] - integral[b] - integral[c] + integral[a];
    }

    private static List<Integer> origins(int length, int tile, int stride) {
        List<Integer> out = new ArrayList<>();
        for (int x = 0; x + tile <= length; x += stride) out.add(x);
        int last = length - tile;
        if (out.isEmpty() || out.get(out.size() - 1) != last) out.add(last);
        return out;
    }

    private static double select(double[] values, int n, int k) {
        int lo = 0;
        int hi = n - 1;
        while (lo < hi) {
            double pivot = values[(lo + hi) >>> 1];
            int i = lo;
            int j = hi;
            while (i <= j) {
                while (values[i] < pivot) i++;
                while (values[j] > pivot) j--;
                if (i <= j) {
                    double swap = values[i];
                    values[i] = values[j];
                    values[j] = swap;
                    i++;
                    j--;
                }
            }
            if (k <= j) hi = j;
            else if (k >= i) lo = i;
            else return values[k];
        }
        return values[lo];
    }
}
