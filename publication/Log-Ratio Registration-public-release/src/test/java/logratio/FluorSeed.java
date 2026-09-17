package logratio;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ColorProcessor;
import logratio.core.LogPlane;

/**
 * Builds a synthetic fluorescence-shaped seed from a phase-contrast one, and reports where the
 * log-domain gradient lives in each.
 *
 * The spatial structure is IDENTICAL between the two -- only the intensity histogram changes. That is
 * the whole point: it isolates "does a fluorescence histogram invert the finding" from "does this
 * particular sample invert the finding", which a real fluorescence recording cannot separate.
 *
 * The remap is max(0, I - p) for a percentile p: background collapses to zero, structure survives as
 * sparse bright objects. That is the shape of a fluorescence frame -- a huge near-zero mode and a
 * sparse bright mode -- without changing where anything is.
 *
 * Written back as an RGB composite with the data in the blue channel, because the historical fixture
 * reads channel 3 of a ColorProcessor.
 */
public class FluorSeed {

    public static void main(String[] args) throws Exception {
        String src = args[0];
        double cut = Double.parseDouble(args[1]);
        String dst = args.length > 2 ? args[2] : null;

        ImagePlus imp = IJ.openImage(src);
        int w = imp.getWidth(), h = imp.getHeight();
        float[] phase = Benchmark.seedPlane(imp);
        imp.close();

        float bg = pct(phase, cut);
        float[] fluor = new float[phase.length];
        float max = 0;
        for (int i = 0; i < phase.length; i++) {
            fluor[i] = Math.max(0f, phase[i] - bg);
            max = Math.max(max, fluor[i]);
        }
        // Rescale to a realistic 8-bit-ish fluorescence range so epsilon = 1 means the same thing it
        // means for the phase seeds relative to the frame's own dynamic range.
        float scale = 200f / Math.max(1f, max);
        int zeros = 0;
        for (int i = 0; i < fluor.length; i++) {
            fluor[i] *= scale;
            if (fluor[i] <= 0) zeros++;
        }
        System.out.printf("%nremap: subtract p%.0f (%.1f), rescale x%.3f -> background is %.1f%% exact zero%n",
                cut, bg, scale, 100.0 * zeros / fluor.length);

        deciles("PHASE CONTRAST (original)", phase, w, h);
        deciles("FLUORESCENCE-SHAPED (same pixels, remapped)", fluor, w, h);

        if (dst != null) {
            ColorProcessor cp = new ColorProcessor(w, h);
            byte[] b = new byte[w * h];
            for (int i = 0; i < b.length; i++) {
                b[i] = (byte) Math.max(0, Math.min(255, Math.round(fluor[i])));
            }
            cp.setChannel(3, new ij.process.ByteProcessor(w, h, b, null));
            IJ.saveAsTiff(new ImagePlus("fluor", cp), dst);
            System.out.println("wrote " + dst);
        }
    }

    private static void deciles(String label, float[] plane, int w, int h) {
        LogPlane lp = LogPlane.of(plane, w, h, 1.0);
        float[] s = plane.clone();
        java.util.Arrays.sort(s);
        double[] edge = new double[11];
        for (int d = 0; d <= 10; d++) {
            edge[d] = s[Math.min(s.length - 1, (int) Math.round(d / 10.0 * (s.length - 1)))];
        }
        double[] sum = new double[10];
        int[] count = new int[10];
        double total = 0;
        for (int i = 0; i < plane.length; i++) {
            int d = 9;
            for (int k = 0; k < 10; k++) {
                if (plane[i] <= edge[k + 1]) { d = k; break; }
            }
            double g = lp.gradMagnitude(i);
            sum[d] += g; count[d]++; total += g;
        }
        System.out.printf("%n  %s%n", label);
        System.out.printf("  %-10s %14s %14s %16s%n", "decile", "intensity", "mean |grad|", "share of total");
        for (int d = 0; d < 10; d++) {
            System.out.printf("  %-10s %6.0f-%-7.0f %14.5f %15.1f%%%n",
                    (d * 10) + "-" + (d * 10 + 10) + "%", edge[d], edge[d + 1],
                    count[d] > 0 ? sum[d] / count[d] : 0, 100 * sum[d] / total);
        }
        double bottom = 0, top = 0;
        for (int d = 0; d < 3; d++) bottom += sum[d];
        for (int d = 7; d < 10; d++) top += sum[d];
        System.out.printf("  dimmest 30%% carry %.1f%%; brightest 30%% carry %.1f%%%n",
                100 * bottom / total, 100 * top / total);
    }

    private static float pct(float[] a, double q) {
        float[] s = a.clone();
        java.util.Arrays.sort(s);
        return s[(int) Math.round(q / 100.0 * (s.length - 1))];
    }
}
