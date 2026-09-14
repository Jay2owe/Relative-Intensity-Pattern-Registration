/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Round-17 emission trajectory reconstruction. Experimental until full-stack gates pass. */
final class OpenCvLongitudinalTrajectory {
    static final class Outcome {
        final double[][] trajectory;
        final double[] confidence;
        final int bright, dim;
        final List<String> pairs;
        Outcome(double[][] trajectory, double[] confidence, int bright, int dim, List<String> pairs) {
            this.trajectory = trajectory; this.confidence = confidence;
            this.bright = bright; this.dim = dim; this.pairs = pairs;
        }
    }
    private static final class Pose {
        final double[] matrix; final double score;
        Pose(double[] matrix, double score) { this.matrix = matrix; this.score = score; }
    }

    static Outcome estimate(float[][] raw, int width, int height, double[][] baseline) {
        return estimate(raw,width,height,baseline,LongitudinalExecutionPolicy.BASELINE);
    }

    static Outcome estimate(float[][] raw, int width, int height, double[][] baseline, LongitudinalExecutionPolicy execution) {
        if (raw.length < 2 || raw.length != baseline.length) throw new IllegalArgumentException("Frame counts differ");
        float[][] features = LongitudinalPreparedFrames.prepare(raw, width, height, true, execution.featureWorkers,execution.selectMedian);
        int[] references = references(raw, width, height);
        int bright = references[0], dim = references[1];
        double cx = (width-1)/2.0, cy = (height-1)/2.0, diagonal = Math.hypot(width, height), radius = diagonal/2;
        double[][] inherited = new double[raw.length][];
        for (int i = 0; i < raw.length; i++) inherited[i] = matrix(baseline[i], cx, cy);
        List<String> pairs = new ArrayList<>();
        pairs.add("pair,seed,status,score,a00,a01,a02,a10,a11,a12");
        int filter = Math.max(1, (int) Math.rint(5.0 * Math.min(width, height)/512)) | 1;
        double[][] selected = new double[raw.length][];
        double[] brightScores = new double[raw.length], dimScores = new double[raw.length], agreements = new double[raw.length];
        List<List<String>> framePairs = new ArrayList<>();
        for (int i = 0; i < raw.length; i++) framePairs.add(new ArrayList<>());
        try (OpenCvLongitudinalOps.Spectrum brightSpectrum = spectrum(features[bright],width,height,execution.cacheSpectra);
             OpenCvLongitudinalOps.Spectrum dimSpectrum = spectrum(features[dim],width,height,execution.cacheSpectra && bright!=dim)) {
            OpenCvLongitudinalOps.Spectrum dimFourier = bright==dim ? brightSpectrum : dimSpectrum;
            Pose bridge = bright == dim ? new Pose(identity(), 1) : fit("bridge", features[bright], features[dim],
                    width, height, multiply(inherited[dim], inverse(inherited[bright])), filter, pairs,
                    brightSpectrum,dimFourier,execution.reuseBuffers,execution.reuseSmoothing);
            LongitudinalOrderedFrames.run(raw.length,execution.frameWorkers,true,i -> {
                try (OpenCvLongitudinalOps.Spectrum owned = spectrum(features[i],width,height,execution.cacheSpectra && i!=bright && i!=dim)) {
                    OpenCvLongitudinalOps.Spectrum moving = i==bright ? brightSpectrum : i==dim ? dimFourier : owned;
                    List<String> journal = framePairs.get(i);
                    Pose b = i == bright ? new Pose(identity(), 1) : fit("bright_"+i, features[bright], features[i],
                            width, height, multiply(inherited[i], inverse(inherited[bright])), filter, journal,
                            brightSpectrum,moving,execution.reuseBuffers,execution.reuseSmoothing);
                    Pose d = i == dim ? new Pose(identity(), 1) : fit("dim_"+i, features[dim], features[i],
                            width, height, multiply(inherited[i], inverse(inherited[dim])), filter, journal,
                            dimFourier,moving,execution.reuseBuffers,execution.reuseSmoothing);
                    double[] dimInBright = multiply(d.matrix, bridge.matrix);
                    double dimScore = Math.min(d.score, bridge.score);
                    selected[i] = b.score >= dimScore ? b.matrix : dimInBright;
                    brightScores[i] = b.score; dimScores[i] = dimScore;
                    agreements[i] = distance(pose(b.matrix, cx, cy, radius), pose(dimInBright, cx, cy, radius));
                }
            });
        }
        for (List<String> journal : framePairs) pairs.addAll(journal);
        double[] inverseZero = inverse(selected[0]);
        double[][] trajectory = new double[raw.length][3];
        double[] values = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            trajectory[i] = pose(multiply(selected[i], inverseZero), cx, cy, radius);
            values[i] = Math.max(brightScores[i], dimScores[i]);
        }
        double median = percentile(values, 50), mad = 1.4826 * percentile(deviations(values, median), 50);
        double floor = Math.max(.18, Math.min(.50, median-2.5*mad));
        double gapLimit = Math.max(1.5, .003*diagonal);
        double[] confidence = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            confidence[i] = Math.max(0, Math.min(1, (values[i]-floor)/Math.max(median-floor, .05)));
            if (agreements[i] > gapLimit && Math.abs(brightScores[i]-dimScores[i]) < .05) confidence[i] = 0;
        }
        // Retain the reference's centre-based rigid normalisation for baseline evidence.
        double[][] base = new double[baseline.length][3];
        double c = Math.cos(-baseline[0][2]), s = Math.sin(-baseline[0][2]);
        double ix = -(c*baseline[0][0]-s*baseline[0][1]), iy = -(s*baseline[0][0]+c*baseline[0][1]);
        for (int i = 0; i < base.length; i++) {
            c = Math.cos(baseline[i][2]); s = Math.sin(baseline[i][2]);
            base[i] = new double[]{c*ix-s*iy+baseline[i][0], s*ix+c*iy+baseline[i][1],
                    (baseline[i][2]-baseline[0][2])*radius};
        }
        protectTerminalEvents(trajectory, confidence, agreements, brightScores, dimScores, base, diagonal);
        return new Outcome(trajectory, confidence, bright, dim, pairs);
    }

    private static Pose fit(String label, float[] reference, float[] moving, int width, int height,
                            double[] inherited, int filter, List<String> journal,
                            OpenCvLongitudinalOps.Spectrum a, OpenCvLongitudinalOps.Spectrum b, boolean reuseBuffers, boolean reuseSmoothing) {
        List<double[]> candidates = new ArrayList<>();
        try {
            float[] phase = a!=null && b!=null ? OpenCvLongitudinalOps.phase(a,b,width,height)
                    : OpenCvLongitudinalOps.phase(reference, moving, width, height);
            double[] phaseMatrix = identity();
            for (int i = 0; i < 6; i++) phaseMatrix[i] = phase[i];
            candidates.add(phaseMatrix);
        } catch (RuntimeException ignored) { /* Reference also skips unavailable phase seeds. */ }
        candidates.add(inherited); candidates.add(identity());
        Set<String> seen = new HashSet<>();
        Pose winner = null;
        try (OpenCvLongitudinalOps.FitBuffer buffer = reuseBuffers ? new OpenCvLongitudinalOps.FitBuffer(reference,moving,width,height,reuseSmoothing) : null) {
        for (int candidate = 0; candidate < candidates.size(); candidate++) {
            double[] initial = candidates.get(candidate);
            long[] key = new long[6];
            float[] seed = new float[6];
            for (int i = 0; i < 6; i++) { key[i] = (long) Math.rint(initial[i]*1000); seed[i] = (float) initial[i]; }
            if (!seen.add(Arrays.toString(key))) continue;
            try {
                OpenCvLongitudinalOps.Fit result = buffer!=null ? buffer.fit(seed,filter)
                        : OpenCvLongitudinalOps.fit(reference, moving, width, height, seed, filter);
                double[] matrix = identity();
                for (int i = 0; i < 6; i++) matrix[i] = result.matrix[i];
                StringBuilder row = new StringBuilder(label+","+candidate+",ok,"+result.score);
                for (float value : result.matrix) row.append(',').append(value);
                journal.add(row.toString());
                if (winner == null || result.score > winner.score) winner = new Pose(matrix, result.score);
            } catch (RuntimeException failure) {
                journal.add(label+","+candidate+",failed,,,,,,,");
            }
        }
        }
        if (winner == null) throw new IllegalStateException("All bright/dim fits failed: " + label);
        return winner;
    }

    private static OpenCvLongitudinalOps.Spectrum spectrum(float[] image,int width,int height,boolean enabled) {
        if (!enabled) return null;
        try { return new OpenCvLongitudinalOps.Spectrum(image,width,height); }
        catch (RuntimeException unavailable) { return null; } // Keep the original phase-seed fallback.
    }

    private static int[] references(float[][] raw, int width, int height) {
        int x0 = (int) Math.rint(.12*width), x1 = (int) Math.rint(.88*width);
        int y0 = (int) Math.rint(.12*height), y1 = (int) Math.rint(.88*height);
        double[] light = new double[raw.length], structure = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            double[] centre = new double[(x1-x0)*(y1-y0)]; int j = 0;
            for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) centre[j++] = raw[i][y*width+x];
            Arrays.sort(centre);
            light[i] = sortedPercentile(centre, 75);
            structure[i] = sortedPercentile(centre, 95)-sortedPercentile(centre, 5);
        }
        double floor = Math.max(1e-6, .35*percentile(structure, 50));
        int usable = 0; for (double value : structure) if (value >= floor) usable++;
        int bright = -1, dim = -1;
        for (int i = 0; i < raw.length; i++) if (usable < 4 || structure[i] >= floor) {
            if (bright < 0 || light[i] > light[bright]) bright = i;
            if (dim < 0 || light[i] < light[dim]) dim = i;
        }
        return new int[]{bright, dim};
    }

    private static double[] identity() { return new double[]{1,0,0,0,1,0,0,0,1}; }
    private static double[] matrix(double[] pose, double cx, double cy) {
        double c = Math.cos(pose[2]), s = Math.sin(pose[2]);
        return new double[]{c,-s,cx-c*cx+s*cy+pose[0],s,c,cy-s*cx-c*cy+pose[1],0,0,1};
    }
    private static double[] pose(double[] a, double cx, double cy, double radius) {
        return new double[]{a[0]*cx+a[1]*cy+a[2]-cx, a[3]*cx+a[4]*cy+a[5]-cy,
                Math.atan2(a[3], a[0])*radius};
    }
    private static double[] multiply(double[] a, double[] b) {
        double[] result = new double[9];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)
            result[3*i+j] = (a[3*i]*b[j]+a[3*i+1]*b[3+j])+a[3*i+2]*b[6+j];
        return result;
    }
    private static double[] inverse(double[] a) {
        double determinant = a[0]*a[4]-a[1]*a[3];
        if (!Double.isFinite(determinant) || determinant == 0) throw new IllegalArgumentException("Singular matrix");
        double[] result = {a[4]/determinant, -a[1]/determinant, 0, -a[3]/determinant, a[0]/determinant, 0, 0,0,1};
        result[2] = -(result[0]*a[2]+result[1]*a[5]); result[5] = -(result[3]*a[2]+result[4]*a[5]);
        return result;
    }
    private static double percentile(double[] input, double q) {
        double[] values = input.clone(); Arrays.sort(values); return sortedPercentile(values, q);
    }
    private static double sortedPercentile(double[] values, double q) {
        double index = q/100*(values.length-1); int low = (int) index, high = Math.min(values.length-1, low+1);
        double f = index-low, difference = values[high]-values[low];
        return f >= .5 ? values[high]-difference*(1-f) : values[low]+difference*f;
    }
    private static double[] deviations(double[] values, double median) {
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) result[i] = Math.abs(values[i]-median);
        return result;
    }
    private static double distance(double[] a, double[] b) {
        double x=a[0]-b[0], y=a[1]-b[1], z=a[2]-b[2]; return Math.sqrt(x*x+y*y+z*z);
    }
    private static void protectTerminalEvents(double[][] t, double[] confidence, double[] agreement,
            double[] bright, double[] dim, double[][] baseline, double diagonal) {
        double gap = Math.max(1.5, .003*diagonal), threshold = Math.max(2.5, .006*diagonal);
        int tail = Math.max(2, Math.min(8, (int) Math.ceil(.04*t.length)));
        for (int i = Math.max(1,t.length-tail); i < t.length; i++) {
            if (confidence[i] > 0 || agreement[i] > gap || distance(t[i],t[i-1]) < threshold) continue;
            boolean two = bright[i] >= .18 && dim[i] >= .18;
            boolean matched = distance(baseline[i],baseline[i-1]) >= threshold
                    && distance(t[i],baseline[i]) <= Math.max(gap,.005*diagonal);
            if (!two && !matched) continue;
            if (matched && !two) {
                t[i] = baseline[i].clone();
                if (confidence[i-1] <= 0) t[i-1] = baseline[i-1].clone();
            }
            confidence[i] = 1e-6; confidence[i-1] = Math.max(confidence[i-1],1e-6);
        }
    }
}
