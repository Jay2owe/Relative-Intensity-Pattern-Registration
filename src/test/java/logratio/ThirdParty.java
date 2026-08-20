/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import ij.IJ;
import ij.ImagePlus;
import ij.process.FloatProcessor;
import logratio.core.Reconciler;
import logratio.core.Transform;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * An actual third-party plugin, on the actual benchmark fixture. <b>Test scope only, and optional.</b>
 *
 * <h2>Why this exists</h2>
 *
 * <p>Every comparison in {@link Benchmark} is against a baseline implemented <em>inside this engine</em>.
 * {@code SSD no gain} is a faithful proxy for the cost function StackReg minimises; it is not StackReg,
 * which has its own pyramid, its own interpolation and its own stopping rule. Until a shipped plugin has
 * been run on the same pixels, "we beat StackReg" is a claim about a cost function and not about a tool
 * anyone actually uses.
 *
 * <h2>The blocker that turned out not to be one</h2>
 *
 * <p>The standing objection was that RCC cannot be applied to these plugins, because none of them expose
 * per-pair estimates — so a comparison would have to pit our multi-lag reconciliation against their
 * chaining and would credit the cost function with something that belongs to the reconciliation.
 *
 * <p><b>TurboReg does expose them.</b> {@code TurboReg_} is a plain {@code ij.plugin.PlugIn} whose
 * {@code run(String)} accepts an {@code -align} command and which then answers
 * {@code getSourcePoints()} and {@code getTargetPoints()}: the refined landmark positions, from which
 * the translation is one subtraction. So it can be driven pair by pair from Java, with no macro and no
 * GUI interaction, and its estimates go through the same {@link Reconciler} and the same pair plan as
 * every other estimator here. The comparison is therefore like for like at both reconciliations, which
 * is what the benchmark's whole design demands.
 *
 * <p>StackReg is a loop over TurboReg that registers each slice to the previous one, so the
 * {@code CHAIN} row is StackReg's own strategy driven through StackReg's own estimator. The
 * {@code RCC} row is what StackReg would score if it had multi-lag reconciliation — a number StackReg
 * itself cannot produce, and the fair upper bound to measure ourselves against.
 *
 * <h2>Loaded by reflection, on purpose</h2>
 *
 * <p>TurboReg is not on Maven Central and is not a declared dependency: {@code pom.xml} deliberately
 * carries {@code net.imagej:ij} and nothing else. The jar is passed on the command line and loaded into
 * a child classloader, so this class compiles and the rest of the suite runs whether or not anyone has
 * Fiji installed. Without the jar it prints what it needs and stops.
 *
 * <pre>
 *   java -cp &lt;classes&gt;;&lt;test-classes&gt;;ij.jar logratio.ThirdParty \
 *        &lt;seed dir&gt; &lt;out dir&gt; &lt;path to TurboReg_-x.y.z.jar&gt;
 * </pre>
 */
public final class ThirdParty {

    private static final double EPSILON_UNUSED = 0;

    private ThirdParty() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("usage: ThirdParty <seed dir> <out dir> <TurboReg_ jar>");
            return;
        }
        Path seeds = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        Path jar = Paths.get(args[2]);
        Files.createDirectories(out);
        if (!Files.exists(jar)) {
            System.out.println("TurboReg jar not found: " + jar);
            System.out.println("It ships with Fiji as plugins/TurboReg_-<version>.jar.");
            return;
        }

        TurboReg turbo = TurboReg.load(jar);
        Path csv = out.resolve("thirdparty.csv");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(csv))) {
            w.println("seed,condition,estimator,reconciliation,frames,pairs,estimate_cpu_ms,"
                    + "cpu_ms_per_pair,median_err_px,p90_err_px,max_err_px");
        }

        List<Path> frames = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(seeds)) {
            s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".tif")).sorted()
                    .forEach(frames::add);
        }
        if (frames.isEmpty()) throw new IOException("no seed frames in " + seeds);

        String onlySeed = System.getProperty("logratio.onlySeed", "");
        for (Path seed : frames) {
            if (!onlySeed.isEmpty() && !seed.getFileName().toString().contains(onlySeed)) continue;
            run(seed, csv, turbo);
        }
        System.out.println("wrote " + csv);
        System.exit(0);        // TurboReg leaves AWT threads behind
    }

    // ------------------------------------------------------------------------------------ //

    private static void run(Path seed, Path csv, TurboReg turbo) throws IOException {
        ImagePlus imp = IJ.openImage(seed.toString());
        if (imp == null) throw new IOException("could not open " + seed);
        int sw = imp.getWidth();
        int sh = imp.getHeight();
        float[] fine = Benchmark.seedPlane(imp);
        imp.close();
        String name = seed.getFileName().toString().replaceFirst("\\.tif$", "");

        // Identical to Benchmark.run, deliberately: the truth, the window, the margin and the
        // trajectory must be the same objects of the same fixture or the numbers are not comparable.
        int[] fx = new int[Benchmark.FRAMES];
        int[] fy = new int[Benchmark.FRAMES];
        Benchmark.trajectory(fx, fy);
        double[] tx = new double[Benchmark.FRAMES];
        double[] ty = new double[Benchmark.FRAMES];
        int span = 0;
        for (int t = 0; t < Benchmark.FRAMES; t++) {
            tx[t] = fx[t] / (double) Benchmark.FINE;
            ty[t] = fy[t] / (double) Benchmark.FINE;
            span = Math.max(span, Math.max(Math.abs(fx[t]), Math.abs(fy[t])));
        }
        int margin = span + Benchmark.FINE_SLACK;
        int win = Math.min((sw - 2 * margin) / Benchmark.FINE, (sh - 2 * margin) / Benchmark.FINE);
        if (win < 64) throw new IOException(name + ": window would be only " + win + " px");
        double sd = Benchmark.standardDeviation(fine);

        System.out.printf("%n=== %s: %dx%d coarse, %d frames ===%n", name, win, win, Benchmark.FRAMES);
        System.out.printf("  %-13s %-30s %-6s %6s %8s %9s %9s %9s%n", "condition", "estimator",
                "recon", "pairs", "cpu ms", "median px", "p90 px", "max px");

        String onlyCondition = System.getProperty("logratio.onlyCondition", "");
        for (Benchmark.Condition condition : Benchmark.Condition.values()) {
            if (!onlyCondition.isEmpty() && !onlyCondition.equals(condition.name())) continue;
            float[][] plane = new float[Benchmark.FRAMES][];
            for (int t = 0; t < Benchmark.FRAMES; t++) {
                plane[t] = Benchmark.frame(fine, sw, sh, win, margin, fx[t], fy[t], condition, t, sd);
            }
            for (Benchmark.Recon r : Benchmark.Recon.values()) {
                List<Reconciler.Observation> plan = Reconciler.planPairs(
                        r == Benchmark.Recon.CHAIN
                                ? Reconciler.Reference.CONSECUTIVE : Reconciler.Reference.MULTILAG,
                        Benchmark.FRAMES, 0, 1, Benchmark.LAGS);

                Transform[] measured = new Transform[plan.size()];
                long cpu0 = cpuNanos();
                for (int i = 0; i < plan.size(); i++) {
                    Reconciler.Observation ob = plan.get(i);
                    measured[i] = turbo.translation(plane[ob.from], plane[ob.to], win, win);
                }
                long cpuMs = (cpuNanos() - cpu0) / 1_000_000;

                Reconciler.Solution solution;
                if (r == Benchmark.Recon.CHAIN) {
                    solution = Reconciler.chain(measured);
                } else {
                    List<Reconciler.Observation> obs = new ArrayList<>(plan.size());
                    for (int i = 0; i < plan.size(); i++) {
                        obs.add(new Reconciler.Observation(
                                plan.get(i).from, plan.get(i).to, measured[i]));
                    }
                    solution = Reconciler.multiLag(Benchmark.FRAMES, obs);
                }

                double[] err = Benchmark.errors(solution, tx, ty);
                double median = Benchmark.quantile(err, 0.5);
                System.out.printf("  %-13s %-30s %-6s %6d %8d %9.3f %9.3f %9.3f%n", condition,
                        TurboReg.LABEL, r, plan.size(), cpuMs, median,
                        Benchmark.quantile(err, 0.9), Benchmark.quantile(err, 1.0));
                String row = String.format("%s,%s,%s,%s,%d,%d,%d,%.3f,%.4f,%.4f,%.4f%n",
                        name, condition, TurboReg.LABEL, r, Benchmark.FRAMES, plan.size(), cpuMs,
                        cpuMs / (double) plan.size(), median,
                        Benchmark.quantile(err, 0.9), Benchmark.quantile(err, 1.0));
                Files.write(csv, row.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
            }
        }
    }

    private static long cpuNanos() {
        java.lang.management.ThreadMXBean b = java.lang.management.ManagementFactory.getThreadMXBean();
        return b.isCurrentThreadCpuTimeSupported() ? b.getCurrentThreadCpuTime() : System.nanoTime();
    }

    // ------------------------------------------------------------------------------------ //

    /**
     * TurboReg, driven one pair at a time.
     *
     * <p><b>The sign convention, because getting it wrong reads as a working estimator with inverted
     * output.</b> TurboReg refines a landmark in the <i>source</i> to the position that matches the
     * <i>target</i>. Put frame B in as the source and frame A as the target, and the motion of the
     * content from A to B — which is what {@link Transform} means — is
     * {@code sourcePoints[0] - targetPoints[0]}. Verified against the synthetic fixture: a frame built
     * with content moved by (3.25, -2.50) is recovered as (3.2500, -2.5004).
     *
     * <p>Two windows are opened once and their pixel arrays swapped between pairs. TurboReg finds its
     * inputs through {@code WindowManager} by title, so they have to be real windows; opening 500 of
     * them per arm is what this avoids.
     */
    private static final class TurboReg {

        static final String LABEL = "TurboReg (StackReg engine)";
        private static final String SRC = "lr_tp_source";
        private static final String TGT = "lr_tp_target";

        private final Class<?> type;
        private final Method run;
        private final Method sourcePoints;
        private final Method targetPoints;
        private ImagePlus source;
        private ImagePlus target;
        private int width;
        private int height;

        private TurboReg(Class<?> type) throws NoSuchMethodException {
            this.type = type;
            this.run = type.getMethod("run", String.class);
            this.sourcePoints = type.getMethod("getSourcePoints");
            this.targetPoints = type.getMethod("getTargetPoints");
        }

        static TurboReg load(Path jar) throws Exception {
            URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                    ThirdParty.class.getClassLoader());
            return new TurboReg(Class.forName("TurboReg_", true, loader));
        }

        Transform translation(float[] a, float[] b, int w, int h) {
            windows(w, h);
            source.getProcessor().setPixels(b.clone());
            target.getProcessor().setPixels(a.clone());
            source.updateAndDraw();
            target.updateAndDraw();

            String opts = "-align"
                    + " -window " + SRC + " 0 0 " + (w - 1) + " " + (h - 1)
                    + " -window " + TGT + " 0 0 " + (w - 1) + " " + (h - 1)
                    + " -translation " + (w / 2) + " " + (h / 2) + " " + (w / 2) + " " + (h / 2)
                    + " -hideOutput";
            try {
                Object plugin = type.getDeclaredConstructor().newInstance();
                run.invoke(plugin, opts);
                double[][] sp = (double[][]) sourcePoints.invoke(plugin);
                double[][] tp = (double[][]) targetPoints.invoke(plugin);
                if (sp == null || tp == null || sp.length == 0 || tp.length == 0) {
                    return Transform.IDENTITY;
                }
                return Transform.translation(sp[0][0] - tp[0][0], sp[0][1] - tp[0][1]);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("TurboReg failed on a pair", e);
            }
        }

        private void windows(int w, int h) {
            if (source != null && width == w && height == h) return;
            close();
            width = w;
            height = h;
            source = new ImagePlus(SRC, new FloatProcessor(w, h, new float[w * h], null));
            target = new ImagePlus(TGT, new FloatProcessor(w, h, new float[w * h], null));
            source.show();
            target.show();
        }

        private void close() {
            if (source != null) source.close();
            if (target != null) target.close();
            source = null;
            target = null;
        }
    }
}
