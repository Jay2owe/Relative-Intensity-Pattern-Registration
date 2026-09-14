/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ij.IJ;
import ij.ImagePlus;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ripr.core.Reconciler;
import ripr.core.Transform;
import ripr.core.Warper;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs installed third-party Fiji registration engines on exactly the stacks written by
 * {@link BenchmarkComparisonStacks}.
 *
 * <p>The plugin jars remain optional: all access is reflective, so the ordinary test suite still has
 * only ImageJ 1.x as a dependency.  The generated TIFFs use the benchmark's common nearest-neighbour
 * correction step.  That deliberately compares displacement estimation rather than allowing each
 * plugin's interpolation to sharpen or blur its score.
 */
public final class ExternalPluginComparisonStacks {

    private static final int[] LAGS = {1, 2, 4, 8, 16};
    private static final int RCC_PAIRS = 209;
    private static final String CSV_MARKER = "21_real_stackreg_turboreg_translation_chain";
    private static final String README_START = "\nEXTERNAL FIJI METHODS (installed plugins)\n";
    private static final String README_END = "END EXTERNAL FIJI METHODS\n";
    private static final long EXTERNAL_RANDOM_SEED = 69997L;

    /**
     * Every algorithm-affecting value used by the reflective adapters, in one printable object.
     * Defaults are the installed plugins' own defaults, recovered from their constructors, dialogs,
     * and direct call sites during Stage 0 of docs/external_parameter_sweep_plan.md.
     */
    static final class ExternalSettings {
        final String turboRegTransformation;
        final int stabilizerPyramidLevel;
        final double stabilizerTemplateUpdate;
        final int stabilizerIterations;
        final double stabilizerTolerance;
        final int fast4dPeakMethod;
        final boolean fast4dNormalizeCorrelation;
        final int correct3dPeaks;
        final boolean correct3dVerifyPeaks;
        final float siftInitialSigma;
        final int siftSteps;
        final int siftMinOctave;
        final int siftMaxOctave;
        final int siftDescriptorSize;
        final int siftDescriptorBins;
        final float siftRod;
        final int siftRansacIterations;
        final double siftMaxEpsilon;
        final double siftMinInlierRatio;
        final int siftMinInliers;
        final String siftModel;
        final int descriptorBrightestPoints;
        final double descriptorSigma1;
        final double descriptorSigma2;
        final double descriptorThreshold;
        final boolean descriptorMaxima;
        final boolean descriptorMinima;
        final String descriptorModel;
        final int descriptorDimensionality;
        final int descriptorLocalization;
        final boolean descriptorSimilarOrientation;
        final int descriptorNeighbors;
        final int descriptorRedundancy;
        final double descriptorSignificance;
        final double descriptorRansacThreshold;
        final int descriptorChannel1;
        final int descriptorChannel2;
        final boolean descriptorRegularize;
        final boolean descriptorFixFirstTile;
        final int descriptorGlobalOptimization;
        final int descriptorRange;
        final boolean descriptorSetPointRois;
        final boolean descriptorSilent;
        final int descriptorFuse;
        final int descriptorInterpolation;

        ExternalSettings(String turboRegTransformation, int stabilizerPyramidLevel,
                         double stabilizerTemplateUpdate, int stabilizerIterations,
                         double stabilizerTolerance, int fast4dPeakMethod, int correct3dPeaks,
                         float siftInitialSigma, int siftSteps, int siftMinOctave,
                         int siftMaxOctave, int siftDescriptorSize, int siftDescriptorBins,
                         float siftRod, int siftRansacIterations, double siftMaxEpsilon,
                         double siftMinInlierRatio, int siftMinInliers, String siftModel,
                         int descriptorBrightestPoints, double descriptorSigma1,
                         double descriptorSigma2, double descriptorThreshold,
                         boolean descriptorMaxima, boolean descriptorMinima,
                         String descriptorModel) {
            this.turboRegTransformation = turboRegTransformation;
            this.stabilizerPyramidLevel = stabilizerPyramidLevel;
            this.stabilizerTemplateUpdate = stabilizerTemplateUpdate;
            this.stabilizerIterations = stabilizerIterations;
            this.stabilizerTolerance = stabilizerTolerance;
            this.fast4dPeakMethod = fast4dPeakMethod;
            this.fast4dNormalizeCorrelation = true;
            this.correct3dPeaks = correct3dPeaks;
            this.correct3dVerifyPeaks = true;
            this.siftInitialSigma = siftInitialSigma;
            this.siftSteps = siftSteps;
            this.siftMinOctave = siftMinOctave;
            this.siftMaxOctave = siftMaxOctave;
            this.siftDescriptorSize = siftDescriptorSize;
            this.siftDescriptorBins = siftDescriptorBins;
            this.siftRod = siftRod;
            this.siftRansacIterations = siftRansacIterations;
            this.siftMaxEpsilon = siftMaxEpsilon;
            this.siftMinInlierRatio = siftMinInlierRatio;
            this.siftMinInliers = siftMinInliers;
            this.siftModel = siftModel;
            this.descriptorBrightestPoints = descriptorBrightestPoints;
            this.descriptorSigma1 = descriptorSigma1;
            this.descriptorSigma2 = descriptorSigma2;
            this.descriptorThreshold = descriptorThreshold;
            this.descriptorMaxima = descriptorMaxima;
            this.descriptorMinima = descriptorMinima;
            this.descriptorModel = descriptorModel;
            this.descriptorDimensionality = 2;
            this.descriptorLocalization = 1;
            this.descriptorSimilarOrientation = false;
            this.descriptorNeighbors = 3;
            this.descriptorRedundancy = 1;
            this.descriptorSignificance = 3.0;
            this.descriptorRansacThreshold = 5.0;
            this.descriptorChannel1 = 0;
            this.descriptorChannel2 = -1;
            this.descriptorRegularize = false;
            this.descriptorFixFirstTile = true;
            this.descriptorGlobalOptimization = 1;
            this.descriptorRange = 5;
            this.descriptorSetPointRois = false;
            this.descriptorSilent = true;
            this.descriptorFuse = 2;
            this.descriptorInterpolation = 0;
        }

        static ExternalSettings defaults() {
            return new ExternalSettings(
                    "rigid", 1, 0.9, 200, 1e-7, 2, 5,
                    1.6f, 3, 64, 1024, 8, 8, 0.92f, 1000, 25.0, 0.05, 2,
                    "rigid", 0, 2.0, 3.2, 0.03, true, false, "rigid");
        }

        ExternalSettings withTurboReg(String value) {
            return new ExternalSettings(value, stabilizerPyramidLevel, stabilizerTemplateUpdate,
                    stabilizerIterations, stabilizerTolerance, fast4dPeakMethod, correct3dPeaks,
                    siftInitialSigma, siftSteps, siftMinOctave, siftMaxOctave,
                    siftDescriptorSize, siftDescriptorBins, siftRod, siftRansacIterations,
                    siftMaxEpsilon, siftMinInlierRatio, siftMinInliers, siftModel,
                    descriptorBrightestPoints, descriptorSigma1, descriptorSigma2,
                    descriptorThreshold, descriptorMaxima, descriptorMinima, descriptorModel);
        }

        ExternalSettings withStabilizer(int level, double update) {
            return new ExternalSettings(turboRegTransformation, level, update,
                    stabilizerIterations, stabilizerTolerance, fast4dPeakMethod, correct3dPeaks,
                    siftInitialSigma, siftSteps, siftMinOctave, siftMaxOctave,
                    siftDescriptorSize, siftDescriptorBins, siftRod, siftRansacIterations,
                    siftMaxEpsilon, siftMinInlierRatio, siftMinInliers, siftModel,
                    descriptorBrightestPoints, descriptorSigma1, descriptorSigma2,
                    descriptorThreshold, descriptorMaxima, descriptorMinima, descriptorModel);
        }

        ExternalSettings withFast4dPeakMethod(int value) {
            return new ExternalSettings(turboRegTransformation, stabilizerPyramidLevel,
                    stabilizerTemplateUpdate, stabilizerIterations, stabilizerTolerance, value,
                    correct3dPeaks, siftInitialSigma, siftSteps, siftMinOctave, siftMaxOctave,
                    siftDescriptorSize, siftDescriptorBins, siftRod, siftRansacIterations,
                    siftMaxEpsilon, siftMinInlierRatio, siftMinInliers, siftModel,
                    descriptorBrightestPoints, descriptorSigma1, descriptorSigma2,
                    descriptorThreshold, descriptorMaxima, descriptorMinima, descriptorModel);
        }

        ExternalSettings withCorrect3dPeaks(int value) {
            return new ExternalSettings(turboRegTransformation, stabilizerPyramidLevel,
                    stabilizerTemplateUpdate, stabilizerIterations, stabilizerTolerance,
                    fast4dPeakMethod, value, siftInitialSigma, siftSteps, siftMinOctave,
                    siftMaxOctave, siftDescriptorSize, siftDescriptorBins, siftRod,
                    siftRansacIterations, siftMaxEpsilon, siftMinInlierRatio, siftMinInliers,
                    siftModel, descriptorBrightestPoints, descriptorSigma1, descriptorSigma2,
                    descriptorThreshold, descriptorMaxima, descriptorMinima, descriptorModel);
        }

        ExternalSettings withSift(double epsilon, int inliers, int minOctave) {
            return new ExternalSettings(turboRegTransformation, stabilizerPyramidLevel,
                    stabilizerTemplateUpdate, stabilizerIterations, stabilizerTolerance,
                    fast4dPeakMethod, correct3dPeaks, siftInitialSigma, siftSteps, minOctave,
                    siftMaxOctave, siftDescriptorSize, siftDescriptorBins, siftRod,
                    siftRansacIterations, epsilon, siftMinInlierRatio, inliers, siftModel,
                    descriptorBrightestPoints, descriptorSigma1, descriptorSigma2,
                    descriptorThreshold, descriptorMaxima, descriptorMinima, descriptorModel);
        }

        ExternalSettings withSiftModel(String value) {
            return new ExternalSettings(turboRegTransformation, stabilizerPyramidLevel,
                    stabilizerTemplateUpdate, stabilizerIterations, stabilizerTolerance,
                    fast4dPeakMethod, correct3dPeaks, siftInitialSigma, siftSteps, siftMinOctave,
                    siftMaxOctave, siftDescriptorSize, siftDescriptorBins, siftRod,
                    siftRansacIterations, siftMaxEpsilon, siftMinInlierRatio, siftMinInliers, value,
                    descriptorBrightestPoints, descriptorSigma1, descriptorSigma2,
                    descriptorThreshold, descriptorMaxima, descriptorMinima, descriptorModel);
        }

        ExternalSettings withDescriptorThreshold(double value) {
            return new ExternalSettings(turboRegTransformation, stabilizerPyramidLevel,
                    stabilizerTemplateUpdate, stabilizerIterations, stabilizerTolerance,
                    fast4dPeakMethod, correct3dPeaks, siftInitialSigma, siftSteps,
                    siftMinOctave, siftMaxOctave, siftDescriptorSize, siftDescriptorBins,
                    siftRod, siftRansacIterations, siftMaxEpsilon, siftMinInlierRatio,
                    siftMinInliers, siftModel, descriptorBrightestPoints, descriptorSigma1,
                    descriptorSigma2, value, descriptorMaxima, descriptorMinima,
                    descriptorModel);
        }

        ExternalSettings withDescriptorModel(String value) {
            return new ExternalSettings(turboRegTransformation, stabilizerPyramidLevel,
                    stabilizerTemplateUpdate, stabilizerIterations, stabilizerTolerance,
                    fast4dPeakMethod, correct3dPeaks, siftInitialSigma, siftSteps,
                    siftMinOctave, siftMaxOctave, siftDescriptorSize, siftDescriptorBins,
                    siftRod, siftRansacIterations, siftMaxEpsilon, siftMinInlierRatio,
                    siftMinInliers, siftModel, descriptorBrightestPoints, descriptorSigma1,
                    descriptorSigma2, descriptorThreshold, descriptorMaxima, descriptorMinima,
                    value);
        }

        String describe() {
            return "TurboReg model=" + turboRegTransformation
                    + "; Image Stabilizer pyramid=" + stabilizerPyramidLevel
                    + ", update=" + stabilizerTemplateUpdate + ", iterations="
                    + stabilizerIterations + ", tolerance=" + stabilizerTolerance
                    + "; Fast4DReg peak method=" + fast4dPeakMethod
                    + ", normalized correlation=" + fast4dNormalizeCorrelation
                    + "; Correct3D peaks=" + correct3dPeaks
                    + ", verify peaks=" + correct3dVerifyPeaks
                    + "; SIFT model=" + siftModel + ", sigma=" + siftInitialSigma
                    + ", steps=" + siftSteps + ", octave=" + siftMinOctave + ".."
                    + siftMaxOctave + ", descriptor=" + siftDescriptorSize + "x"
                    + siftDescriptorBins + ", rod=" + siftRod + ", RANSAC="
                    + siftRansacIterations + "/" + siftMaxEpsilon + "/"
                    + siftMinInlierRatio + "/" + siftMinInliers
                    + "; descriptor model=" + descriptorModel + ", brightest="
                    + descriptorBrightestPoints + ", sigma=" + descriptorSigma1 + "/"
                    + descriptorSigma2 + ", threshold=" + descriptorThreshold
                    + ", maxima=" + descriptorMaxima + ", minima=" + descriptorMinima
                    + ", localization=" + descriptorLocalization + ", neighbors="
                    + descriptorNeighbors + ", redundancy=" + descriptorRedundancy
                    + ", significance=" + descriptorSignificance + ", RANSAC="
                    + descriptorRansacThreshold + ", regularize=" + descriptorRegularize
                    + ", fix first=" + descriptorFixFirstTile + ", global optimization="
                    + descriptorGlobalOptimization + ", range=" + descriptorRange
                    + "; reproducibility seed=" + EXTERNAL_RANDOM_SEED;
        }
    }

    static final class SweepConfig {
        final String id;
        final String engine;
        final String description;
        final ExternalSettings settings;

        SweepConfig(String id, String engine, String description, ExternalSettings settings) {
            this.id = id;
            this.engine = engine;
            this.description = description;
            this.settings = settings;
        }
    }

    private interface PairEstimator {
        Transform estimate(float[] from, float[] to, int width);
        void warmup(float[] from, float[] to, int width);
    }

    private interface Solver {
        Reconciler.Solution solve(float[][] frames, int width);
        void warmup(float[][] frames, int width);
    }

    private static final class ExternalMethod {
        final String stem;
        final String family;
        final String label;
        final String strategy;
        final int pairs;
        final Solver solver;
        final String aliasOf;

        ExternalMethod(String stem, String family, String label, String strategy,
                       int pairs, Solver solver) {
            this(stem, family, label, strategy, pairs, solver, null);
        }

        ExternalMethod(String stem, String family, String label, String strategy,
                       int pairs, Solver solver, String aliasOf) {
            this.stem = stem;
            this.family = family;
            this.label = label;
            this.strategy = strategy;
            this.pairs = pairs;
            this.solver = solver;
            this.aliasOf = aliasOf;
        }
    }

    private static final class Result {
        final ExternalMethod method;
        final Reconciler.Solution solution;
        final double cpuMs;
        final double elapsedMs;
        final double median;
        final double p90;
        final double maximum;
        final String failure;
        final String metricFailure;

        Result(ExternalMethod method, Reconciler.Solution solution, double cpuMs,
               double elapsedMs, double median, double p90, double maximum) {
            this(method, solution, cpuMs, elapsedMs, median, p90, maximum, null, null);
        }

        Result(ExternalMethod method, Reconciler.Solution solution, double cpuMs,
               double elapsedMs, double median, double p90, double maximum, String failure) {
            this(method, solution, cpuMs, elapsedMs, median, p90, maximum, failure, null);
        }

        Result(ExternalMethod method, Reconciler.Solution solution, double cpuMs,
               double elapsedMs, double median, double p90, double maximum, String failure,
               String metricFailure) {
            this.method = method;
            this.solution = solution;
            this.cpuMs = cpuMs;
            this.elapsedMs = elapsedMs;
            this.median = median;
            this.p90 = p90;
            this.maximum = maximum;
            this.failure = failure;
            this.metricFailure = metricFailure;
        }
    }

    private ExternalPluginComparisonStacks() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--self-test".equals(args[0])) {
            selfTest();
            System.exit(0);
        }
        if (args.length == 1 && "--list-sweep-configs".equals(args[0])) {
            for (SweepConfig config : sweepConfigs()) {
                System.out.println(config.id + "\t" + config.engine + "\t" + config.description);
            }
            System.exit(0);
        }
        if (args.length == 4 && "--sweep".equals(args[0])) {
            runSweep(Paths.get(args[1]).toAbsolutePath().normalize(),
                    Paths.get(args[2]).toAbsolutePath().normalize(), args[3]);
            System.exit(0);
        }
        if (args.length == 1 && "--probe-signs".equals(args[0])) {
            probeSigns();
            System.exit(0);
        }
        if (args.length == 2 && "--v2".equals(args[0])) {
            runVersion2(Paths.get(args[1]).toAbsolutePath().normalize());
            System.exit(0);
        }
        if (args.length == 2 && "--v2-native".equals(args[0])) {
            runNativeVersion2(Paths.get(args[1]).toAbsolutePath().normalize());
            System.exit(0);
        }
        if (args.length == 2 && "--existing".equals(args[0])) {
            runExisting(Paths.get(args[1]).toAbsolutePath().normalize());
            System.exit(0);
        }
        if (args.length < 2) {
            System.out.println("usage: ExternalPluginComparisonStacks <seed dir> <stack output dir>");
            return;
        }
        Path seeds = Paths.get(args[0]);
        Path out = Paths.get(args[1]);

        TurboRegEstimator turbo = new TurboRegEstimator();
        ImageStabilizerSolver stabilizer = new ImageStabilizerSolver();
        Fast4DRegEstimator fast4d = new Fast4DRegEstimator();
        Correct3DDriftEstimator correct3d = new Correct3DDriftEstimator();
        SiftEstimator sift = new SiftEstimator();
        DescriptorSeriesSolver descriptor = new DescriptorSeriesSolver();

        List<ExternalMethod> methods = externalMethods(
                turbo, stabilizer, fast4d, correct3d, sift, descriptor);

        Map<String, Benchmark.Condition> selected = new LinkedHashMap<>();
        selected.put("SYNTH_FLUOR_B6", Benchmark.Condition.CLEAN);
        selected.put("VID47_D3_1_09d20h00m", Benchmark.Condition.GAIN_FADE);
        selected.put("VID52_C3_1_02d00h00m", Benchmark.Condition.CHANGE_MOVED);

        for (Map.Entry<String, Benchmark.Condition> entry : selected.entrySet()) {
            Path seed = seeds.resolve(entry.getKey() + ".tif");
            if (!Files.exists(seed)) {
                Path control = seeds.getParent().resolve("seeds_control").resolve(
                        entry.getKey() + ".tif");
                seed = Files.exists(control) ? control : seed;
            }
            runRecording(seed, entry.getValue(), out, methods);
        }
        turbo.close();
        System.out.println("External Fiji comparison complete.");
        System.exit(0); // TurboReg opens two AWT image windows by design.
    }

    static List<SweepConfig> sweepConfigs() {
        ExternalSettings d = ExternalSettings.defaults();
        return Arrays.asList(
                new SweepConfig("all_defaults", "all", "installed defaults", d),
                new SweepConfig("all_defaults_replay", "all",
                        "independent replay of installed defaults", d),
                new SweepConfig("turboreg_default_rigid", "turboreg",
                        "installed rigid-body default", d),
                new SweepConfig("turboreg_translation", "turboreg",
                        "translation model", d.withTurboReg("translation")),
                new SweepConfig("stabilizer_default", "stabilizer",
                        "maximum pyramid level 1; rolling template update 0.9", d),
                new SweepConfig("stabilizer_pyramid_2", "stabilizer",
                        "maximum pyramid level 2", d.withStabilizer(2, 0.9)),
                new SweepConfig("stabilizer_pyramid_3", "stabilizer",
                        "maximum pyramid level 3", d.withStabilizer(3, 0.9)),
                new SweepConfig("stabilizer_pyramid_4", "stabilizer",
                        "maximum pyramid level 4", d.withStabilizer(4, 0.9)),
                new SweepConfig("stabilizer_pinned_first", "stabilizer",
                        "default pyramid; template pinned to first frame",
                        d.withStabilizer(1, 1.0)),
                new SweepConfig("fast4d_peak_optimized_default", "fast4d",
                        "optimized sub-pixel peak finder (installed value 2)", d),
                new SweepConfig("fast4d_peak_centroid", "fast4d",
                        "centroid peak finder (value 0)", d.withFast4dPeakMethod(0)),
                new SweepConfig("fast4d_peak_pixel", "fast4d",
                        "maximum-pixel peak finder (value 1)", d.withFast4dPeakMethod(1)),
                new SweepConfig("correct3d_peaks_5_default", "correct3d",
                        "five phase-correlation peaks", d),
                new SweepConfig("correct3d_peaks_10", "correct3d",
                        "ten phase-correlation peaks", d.withCorrect3dPeaks(10)),
                new SweepConfig("sift_default", "sift",
                        "installed Register Virtual Stack rigid defaults", d),
                new SweepConfig("sift_translation", "sift",
                        "translation model with installed numeric defaults",
                        d.withSiftModel("translation")),
                new SweepConfig("sift_epsilon_1", "sift", "RANSAC max epsilon 1 px",
                        d.withSift(1.0, 2, 64)),
                new SweepConfig("sift_epsilon_3", "sift", "RANSAC max epsilon 3 px",
                        d.withSift(3.0, 2, 64)),
                new SweepConfig("sift_epsilon_10", "sift", "RANSAC max epsilon 10 px",
                        d.withSift(10.0, 2, 64)),
                new SweepConfig("sift_min_inliers_4", "sift", "minimum four inliers",
                        d.withSift(25.0, 4, 64)),
                new SweepConfig("sift_min_inliers_8", "sift", "minimum eight inliers",
                        d.withSift(25.0, 8, 64)),
                new SweepConfig("sift_finer_octave", "sift", "minimum octave size 32 px",
                        d.withSift(25.0, 2, 32)),
                new SweepConfig("descriptor_threshold_0_008", "descriptor",
                        "low detection threshold 0.008", d.withDescriptorThreshold(0.008)),
                new SweepConfig("descriptor_translation", "descriptor",
                        "translation model with installed numeric defaults",
                        d.withDescriptorModel("translation")),
                new SweepConfig("descriptor_threshold_0_03_default", "descriptor",
                        "installed numeric detection threshold 0.03", d),
                new SweepConfig("descriptor_threshold_0_1", "descriptor",
                        "strong detection threshold 0.1", d.withDescriptorThreshold(0.1)));
    }

    private static SweepConfig sweepConfig(String id) {
        for (SweepConfig config : sweepConfigs()) if (config.id.equals(id)) return config;
        throw new IllegalArgumentException("unknown sweep configuration " + id);
    }

    /**
     * Keep only the engines named in {@code -Dripr.onlyExternalMethod}, a comma-separated list of
     * substrings matched against the file stem. Empty means every engine, which is the default.
     *
     * <p>An engine that only reuses another's run is dropped when the run it reuses is not selected,
     * because there would be nothing to copy from.
     */
    private static List<ExternalMethod> requestedOnly(List<ExternalMethod> methods) {
        List<String> wanted = new ArrayList<>();
        for (String value : System.getProperty("ripr.onlyExternalMethod", "").split(",")) {
            if (!value.trim().isEmpty()) wanted.add(value.trim());
        }
        if (wanted.isEmpty()) return methods;
        List<ExternalMethod> kept = new ArrayList<>();
        Set<String> stems = new HashSet<>();
        for (ExternalMethod method : methods) {
            for (String one : wanted) {
                if (method.stem.contains(one)) {
                    kept.add(method);
                    stems.add(method.stem);
                    break;
                }
            }
        }
        List<ExternalMethod> out = new ArrayList<>();
        for (ExternalMethod method : kept) {
            if (method.aliasOf == null || stems.contains(method.aliasOf)) out.add(method);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("no external engine matches "
                + System.getProperty("ripr.onlyExternalMethod"));
        return out;
    }

    private static List<ExternalMethod> externalMethods(
            TurboRegEstimator turbo, ImageStabilizerSolver stabilizer,
            Fast4DRegEstimator fast4d, Correct3DDriftEstimator correct3d,
            SiftEstimator sift, DescriptorSeriesSolver descriptor) {
        return requestedOnly(allExternalMethods(turbo, stabilizer, fast4d, correct3d, sift,
                descriptor));
    }

    private static List<ExternalMethod> allExternalMethods(
            TurboRegEstimator turbo, ImageStabilizerSolver stabilizer,
            Fast4DRegEstimator fast4d, Correct3DDriftEstimator correct3d,
            SiftEstimator sift, DescriptorSeriesSolver descriptor) {
        String turboModel = turbo.settings.turboRegTransformation;
        String siftModel = sift.settings.siftModel;
        String descriptorModel = descriptor.settings.descriptorModel;
        return Arrays.asList(
                new ExternalMethod(
                        "21_real_stackreg_turboreg_translation_chain",
                        "TurboReg area registration", "StackReg: " + turboModel
                                + ", previous frame",
                        "native consecutive-frame chain; " + turboModel + " model", 47,
                        pairSolver(turbo, false)),
                new ExternalMethod(
                        "22_real_turboreg_translation_multilag_rcc",
                        "TurboReg area registration", "TurboReg: " + turboModel
                                + ", multiple lags",
                        "redundant cross-correlation global solve; " + turboModel + " model",
                        RCC_PAIRS,
                        pairSolver(turbo, true)),
                new ExternalMethod(
                        "23_real_multistackreg_translation_same_engine",
                        "TurboReg area registration", "MultiStackReg: " + turboModel,
                        "same TurboReg engine, model and previous-frame strategy as method 21", 47,
                        null, "21_real_stackreg_turboreg_translation_chain"),
                new ExternalMethod(
                        "24_real_image_stabilizer_lucas_kanade_rolling_template",
                        "Lucas-Kanade optical flow", "Image Stabilizer: translation",
                        "native rolling template; update coefficient 0.9", 47, stabilizer),
                new ExternalMethod(
                        "25_real_fast4dreg_nanoj_previous_frame",
                        "NanoJ cross-correlation", "Fast4DReg: previous-frame reference",
                        "native previous-frame chain", 47, pairSolver(fast4d, false)),
                new ExternalMethod(
                        "26_real_fast4dreg_nanoj_first_frame",
                        "NanoJ cross-correlation", "Fast4DReg: first-frame reference",
                        "native direct-to-first reference", 47, fixedSolver(fast4d)),
                new ExternalMethod(
                        "27_real_correct_3d_drift_phase_correlation_standard",
                        "ImgLib1 phase correlation", "Correct 3D Drift: standard",
                        "native integer previous-frame chain", 47,
                        correct3dSolver(correct3d, false)),
                new ExternalMethod(
                        "28_real_correct_3d_drift_phase_correlation_multitime",
                        "ImgLib1 phase correlation", "Correct 3D Drift: multi-time-scale",
                        "native lags 1, 3, 9, 27 and 47", 72,
                        correct3dSolver(correct3d, true)),
                new ExternalMethod(
                        "29_real_linear_stack_alignment_sift_translation_chain",
                        "scale-invariant feature transform", "Linear Stack Alignment with SIFT: "
                                + siftModel,
                        siftModel + " model; previous-frame chain", 47,
                        pairSolver(sift, false)),
                new ExternalMethod(
                        "30_real_sift_translation_multilag_rcc",
                        "scale-invariant feature transform", "SIFT: " + siftModel
                                + ", multiple lags",
                        siftModel + " model; redundant cross-correlation global solve", RCC_PAIRS,
                        pairSolver(sift, true)),
                new ExternalMethod(
                        "31_register_virtual_stack_slices_same_sift_engine",
                        "scale-invariant feature transform", "Register Virtual Stack Slices",
                        "same SIFT translation engine and previous-frame strategy as method 29", 47,
                        null, "29_real_linear_stack_alignment_sift_translation_chain"),
                new ExternalMethod(
                        "32_real_descriptor_based_series_translation",
                        "descriptor-based registration", "Descriptor-based series registration: "
                                + descriptorModel,
                        descriptorModel + " model; all detected features; all-to-all within five frames",
                        RCC_PAIRS,
                        descriptor));
    }

    /**
     * Run the installed engines over every recording already present under {@code root}.
     *
     * <p>Expects the benchmark's own layout, {@code <image class>/<series>/<motion>/<condition>/}
     * with {@code 00_input_uncorrected.tif} inside. The movement truth comes from the motion folder
     * name, which is the same declaration the log-ratio arms were scored against, so the two sets of
     * numbers are comparable without re-deriving anything.
     */
    private static void runExisting(Path root) throws Exception {
        if (!Files.isDirectory(root)) throw new IOException("missing recording root " + root);
        List<Path> inputs = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(root, 5)) {
            for (Path path : (Iterable<Path>) walk.filter(p ->
                    p.getFileName().toString().equals("00_input_uncorrected.tif"))::iterator) {
                inputs.add(path);
            }
        }
        java.util.Collections.sort(inputs);
        if (inputs.isEmpty()) throw new IOException("no 00_input_uncorrected.tif under " + root);
        System.out.printf("external engines over %d existing recordings under %s%n",
                inputs.size(), root);

        TurboRegEstimator turbo = new TurboRegEstimator();
        try {
            List<ExternalMethod> methods = externalMethods(turbo, new ImageStabilizerSolver(),
                    new Fast4DRegEstimator(), new Correct3DDriftEstimator(), new SiftEstimator(),
                    new DescriptorSeriesSolver());
            int completed = 0;
            List<String> failures = new ArrayList<>();
            for (Path input : inputs) {
                Path recording = input.getParent();
                String motionName = recording.getParent().getFileName().toString();
                try {
                    ControlledMotionProfile motion = ControlledMotionProfile.valueOf(motionName);
                    float[][] frames = readFrames(input);
                    int width = frameWidth(input);
                    double[] truthX = new double[Benchmark.FRAMES];
                    double[] truthY = new double[Benchmark.FRAMES];
                    int[] fineX = new int[Benchmark.FRAMES];
                    int[] fineY = new int[Benchmark.FRAMES];
                    motion.fill(fineX, fineY);
                    for (int t = 0; t < Benchmark.FRAMES; t++) {
                        truthX[t] = fineX[t] / (double) Benchmark.FINE;
                        truthY[t] = fineY[t] / (double) Benchmark.FINE;
                    }
                    if (frames.length != Benchmark.FRAMES) {
                        throw new IOException("expected " + Benchmark.FRAMES + " frames, found "
                                + frames.length + " in " + input);
                    }
                    System.out.println(root.relativize(recording).toString());
                    // These recordings carry no separate noise-free copy, so the diagnostic panel is
                    // built from the same frames the engines were given.
                    runMethodsOn(recording, frames, frames, width, truthX, truthY, methods);
                    Files.deleteIfExists(recording.resolve("external_failure.txt"));
                    completed++;
                } catch (Exception error) {
                    String message = root.relativize(recording) + ": " + error;
                    Files.write(recording.resolve("external_failure.txt"),
                            (message + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                    failures.add(message);
                    System.err.println("FAILED " + message);
                }
            }
            System.out.printf("external engines: %d of %d recordings, %d failures%n",
                    completed, inputs.size(), failures.size());
            if (!failures.isEmpty()) {
                throw new IOException("external failures:\n" + String.join("\n", failures));
            }
        } finally {
            turbo.close();
        }
    }

    /**
     * Lightweight, isolated parameter-runner. It reads existing benchmark inputs and writes one CSV;
     * it never writes into a recording folder and therefore cannot contaminate another configuration.
     */
    private static void runSweep(Path root, Path output, String configId) throws Exception {
        if (!Files.isDirectory(root)) throw new IOException("missing recording root " + root);
        SweepConfig config = sweepConfig(configId);
        String onlyClass = System.getProperty("ripr.onlyClass", "").trim();
        Set<String> onlyConditions = new HashSet<>();
        for (String value : System.getProperty("ripr.onlyCondition", "").split(",")) {
            if (!value.trim().isEmpty()) onlyConditions.add(value.trim());
        }
        Set<String> onlySeries = new HashSet<>();
        for (String value : System.getProperty("ripr.onlySeries", "").split(",")) {
            if (!value.trim().isEmpty()) onlySeries.add(value.trim());
        }
        Set<String> onlyMotions = new HashSet<>();
        for (String value : System.getProperty("ripr.onlyMotion", "").split(",")) {
            if (!value.trim().isEmpty()) onlyMotions.add(value.trim());
        }
        List<Path> inputs = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(root, 5)) {
            for (Path path : (Iterable<Path>) walk.filter(p ->
                    p.getFileName().toString().equals("00_input_uncorrected.tif"))::iterator) {
                Path relative = root.relativize(path);
                if (onlyClass.isEmpty() || (relative.getNameCount() > 0
                        && onlyClass.equals(relative.getName(0).toString()))) {
                    if (!onlySeries.isEmpty() && (relative.getNameCount() < 2
                            || !onlySeries.contains(relative.getName(1).toString()))) continue;
                    if (!onlyMotions.isEmpty() && (relative.getNameCount() < 3
                            || !onlyMotions.contains(relative.getName(2).toString()))) continue;
                    if (!onlyConditions.isEmpty() && (relative.getNameCount() < 4
                            || !matchesCondition(onlyConditions,
                            relative.getName(3).toString()))) continue;
                    inputs.add(path);
                }
            }
        }
        java.util.Collections.sort(inputs);
        if (inputs.isEmpty()) throw new IOException("no matching recordings under " + root);
        if (Files.exists(output) && !Boolean.getBoolean("ripr.rewrite")) {
            throw new IOException("refusing to overwrite " + output
                    + "; set -Dripr.rewrite=true");
        }
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        String preprocessingArm = System.getProperty("ripr.preprocessingArm", "native")
                .trim().toLowerCase(java.util.Locale.ROOT);
        if (!"native".equals(preprocessingArm)
                && !"common_normalized".equals(preprocessingArm)) {
            throw new IllegalArgumentException("ripr.preprocessingArm must be native or "
                    + "common_normalized, was " + preprocessingArm);
        }
        String header = "dataset,image_series_class,series_id,motion_category,condition,"
                + "engine,config_id,config_description,method_id,method_label,origin,family,"
                + "strategy,status,median_error_px,p90_error_px,max_error_px,terminal_error_px,"
                + "median_angle_error_deg,p90_angle_error_deg,max_angle_error_deg,"
                + "frames_over_1px,frames_over_5px,cpu_seconds,elapsed_seconds,pairs,"
                + "preprocessing_arm,normalization_fallback,peak_heap_mb,transform_x_px,"
                + "transform_y_px,transform_theta_rad,settings\n";
        Files.write(output, header.getBytes(StandardCharsets.UTF_8));

        TurboRegEstimator turbo = new TurboRegEstimator(config.settings);
        try {
            List<ExternalMethod> methods = configuredMethods(config, turbo);
            int successful = 0;
            int failed = 0;
            boolean warmup = true;
            for (Path input : inputs) {
                Path relative = root.relativize(input);
                if (relative.getNameCount() < 5) {
                    throw new IOException("unexpected recording layout " + input);
                }
                String imageClass = relative.getName(0).toString();
                String series = relative.getName(1).toString();
                String motionName = relative.getName(2).toString();
                String condition = relative.getName(3).toString();
                float[][] frames = readFrames(input);
                boolean normalizationFallback = false;
                if ("common_normalized".equals(preprocessingArm)) {
                    NormalizedFrames normalized = commonNormalize(frames);
                    frames = normalized.frames;
                    normalizationFallback = normalized.fallback;
                }
                int width = frameWidth(input);
                Truth truth = readTruth(input.getParent(), motionName, frames.length);
                Map<String, Result> results = evaluate(frames, width, truth.x, truth.y,
                        truth.theta, methods, warmup);
                warmup = false;
                StringBuilder rows = new StringBuilder();
                for (Result result : results.values()) {
                    ExternalMethod method = result.method;
                    String engine = engineForStem(method.stem);
                    String origin = method.stem.startsWith("22_") || method.stem.startsWith("30_")
                            ? "third-party pair estimator, our multi-lag solver"
                            : "third-party engine, native strategy";
                    String status = result.failure == null ? "ok" : "failed: " + result.failure;
                    double[] errors = result.solution == null ? null
                            : geometricErrors(result.solution.cumulative, truth.x, truth.y,
                                    truth.theta, width);
                    double[] angleErrors = result.solution == null ? null
                            : angleErrorsDegrees(result.solution.cumulative, truth.theta);
                    double peakHeapMb = (Runtime.getRuntime().totalMemory()
                            - Runtime.getRuntime().freeMemory()) / (1024.0 * 1024.0);
                    if (result.failure == null) successful++; else failed++;
                    rows.append(csvQuote(root.getFileName().toString())).append(',')
                            .append(csvQuote(imageClass)).append(',').append(csvQuote(series)).append(',')
                            .append(csvQuote(motionName)).append(',').append(csvQuote(condition)).append(',')
                            .append(csvQuote(engine)).append(',').append(csvQuote(config.id)).append(',')
                            .append(csvQuote(config.description)).append(',')
                            .append(csvQuote(method.stem)).append(',').append(csvQuote(method.label)).append(',')
                            .append(csvQuote(origin)).append(',').append(csvQuote(method.family)).append(',')
                            .append(csvQuote(method.strategy)).append(',').append(csvQuote(status)).append(',')
                            .append(formatSweep(result.median)).append(',')
                            .append(formatSweep(result.p90)).append(',')
                            .append(formatSweep(result.maximum)).append(',')
                            .append(formatSweep(errors == null ? Double.NaN
                                    : errors[errors.length - 1])).append(',')
                            .append(formatSweep(angleErrors == null ? Double.NaN
                                    : Benchmark.quantile(angleErrors, 0.5))).append(',')
                            .append(formatSweep(angleErrors == null ? Double.NaN
                                    : Benchmark.quantile(angleErrors, 0.9))).append(',')
                            .append(formatSweep(angleErrors == null ? Double.NaN
                                    : Benchmark.quantile(angleErrors, 1.0))).append(',')
                            .append(errors == null ? -1 : countAbove(errors, 1.0)).append(',')
                            .append(errors == null ? -1 : countAbove(errors, 5.0)).append(',')
                            .append(formatSweep(result.cpuMs / 1000.0)).append(',')
                            .append(formatSweep(result.elapsedMs / 1000.0)).append(',')
                            .append(method.pairs).append(',')
                            .append(csvQuote(preprocessingArm)).append(',')
                            .append(normalizationFallback).append(',')
                            .append(formatSweep(peakHeapMb)).append(',')
                            .append(csvQuote(transformSeries(result.solution, 0))).append(',')
                            .append(csvQuote(transformSeries(result.solution, 1))).append(',')
                            .append(csvQuote(transformSeries(result.solution, 2))).append(',')
                            .append(csvQuote(config.settings.describe())).append('\n');
                }
                Files.write(output, rows.toString().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.APPEND);
                System.out.printf("%s %s/%s/%s: %d rows%n", config.id, imageClass, series,
                        motionName, results.size());
            }
            System.out.printf("sweep %s complete: %d recordings, %d successful rows, %d failures%n",
                    config.id, inputs.size(), successful, failed);
        } finally {
            turbo.close();
        }
    }

    private static boolean matchesCondition(Set<String> requested, String instance) {
        for (String condition : requested) {
            if (instance.equals(condition) || instance.startsWith(condition + "_r")) return true;
        }
        return false;
    }

    private static List<ExternalMethod> configuredMethods(SweepConfig config,
                                                           TurboRegEstimator turbo)
            throws Exception {
        List<ExternalMethod> all = allExternalMethods(turbo,
                new ImageStabilizerSolver(config.settings), new Fast4DRegEstimator(config.settings),
                new Correct3DDriftEstimator(config.settings), new SiftEstimator(config.settings),
                new DescriptorSeriesSolver(config.settings));
        List<ExternalMethod> out = new ArrayList<>();
        if ("all".equals(config.engine)) {
            out.addAll(all);
        } else {
            for (ExternalMethod method : all) {
                if (config.engine.equals(engineForStem(method.stem))
                        && (Boolean.getBoolean("ripr.fullEngineRows")
                        || method.stem.equals(representativeStem(config.engine)))) out.add(method);
            }
        }
        String onlyMethod = System.getProperty("ripr.onlyMethod", "").trim();
        if (!onlyMethod.isEmpty()) {
            Set<String> requested = new HashSet<>();
            for (String value : onlyMethod.split(",")) {
                if (!value.trim().isEmpty()) requested.add(value.trim());
            }
            // Alias rows reuse the canonical engine result. Include that dependency when an alias
            // is requested, then let the reusable benchmark filter the extra canonical row.
            for (ExternalMethod method : all) {
                if (requested.contains(method.stem) && method.aliasOf != null) {
                    requested.add(method.aliasOf);
                }
            }
            out.removeIf(method -> !requested.contains(method.stem));
            if (out.isEmpty()) {
                throw new IllegalArgumentException("requested method is not in configuration "
                        + config.id + ": " + onlyMethod);
            }
        }
        return out;
    }

    private static String representativeStem(String engine) {
        if ("turboreg".equals(engine)) return "21_real_stackreg_turboreg_translation_chain";
        if ("stabilizer".equals(engine)) {
            return "24_real_image_stabilizer_lucas_kanade_rolling_template";
        }
        if ("fast4d".equals(engine)) return "26_real_fast4dreg_nanoj_first_frame";
        if ("correct3d".equals(engine)) {
            return "27_real_correct_3d_drift_phase_correlation_standard";
        }
        if ("sift".equals(engine)) {
            return "29_real_linear_stack_alignment_sift_translation_chain";
        }
        if ("descriptor".equals(engine)) {
            return "32_real_descriptor_based_series_translation";
        }
        throw new IllegalArgumentException("unknown engine " + engine);
    }

    private static String engineForStem(String stem) {
        if (stem.startsWith("21_") || stem.startsWith("22_") || stem.startsWith("23_")) {
            return "turboreg";
        }
        if (stem.startsWith("24_")) return "stabilizer";
        if (stem.startsWith("25_") || stem.startsWith("26_")) return "fast4d";
        if (stem.startsWith("27_") || stem.startsWith("28_")) return "correct3d";
        if (stem.startsWith("29_") || stem.startsWith("30_") || stem.startsWith("31_")) {
            return "sift";
        }
        if (stem.startsWith("32_")) return "descriptor";
        throw new IllegalArgumentException("unknown external method " + stem);
    }

    private static Map<String, Result> evaluate(float[][] frames, int width,
                                                 double[] truthX, double[] truthY,
                                                 List<ExternalMethod> methods) {
        return evaluate(frames, width, truthX, truthY, new double[truthX.length], methods, true);
    }

    private static Map<String, Result> evaluate(float[][] frames, int width,
                                                 double[] truthX, double[] truthY,
                                                 List<ExternalMethod> methods, boolean warmup) {
        return evaluate(frames, width, truthX, truthY, new double[truthX.length], methods, warmup);
    }

    private static Map<String, Result> evaluate(float[][] frames, int width,
                                                 double[] truthX, double[] truthY,
                                                 double[] truthTheta,
                                                 List<ExternalMethod> methods, boolean warmup) {
        Map<String, Result> results = new LinkedHashMap<>();
        for (ExternalMethod method : methods) {
            Result result;
            try {
                if (method.aliasOf != null) {
                    Result original = results.get(method.aliasOf);
                    if (original == null) throw new IllegalStateException("missing alias " + method.aliasOf);
                    result = new Result(method, original.solution, original.cpuMs, original.elapsedMs,
                            original.median, original.p90, original.maximum, original.failure);
                } else {
                    if (warmup) {
                        resetExternalRandomSources();
                        try {
                            method.solver.warmup(frames, width);
                        } catch (RuntimeException warmupFailure) {
                            // Warm-up is excluded from the measurement and must never decide whether
                            // the real 48-frame solve succeeds. Some feature methods cannot fit the
                            // deliberately tiny two-frame warm-up even when the full series is valid.
                            System.err.println("warm-up unavailable for " + method.stem + ": "
                                    + conciseFailure(warmupFailure));
                        }
                    }
                    resetExternalRandomSources();
                    long cpu0 = processCpuNanos();
                    long wall0 = System.nanoTime();
                    final List<String> asynchronousFailures = java.util.Collections.synchronizedList(
                            new ArrayList<String>());
                    Thread.UncaughtExceptionHandler previousHandler =
                            Thread.getDefaultUncaughtExceptionHandler();
                    Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                        asynchronousFailures.add(thread.getName() + ": " + conciseFailure(error));
                        error.printStackTrace(System.err);
                    });
                    Reconciler.Solution solution;
                    try {
                        solution = method.solver.solve(frames, width);
                    } finally {
                        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
                    }
                    if (!asynchronousFailures.isEmpty()) {
                        throw new IllegalStateException("asynchronous plugin worker failure(s): "
                                + String.join(" | ", asynchronousFailures));
                    }
                    double elapsedMs = (System.nanoTime() - wall0) / 1e6;
                    double cpuMs = (processCpuNanos() - cpu0) / 1e6;
                    double[] metrics = geometricMetrics(solution.cumulative, truthX, truthY,
                            truthTheta, width);
                    result = new Result(method, solution, cpuMs, elapsedMs,
                            metrics[0], metrics[1], metrics[2]);
                }
            } catch (RuntimeException error) {
                result = new Result(method, null, Double.NaN, Double.NaN, Double.NaN,
                        Double.NaN, Double.NaN, conciseFailure(error));
            }
            results.put(method.stem, result);
        }
        return results;
    }

    /**
     * The installed mpicbg RANSAC code is seeded with 69997, but its shared generator advances
     * across calls; TileUtil also uses Collections.shuffle's time-seeded generator. Reset both at
     * each independent method/recording boundary so a named configuration is exactly replayable.
     */
    private static void resetExternalRandomSources() {
        try {
            Class<?> model = Class.forName("mpicbg.models.AbstractModel");
            Field modelRandom = model.getDeclaredField("rnd");
            modelRandom.setAccessible(true);
            ((java.util.Random) modelRandom.get(null)).setSeed(EXTERNAL_RANDOM_SEED);

            Field collectionsRandom = java.util.Collections.class.getDeclaredField("r");
            collectionsRandom.setAccessible(true);
            collectionsRandom.set(null, new java.util.Random(EXTERNAL_RANDOM_SEED));
        } catch (ClassNotFoundException unavailableForNonMpicbgMethod) {
            // TurboReg, Image Stabilizer and Fast4DReg can be used without mpicbg on the classpath.
        } catch (Exception error) {
            throw new IllegalStateException("could not reset external random sources", error);
        }
    }

    /**
     * Per-frame RMS geometric warp error over every pixel in a square image, then frame quantiles.
     * For the plugin's translation-only transforms this is exactly the historical Euclidean error.
     * For a rigid estimate the closed form adds the rotation-induced displacement instead of silently
     * scoring only the centre translation.
     */
    static double[] geometricMetrics(Transform[] transforms, double[] truthX, double[] truthY,
                                     int width) {
        return geometricMetrics(transforms, truthX, truthY, new double[truthX.length], width);
    }

    static double[] geometricMetrics(Transform[] transforms, double[] truthX, double[] truthY,
                                     double[] truthTheta, int width) {
        double[] errors = geometricErrors(transforms, truthX, truthY, truthTheta, width);
        return new double[]{Benchmark.quantile(errors, 0.5), Benchmark.quantile(errors, 0.9),
                Benchmark.quantile(errors, 1.0)};
    }

    static double[] geometricErrors(Transform[] transforms, double[] truthX, double[] truthY,
                                    int width) {
        return geometricErrors(transforms, truthX, truthY, new double[truthX.length], width);
    }

    static double[] geometricErrors(Transform[] transforms, double[] truthX, double[] truthY,
                                    double[] truthTheta, int width) {
        if (transforms.length != truthX.length || transforms.length != truthY.length
                || transforms.length != truthTheta.length) {
            throw new IllegalArgumentException("transform and truth lengths differ");
        }
        double coordinateVariance = (width * (double) width - 1.0) / 12.0;
        double[] errors = new double[transforms.length];
        for (int i = 0; i < errors.length; i++) {
            Transform transform = transforms[i] == null ? Transform.IDENTITY : transforms[i];
            double dx = transform.dx - truthX[i];
            double dy = transform.dy - truthY[i];
            double angleError = transform.theta - truthTheta[i];
            double rotationMeanSquare = 4.0 * coordinateVariance
                    * (1.0 - Math.cos(angleError));
            errors[i] = Math.sqrt(Math.max(0.0, dx * dx + dy * dy + rotationMeanSquare));
        }
        return errors;
    }

    static double[] angleErrorsDegrees(Transform[] transforms, double[] truthTheta) {
        if (transforms.length != truthTheta.length) {
            throw new IllegalArgumentException("transform and angle-truth lengths differ");
        }
        double[] errors = new double[transforms.length];
        for (int i = 0; i < errors.length; i++) {
            Transform transform = transforms[i] == null ? Transform.IDENTITY : transforms[i];
            errors[i] = Math.abs(Math.toDegrees(transform.theta - truthTheta[i]));
        }
        return errors;
    }

    static final class Truth {
        final double[] x;
        final double[] y;
        final double[] theta;

        Truth(double[] x, double[] y, double[] theta) {
            this.x = x;
            this.y = y;
            this.theta = theta;
        }
    }

    /** Read exact per-frame truth when a generated benchmark supplies it; otherwise use v2 motion truth. */
    static Truth readTruth(Path recording, String motionName, int frames) throws IOException {
        Path file = recording.resolve("truth.csv");
        if (!Files.isRegularFile(file)) {
            ControlledMotionProfile motion = ControlledMotionProfile.valueOf(motionName);
            int[] fineX = new int[frames];
            int[] fineY = new int[frames];
            motion.fill(fineX, fineY);
            double[] x = new double[frames];
            double[] y = new double[frames];
            for (int i = 0; i < frames; i++) {
                x[i] = fineX[i] / (double) Benchmark.FINE;
                y[i] = fineY[i] / (double) Benchmark.FINE;
            }
            return new Truth(x, y, new double[frames]);
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.size() != frames + 1) {
            throw new IOException(file + " has " + Math.max(0, lines.size() - 1)
                    + " truth rows, expected " + frames);
        }
        String[] header = lines.get(0).split(",", -1);
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.length; i++) columns.put(header[i].trim(), i);
        String xName = columns.containsKey("dx") ? "dx" : "truth_x_px";
        String yName = columns.containsKey("dy") ? "dy" : "truth_y_px";
        String thetaName = columns.containsKey("theta_radians")
                ? "theta_radians" : "truth_rotation_radians";
        if (!columns.containsKey(xName) || !columns.containsKey(yName)
                || !columns.containsKey(thetaName)) {
            throw new IOException("unsupported truth header in " + file);
        }
        double[] x = new double[frames];
        double[] y = new double[frames];
        double[] theta = new double[frames];
        for (int i = 0; i < frames; i++) {
            String[] row = lines.get(i + 1).split(",", -1);
            x[i] = Double.parseDouble(row[columns.get(xName)]);
            y[i] = Double.parseDouble(row[columns.get(yName)]);
            theta[i] = Double.parseDouble(row[columns.get(thetaName)]);
        }
        return new Truth(x, y, theta);
    }

    private static int countAbove(double[] values, double threshold) {
        int count = 0;
        for (double value : values) if (value > threshold) count++;
        return count;
    }

    private static String transformSeries(Reconciler.Solution solution, int component) {
        if (solution == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < solution.cumulative.length; i++) {
            if (i > 0) out.append(';');
            Transform transform = solution.cumulative[i];
            if (transform == null) out.append("NaN");
            else out.append(String.format(java.util.Locale.ROOT, "%.9f",
                    component == 0 ? transform.dx : component == 1 ? transform.dy
                            : transform.theta));
        }
        return out.toString();
    }

    static double[] controlledGeometricMetrics(Transform[] transforms, String motion, int width) {
        int[] fineX = new int[Benchmark.FRAMES];
        int[] fineY = new int[Benchmark.FRAMES];
        ControlledMotionProfile.valueOf(motion).fill(fineX, fineY);
        double[] truthX = new double[Benchmark.FRAMES];
        double[] truthY = new double[Benchmark.FRAMES];
        for (int i = 0; i < Benchmark.FRAMES; i++) {
            truthX[i] = fineX[i] / (double) Benchmark.FINE;
            truthY[i] = fineY[i] / (double) Benchmark.FINE;
        }
        return geometricMetrics(transforms, truthX, truthY, width);
    }

    /** Deterministic checks for the sweep registry and rigid/geometric conversion code. */
    private static void selfTest() {
        Set<String> ids = new HashSet<>();
        for (SweepConfig config : sweepConfigs()) {
            if (!ids.add(config.id)) throw new AssertionError("duplicate config " + config.id);
        }
        if (ids.size() != 26) throw new AssertionError("expected 26 configs, found " + ids.size());

        int width = 129;
        Transform expected = new Transform(2.25, -1.75, Math.toRadians(1.2));
        double center = (width - 1) / 2.0;
        double[][] from = {{center, center}, {center - 30, center}, {center, center + 30}};
        double[][] to = new double[from.length][2];
        for (int i = 0; i < from.length; i++) {
            expected.apply(from[i][0], from[i][1], center, center, to[i]);
        }
        Transform fitted = fittedRigid(from, to, width);
        assertClose("rigid dx", expected.dx, fitted.dx, 1e-10);
        assertClose("rigid dy", expected.dy, fitted.dy, 1e-10);
        assertClose("rigid theta", expected.theta, fitted.theta, 1e-10);

        Transform[] transforms = {Transform.IDENTITY, Transform.translation(3, -4)};
        double[] metrics = geometricMetrics(transforms, new double[]{0, 3},
                new double[]{0, -4}, width);
        assertClose("translation geometric median", 0, metrics[0], 0);
        assertClose("translation geometric p90", 0, metrics[1], 0);
        assertClose("translation geometric max", 0, metrics[2], 0);
        System.out.println("External sweep self-test passed: 26 unique configs; rigid and metric math OK");
    }

    private static void assertClose(String label, double expected, double actual, double tolerance) {
        if (!(Math.abs(expected - actual) <= tolerance)) {
            throw new AssertionError(label + ": expected " + expected + ", found " + actual);
        }
    }

    static int frameWidth(Path input) throws IOException {
        ImagePlus image = IJ.openImage(input.toString());
        if (image == null) throw new IOException("could not open " + input);
        try {
            return image.getWidth();
        } finally {
            image.close();
        }
    }

    static float[][] readFrames(Path input) throws IOException {
        ImagePlus image = IJ.openImage(input.toString());
        if (image == null) throw new IOException("could not open " + input);
        try {
            int count = image.getStackSize();
            float[][] out = new float[count][];
            for (int t = 0; t < count; t++) {
                ImageProcessor processor = image.getStack().getProcessor(t + 1);
                out[t] = (float[]) processor.convertToFloat().getPixels();
            }
            return out;
        } finally {
            image.close();
        }
    }

    /** Result of the method-independent robust affine intensity mapping frozen for benchmark v3. */
    static final class NormalizedFrames {
        final float[][] frames;
        final boolean fallback;

        NormalizedFrames(float[][] frames, boolean fallback) {
            this.frames = frames;
            this.fallback = fallback;
        }
    }

    /**
     * Map every frame's 1st--99th percentile range to frame zero's range.
     *
     * <p>This is intentionally test infrastructure rather than plugin preprocessing. Every method
     * receives the same mapped bytes, and the native arm remains available beside it.
     */
    static NormalizedFrames commonNormalize(float[][] input) {
        if (input.length == 0) return new NormalizedFrames(new float[0][], false);
        double referenceLow = Benchmark.percentile(input[0], 1.0);
        double referenceHigh = Benchmark.percentile(input[0], 99.0);
        float[][] output = new float[input.length][];
        boolean referenceFallback = !(Double.isFinite(referenceLow) && Double.isFinite(referenceHigh))
                || referenceHigh - referenceLow < Math.max(1e-12,
                1e-12 * Math.abs(referenceHigh));
        boolean fallback = referenceFallback;
        for (int t = 0; t < input.length; t++) {
            output[t] = new float[input[t].length];
            double low = Benchmark.percentile(input[t], 1.0);
            double high = Benchmark.percentile(input[t], 99.0);
            boolean frameFallback = referenceFallback || !(Double.isFinite(low) && Double.isFinite(high))
                    || high - low < Math.max(1e-12, 1e-12 * Math.abs(high));
            if (frameFallback) {
                System.arraycopy(input[t], 0, output[t], 0, input[t].length);
                fallback = true;
                continue;
            }
            double scale = (referenceHigh - referenceLow) / (high - low);
            for (int i = 0; i < input[t].length; i++) {
                double clipped = Math.max(low, Math.min(high, input[t][i]));
                output[t][i] = (float) (referenceLow + (clipped - low) * scale);
            }
        }
        return new NormalizedFrames(output, fallback);
    }

    private static void runVersion2(Path project) throws Exception {
        Path benchmark = project.resolve("library/benchmark");
        Path manifest = benchmark.resolve("benchmark_v2_series_manifest.csv");
        if (!Files.isRegularFile(manifest)) throw new IOException("missing " + manifest);

        Set<String> requested = new HashSet<>();
        for (String value : System.getProperty("ripr.onlySeries", "").split(",")) {
            if (!value.trim().isEmpty()) requested.add(value.trim());
        }
        if (requested.isEmpty() && !Boolean.getBoolean("ripr.confirmBalancedRun")) {
            throw new IllegalArgumentException("Set -Dripr.onlySeries=id1,id2 or "
                    + "-Dripr.confirmBalancedRun=true");
        }
        String requestedCondition = System.getProperty("ripr.onlyCondition", "CLEAN");
        String requestedMotion = System.getProperty("ripr.onlyMotionProfile", "");
        boolean includeSupplementary = Boolean.getBoolean("ripr.includeSupplementary");

        TurboRegEstimator turbo = new TurboRegEstimator();
        try {
            List<ExternalMethod> methods = externalMethods(turbo, new ImageStabilizerSolver(),
                    new Fast4DRegEstimator(), new Correct3DDriftEstimator(), new SiftEstimator(),
                    new DescriptorSeriesSolver());
            int completed = 0;
            List<String> failures = new ArrayList<>();
            List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).trim().isEmpty()) continue;
                String[] row = lines.get(i).split(",", -1);
                String seriesId = row[0];
                if ("LOCAL_EXISTING".equals(row[1])) continue;
                if (!requested.isEmpty() && !requested.contains(seriesId)) continue;
                if (!includeSupplementary && "supplementary".equals(row[4])) continue;
                Path source = benchmark.resolve(row[8]).normalize();
                Path seed = representativeImage(source);
                for (ControlledMotionProfile motion : ControlledMotionProfile.values()) {
                    if (!requestedMotion.isEmpty()
                            && !requestedMotion.equals(motion.name())) continue;
                    for (Benchmark.Condition condition : Benchmark.Condition.values()) {
                        if (!requestedCondition.isEmpty()
                                && !requestedCondition.equals(condition.name())) continue;
                        Path recording = benchmark.resolve("v2/benchmarks/controlled_motion")
                                .resolve(row[3]).resolve(seriesId).resolve(motion.name())
                                .resolve(condition.name());
                        try {
                            runRecordingAt(seed, condition, motion, recording, methods);
                            Files.deleteIfExists(recording.resolve("external_failure.txt"));
                            completed++;
                        } catch (Exception error) {
                            Files.createDirectories(recording);
                            String message = seriesId + " / " + motion.name() + " / "
                                    + condition.name() + ": " + error;
                            Files.write(recording.resolve("external_failure.txt"),
                                    (message + System.lineSeparator())
                                            .getBytes(StandardCharsets.UTF_8));
                            failures.add(message);
                            System.err.println("FAILED " + message);
                        }
                    }
                }
            }
            System.out.println("external completed " + completed + "; failed " + failures.size());
            if (!failures.isEmpty()) {
                throw new IOException("external benchmark failures:\n"
                        + String.join("\n", failures));
            }
        } finally {
            turbo.close();
        }
    }

    private static Path representativeImage(Path source) throws IOException {
        List<Path> candidates = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(source)) {
            stream.filter(Files::isRegularFile).filter(path -> {
                String normalized = path.toString().replace('\\', '/');
                if (normalized.contains("/annotations/")) return false;
                String name = path.getFileName().toString().toLowerCase();
                return name.endsWith(".tif") || name.endsWith(".tiff")
                        || name.endsWith(".png") || name.endsWith(".stk");
            }).forEach(candidates::add);
        }
        if (candidates.isEmpty()) throw new IOException("no image source under " + source);
        candidates.sort(Comparator.comparing(Path::toString));
        return candidates.get(0);
    }

    private static void runNativeVersion2(Path project) throws Exception {
        Path benchmark = project.resolve("library/benchmark");
        Path manifest = benchmark.resolve("benchmark_v2_native_series_manifest.csv");
        if (!Files.isRegularFile(manifest)) throw new IOException("missing " + manifest);
        Set<String> requested = new HashSet<>();
        for (String value : System.getProperty("ripr.onlySeries", "").split(",")) {
            if (!value.trim().isEmpty()) requested.add(value.trim());
        }
        if (requested.isEmpty() && !Boolean.getBoolean("ripr.confirmBalancedRun")) {
            throw new IllegalArgumentException("Set -Dripr.onlySeries=id1,id2 or "
                    + "-Dripr.confirmBalancedRun=true");
        }
        String requestedClass = System.getProperty("ripr.onlyClass", "");

        TurboRegEstimator turbo = new TurboRegEstimator();
        try {
            List<ExternalMethod> methods = externalMethods(turbo, new ImageStabilizerSolver(),
                    new Fast4DRegEstimator(), new Correct3DDriftEstimator(), new SiftEstimator(),
                    new DescriptorSeriesSolver());
            List<String> failures = new ArrayList<>();
            int completed = 0;
            List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
            for (int line = 1; line < lines.size(); line++) {
                if (lines.get(line).trim().isEmpty()) continue;
                String[] row = lines.get(line).split(",", -1);
                String seriesId = row[0];
                String imageClass = row[1];
                if (!"ready".equals(row[9])) continue;
                if (!requested.isEmpty() && !requested.contains(seriesId)) continue;
                if (!requestedClass.isEmpty() && !requestedClass.equals(imageClass)) continue;
                try {
                    NativeSeriesFrames.Recording nativeFrames = NativeSeriesFrames.load(
                            benchmark.resolve(row[3]).normalize(),
                            NativeSeriesFrames.Layout.valueOf(row[4]), Integer.parseInt(row[5]),
                            Integer.parseInt(row[6]), Integer.parseInt(row[7]),
                            Integer.parseInt(row[8]));
                    Path recording = findNativeRecording(benchmark, imageClass, seriesId);
                    runNativeRecordingAt(nativeFrames.frames, nativeFrames.width, recording, methods);
                    completed++;
                } catch (Exception error) {
                    String message = seriesId + ": " + error;
                    failures.add(message);
                    System.err.println("FAILED " + message);
                }
            }
            System.out.println("external native completed " + completed + "; failed "
                    + failures.size());
            if (!failures.isEmpty()) {
                throw new IOException("external native failures:\n" + String.join("\n", failures));
            }
        } finally {
            turbo.close();
        }
    }

    private static Path findNativeRecording(Path benchmark, String imageClass, String seriesId)
            throws IOException {
        Path classRoot = benchmark.resolve("v2/benchmarks/natural_motion").resolve(imageClass);
        List<Path> found = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(classRoot)) {
            stream.filter(path -> path.getFileName().toString().equals(seriesId))
                    .filter(path -> Files.isRegularFile(path.resolve("motion_profile.csv")))
                    .forEach(found::add);
        }
        if (found.size() != 1) {
            throw new IOException(seriesId + " has " + found.size()
                    + " current native output folders rather than one");
        }
        return found.get(0);
    }

    private static void runNativeRecordingAt(float[][] frames, int width, Path recording,
                                             List<ExternalMethod> methods) throws Exception {
        if (!Files.isRegularFile(recording.resolve("comparison.csv"))) {
            throw new IOException("internal native methods must run first: " + recording);
        }
        Path diagnostics = recording.resolve("diagnostics");
        Files.createDirectories(diagnostics);
        Map<String, Result> results = new LinkedHashMap<>();
        for (ExternalMethod method : methods) {
            Result result;
            try {
                if (method.aliasOf != null) {
                    Result original = results.get(method.aliasOf);
                    if (original == null) throw new IllegalStateException("missing alias " + method.aliasOf);
                    result = new Result(method, original.solution, original.cpuMs,
                            original.elapsedMs, original.median, original.p90, original.maximum,
                            original.failure, original.metricFailure);
                } else {
                    method.solver.warmup(frames, width);
                    long cpu0 = processCpuNanos();
                    long wall0 = System.nanoTime();
                    Reconciler.Solution solution = method.solver.solve(frames, width);
                    double elapsedMs = (System.nanoTime() - wall0) / 1e6;
                    double cpuMs = (processCpuNanos() - cpu0) / 1e6;
                    float[][] corrected = corrected(frames, width, solution);
                    NativeMotionProfiler.Residual residual = NativeMotionProfiler.residual(
                            corrected, width);
                    // REGRESSION GUARD: A successful plugin run can still leave too little common
                    // finite image area to score. Preserve the correction, but label its metric
                    // explicitly unscorable instead of emitting an unexplained blank value.
                    String metricFailure = Double.isFinite(residual.median) ? null
                            : "correction leaves less than 32 x 32 common finite overlap";
                    result = new Result(method, solution, cpuMs, elapsedMs,
                            residual.median, residual.p90, residual.maximum, null, metricFailure);
                }
            } catch (RuntimeException error) {
                result = new Result(method, null, Double.NaN, Double.NaN, Double.NaN,
                        Double.NaN, Double.NaN, conciseFailure(error));
            }
            results.put(method.stem, result);
            if (result.failure == null) {
                writeImages(recording, diagnostics, method, frames, frames, width, result.solution);
                writeTransforms(recording.resolve(method.stem + "_transforms.csv"), result.solution);
                Files.deleteIfExists(recording.resolve(method.stem + "_unavailable.txt"));
                Path unscorable = recording.resolve(method.stem + "_unscorable.txt");
                if (result.metricFailure == null) {
                    Files.deleteIfExists(unscorable);
                } else {
                    Files.write(unscorable, (result.metricFailure + System.lineSeparator())
                            .getBytes(StandardCharsets.UTF_8));
                }
            } else {
                Files.write(recording.resolve(method.stem + "_unavailable.txt"),
                        (result.failure + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            }
            System.out.printf("%-62s residual %8s px  %8.3f CPU s%n", method.stem,
                    formatAvailable(result.median), result.cpuMs / 1000.0);
        }
        updateNativeComparison(recording, frames.length, results);
        updateReadme(recording, results);
    }

    private static void updateNativeComparison(Path recording, int frameCount,
                                               Map<String, Result> results) throws IOException {
        Path csv = recording.resolve("comparison.csv");
        List<String> old = Files.readAllLines(csv, StandardCharsets.UTF_8);
        boolean internalRowsAlreadyExpanded = !old.isEmpty()
                && old.get(0).contains("elapsed_seconds_per_stack");
        StringBuilder text = new StringBuilder();
        for (String line : old) {
            if (startsWithExternalNumber(line)) continue;
            if (line.startsWith("method,")) {
                text.append("method,median_residual_step_px,p90_residual_step_px,")
                        .append("cpu_seconds_per_stack,cpu_ms_per_pair,details,metric_status,")
                        .append("family,strategy,maximum_residual_step_px,")
                        .append("elapsed_seconds_per_stack,elapsed_ms_per_pair,timing_status\n");
            } else {
                text.append(line);
                if (!internalRowsAlreadyExpanded) text.append(",,,,,,");
                text.append('\n');
            }
        }
        for (Result result : results.values()) {
            ExternalMethod method = result.method;
            int pairs = nativePairCount(method, frameCount);
            String status = result.failure != null ? "unavailable: " + result.failure
                    : method.aliasOf == null ? "measured" : "reused identical engine run";
            text.append(method.stem).append(',').append(formatAvailable(result.median)).append(',')
                    .append(formatAvailable(result.p90)).append(',')
                    .append(formatAvailable(result.cpuMs / 1000.0)).append(',')
                    .append(formatAvailable(result.cpuMs / Math.max(1, pairs))).append(',')
                    .append(csvQuote(method.label)).append(',')
                    .append(csvQuote(result.failure != null
                            ? "not scored because method was unavailable"
                            : result.metricFailure == null
                                    ? "stability only; natural movement has no exact truth"
                                    : "unscorable: " + result.metricFailure)).append(',')
                    .append(csvQuote(method.family)).append(',').append(csvQuote(method.strategy)).append(',')
                    .append(formatAvailable(result.maximum)).append(',')
                    .append(formatAvailable(result.elapsedMs / 1000.0)).append(',')
                    .append(formatAvailable(result.elapsedMs / Math.max(1, pairs))).append(',')
                    .append(csvQuote(status)).append('\n');
        }
        Files.write(csv, text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static int nativePairCount(ExternalMethod method, int frames) {
        if (method.stem.contains("multilag")) {
            return Reconciler.planPairs(Reconciler.Reference.MULTILAG,
                    frames, 0, 1, LAGS).size();
        }
        return Math.max(1, frames - 1);
    }

    /** Reports adapter sign and scale against a known content motion of (+3, -2) pixels. */
    private static void probeSigns() throws Exception {
        int width = 192;
        float[] first = new float[width * width];
        java.util.Random random = new java.util.Random(20260813L);
        for (int y = 12; y < width - 12; y++) {
            for (int x = 12; x < width - 12; x++) {
                double blob = 100 * Math.exp(-Math.pow(x - 61, 2) / 300.0
                        - Math.pow(y - 89, 2) / 500.0);
                first[y * width + x] = (float) (blob + 20 * random.nextDouble());
            }
        }
        float[] second = new float[first.length];
        for (int y = 0; y < width; y++) {
            for (int x = 0; x < width; x++) {
                int sx = x - 3;
                int sy = y + 2;
                if (sx >= 0 && sy >= 0 && sx < width && sy < width) {
                    second[y * width + x] = first[sy * width + sx];
                }
            }
        }
        TurboRegEstimator turbo = new TurboRegEstimator();
        System.out.println("expected content motion: dx=3 dy=-2");
        System.out.println("TurboReg: " + turbo.estimate(first, second, width));
        System.out.println("Fast4DReg: " + new Fast4DRegEstimator().estimate(first, second, width));
        System.out.println("Correct3D: " + new Correct3DDriftEstimator().estimate(first, second, width));
        System.out.println("SIFT: " + new SiftEstimator().estimate(first, second, width));
        Reconciler.Solution stabilized = new ImageStabilizerSolver().solve(
                new float[][]{first, second}, width);
        System.out.println("Image Stabilizer: " + stabilized.cumulative[1]);
        Reconciler.Solution descriptor = new DescriptorSeriesSolver().solve(
                new float[][]{first, second}, width);
        System.out.println("Descriptor series: " + descriptor.cumulative[1]);
        turbo.close();
    }

    private static void runRecording(Path seed, Benchmark.Condition condition, Path out,
                                     List<ExternalMethod> methods) throws Exception {
        String seedName = seed.getFileName().toString().replaceFirst("(?i)\\.tif$", "");
        runRecordingAt(seed, condition, ControlledMotionProfile.CURVED_OSCILLATING_DRIFT,
                out.resolve(seedName + "__" + condition.name()), methods);
    }

    private static void runRecordingAt(Path seed, Benchmark.Condition condition,
                                       ControlledMotionProfile motionProfile, Path recording,
                                       List<ExternalMethod> methods) throws Exception {
        ImagePlus image = IJ.openImage(seed.toString());
        if (image == null) throw new IOException("could not open " + seed);
        int shortestSide = Math.min(image.getWidth(), image.getHeight());
        if (shortestSide < 512) {
            double scale = 512.0 / shortestSide;
            int resizedWidth = (int) Math.ceil(image.getWidth() * scale);
            int resizedHeight = (int) Math.ceil(image.getHeight() * scale);
            ImageProcessor processor = image.getProcessor().duplicate();
            processor.setInterpolationMethod(ImageProcessor.BILINEAR);
            processor = processor.resize(resizedWidth, resizedHeight);
            image.close();
            image = new ImagePlus(seed.getFileName().toString(), processor);
        }
        int sourceWidth = image.getWidth();
        int sourceHeight = image.getHeight();
        float[] fine = Benchmark.seedPlane(image);
        image.close();

        int[] fineX = new int[Benchmark.FRAMES];
        int[] fineY = new int[Benchmark.FRAMES];
        motionProfile.fill(fineX, fineY);
        double[] truthX = new double[Benchmark.FRAMES];
        double[] truthY = new double[Benchmark.FRAMES];
        int span = 0;
        for (int t = 0; t < Benchmark.FRAMES; t++) {
            truthX[t] = fineX[t] / (double) Benchmark.FINE;
            truthY[t] = fineY[t] / (double) Benchmark.FINE;
            span = Math.max(span, Math.max(Math.abs(fineX[t]), Math.abs(fineY[t])));
        }
        int margin = span + Benchmark.FINE_SLACK;
        int width = Math.min((sourceWidth - 2 * margin) / Benchmark.FINE,
                (sourceHeight - 2 * margin) / Benchmark.FINE);
        double sourceSd = Benchmark.standardDeviation(fine);
        float[][] frames = makeFrames(fine, sourceWidth, sourceHeight, width, margin,
                fineX, fineY, condition, sourceSd);
        float[][] clean = makeFrames(fine, sourceWidth, sourceHeight, width, margin,
                fineX, fineY, Benchmark.Condition.CLEAN, sourceSd);

        runMethodsOn(recording, frames, clean, width, truthX, truthY, methods);
    }

    /**
     * Run every installed engine on frames that already exist, rather than on frames regenerated
     * from a seed image.
     *
     * <p>The controlled benchmark rebuilds its stacks deterministically from the source series named
     * in the manifest. A recording set built some other way — the locked test set, for one — has no
     * manifest row and no seed to rebuild from, but it does have its input stack on disk and a
     * declared movement profile in its folder name, which is everything these engines need.
     */
    private static void runMethodsOn(Path recording, float[][] frames, float[][] clean, int width,
                                     double[] truthX, double[] truthY,
                                     List<ExternalMethod> methods) throws Exception {
        Path diagnostics = recording.resolve("diagnostics");
        Files.createDirectories(diagnostics);

        Map<String, Result> results = new LinkedHashMap<>();
        for (ExternalMethod method : methods) {
            Result result;
            try {
                if (method.aliasOf != null) {
                    Result original = results.get(method.aliasOf);
                    if (original == null) {
                        throw new IllegalStateException("missing alias " + method.aliasOf);
                    }
                    result = new Result(method, original.solution, original.cpuMs,
                            original.elapsedMs, original.median, original.p90, original.maximum,
                            original.failure);
                } else {
                    method.solver.warmup(frames, width);
                    long cpu0 = processCpuNanos();
                    long wall0 = System.nanoTime();
                    Reconciler.Solution solution = method.solver.solve(frames, width);
                    double elapsedMs = (System.nanoTime() - wall0) / 1e6;
                    double cpuMs = (processCpuNanos() - cpu0) / 1e6;
                    double[] metrics = geometricMetrics(solution.cumulative, truthX, truthY, width);
                    result = new Result(method, solution, cpuMs, elapsedMs,
                            metrics[0], metrics[1], metrics[2]);
                }
            } catch (RuntimeException error) {
                String failure = conciseFailure(error);
                result = new Result(method, null, Double.NaN, Double.NaN,
                        Double.NaN, Double.NaN, Double.NaN, failure);
            }
            results.put(method.stem, result);
            if (result.failure == null) {
                // The corrected stack and the motion panel are for looking at, not for scoring: every
                // number in every summary comes from the transforms file. On a run whose only purpose
                // is a measurement they are 4 MB per engine per recording of nothing.
                if (!Boolean.getBoolean("ripr.noImages")) {
                    writeImages(recording, diagnostics, method, frames, clean, width,
                            result.solution);
                }
                writeTransforms(recording.resolve(method.stem + "_transforms.csv"), result.solution);
                Files.deleteIfExists(recording.resolve(method.stem + "_unavailable.txt"));
                System.out.printf("%-62s %8.4f px  %8.3f CPU s  %8.3f elapsed s%n",
                        method.stem, result.median, result.cpuMs / 1000.0,
                        result.elapsedMs / 1000.0);
            } else {
                Files.write(recording.resolve(method.stem + "_unavailable.txt"),
                        (result.failure + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                System.out.printf("%-62s unavailable: %s%n", method.stem, result.failure);
            }
        }
        updateComparison(recording, results);
        updateReadme(recording, results);
    }

    private static float[][] makeFrames(float[] fine, int sourceWidth, int sourceHeight, int width,
                                        int margin, int[] fineX, int[] fineY,
                                        Benchmark.Condition condition, double sourceSd) {
        float[][] out = new float[Benchmark.FRAMES][];
        for (int t = 0; t < out.length; t++) {
            out[t] = Benchmark.frame(fine, sourceWidth, sourceHeight, width, margin,
                    fineX[t], fineY[t], condition, t, sourceSd);
        }
        return out;
    }

    private static void writeImages(Path recording, Path diagnostics, ExternalMethod method,
                                    float[][] frames, float[][] clean, int width,
                                    Reconciler.Solution solution) {
        float[][] corrected = corrected(frames, width, solution);
        float[][] correctedClean = corrected(clean, width, solution);
        IJ.saveAsTiff(BenchmarkStacks.stack(corrected, width, method.label),
                recording.resolve(method.stem + "_corrected.tif").toString());
        IJ.saveAsTiff(BenchmarkStacks.sdPanel(clean, correctedClean, width),
                diagnostics.resolve(method.stem + "_before_after_motion.tif").toString());
    }

    private static float[][] corrected(float[][] frames, int width, Reconciler.Solution solution) {
        float[][] out = new float[frames.length][];
        for (int t = 0; t < frames.length; t++) {
            out[t] = new float[width * width];
            Transform transform = solution.cumulative[t] == null
                    ? Transform.IDENTITY : solution.cumulative[t];
            Warper.warp(frames[t], out[t], width, width, transform,
                    Warper.Interpolation.NONE, Float.NaN);
        }
        return out;
    }

    private static void writeTransforms(Path path, Reconciler.Solution solution)
            throws IOException {
        StringBuilder csv = new StringBuilder("frame,x_px,y_px,rotation_radians,status\n");
        for (int frame = 0; frame < solution.cumulative.length; frame++) {
            Transform transform = solution.cumulative[frame];
            if (transform == null) transform = Transform.IDENTITY;
            csv.append(frame + 1).append(',').append(format(transform.dx)).append(',')
                    .append(format(transform.dy)).append(',').append(format(transform.theta))
                    .append(',').append(solution.support[frame] > 0 ? "measured" : "unsupported")
                    .append('\n');
        }
        Files.write(path, csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String conciseFailure(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message);
    }

    private static void updateComparison(Path recording, Map<String, Result> results)
            throws IOException {
        Path csv = recording.resolve("comparison.csv");
        List<String> old = Files.readAllLines(csv, StandardCharsets.UTF_8);
        StringBuilder text = new StringBuilder();
        for (String line : old) {
            // Rows belonging to an engine this run did not touch are kept. Stripping every external
            // row and rewriting only the ones just measured would silently delete the rest of the
            // table whenever -Dripr.onlyExternalMethod narrows the run to one engine.
            if (line.startsWith(CSV_MARKER) || startsWithExternalNumber(line)) {
                if (results.containsKey(stemOf(line, ','))) continue;
                text.append(line).append("\n");
                continue;
            }
            if (line.startsWith("method,")) {
                text.append("method,median_error_px,cpu_seconds_per_stack,cpu_ms_per_pair,details,")
                        .append("family,strategy,p90_error_px,max_error_px,elapsed_seconds_per_stack,")
                        .append("elapsed_ms_per_pair,timing_status\n");
            } else {
                text.append(line).append("\n");
            }
        }
        for (Result result : results.values()) {
            ExternalMethod method = result.method;
            String status = result.failure != null ? "unavailable: " + result.failure
                    : method.aliasOf == null ? "measured" : "reused identical engine run";
            text.append(method.stem).append(',')
                    .append(formatAvailable(result.median)).append(',')
                    .append(formatAvailable(result.cpuMs / 1000.0)).append(',')
                    .append(formatAvailable(result.cpuMs / method.pairs)).append(',')
                    .append(csvQuote(method.label)).append(',')
                    .append(csvQuote(method.family)).append(',')
                    .append(csvQuote(method.strategy)).append(',')
                    .append(formatAvailable(result.p90)).append(',')
                    .append(formatAvailable(result.maximum)).append(',')
                    .append(formatAvailable(result.elapsedMs / 1000.0)).append(',')
                    .append(formatAvailable(result.elapsedMs / method.pairs)).append(',')
                    .append(csvQuote(status)).append('\n');
        }
        Files.write(csv, text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static boolean startsWithExternalNumber(String line) {
        return line.matches("(?:2[1-9]|3[0-2])_.*");
    }

    /** The file stem a saved row belongs to: everything before its first separator. */
    private static String stemOf(String line, char separator) {
        int at = line.indexOf(separator);
        return at < 0 ? line.trim() : line.substring(0, at).trim();
    }

    private static void updateReadme(Path recording, Map<String, Result> results)
            throws IOException {
        Path path = recording.resolve("README.txt");
        String old = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        int start = old.indexOf(README_START);
        List<String> kept = new ArrayList<>();
        if (start >= 0) {
            // Same reason as updateComparison: a narrowed run must not erase the engines it skipped.
            for (String line : old.substring(start).split("\n")) {
                if (startsWithExternalNumber(line) && !results.containsKey(stemOf(line, '\t'))) {
                    kept.add(line);
                }
            }
            old = old.substring(0, start);
        }
        StringBuilder section = new StringBuilder(README_START);
        section.append("External estimators are applied with the same nearest-neighbour correction as ")
                .append("the internal methods, so interpolation cannot change the ranking.\n")
                .append("method\tmedian px\tp90 px\tCPU s/stack\telapsed s/stack\tdetails\n");
        for (String line : kept) section.append(line).append('\n');
        for (Result result : results.values()) {
            ExternalMethod method = result.method;
            section.append(method.stem).append('\t').append(formatAvailable(result.median)).append('\t')
                    .append(formatAvailable(result.p90)).append('\t')
                    .append(formatAvailable(result.cpuMs / 1000.0))
                    .append('\t').append(formatAvailable(result.elapsedMs / 1000.0)).append('\t')
                    .append(method.label).append("; ").append(method.strategy);
            if (result.failure != null) section.append("; unavailable: ").append(result.failure);
            if (method.aliasOf != null) {
                section.append("; identical output reused from ").append(method.aliasOf);
            }
            section.append('\n');
        }
        section.append(README_END);
        Files.write(path, (old + section).getBytes(StandardCharsets.UTF_8));
    }

    private static String csvQuote(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    private static String formatAvailable(double value) {
        return Double.isFinite(value) ? format(value) : "";
    }

    private static String formatSweep(double value) {
        return Double.isFinite(value)
                ? String.format(java.util.Locale.ROOT, "%.9f", value) : "";
    }

    private static Solver pairSolver(final PairEstimator estimator, final boolean rcc) {
        return new Solver() {
            @Override
            public Reconciler.Solution solve(float[][] frames, int width) {
                if (rcc) return solveRcc(frames, width, estimator);
                Transform[] steps = new Transform[frames.length - 1];
                for (int t = 0; t < steps.length; t++) {
                    steps[t] = estimator.estimate(frames[t], frames[t + 1], width);
                }
                return Reconciler.chain(steps);
            }

            @Override
            public void warmup(float[][] frames, int width) {
                estimator.warmup(frames[0], frames[1], width);
            }
        };
    }

    private static Solver fixedSolver(final PairEstimator estimator) {
        return new Solver() {
            @Override
            public Reconciler.Solution solve(float[][] frames, int width) {
                Transform[] direct = new Transform[frames.length];
                direct[0] = Transform.IDENTITY;
                for (int t = 1; t < frames.length; t++) {
                    direct[t] = estimator.estimate(frames[0], frames[t], width);
                }
                return Reconciler.fixed(direct, 0);
            }

            @Override
            public void warmup(float[][] frames, int width) {
                estimator.warmup(frames[0], frames[1], width);
            }
        };
    }

    private static Reconciler.Solution solveRcc(float[][] frames, int width,
                                                PairEstimator estimator) {
        List<Reconciler.Observation> plan = Reconciler.planPairs(
                Reconciler.Reference.MULTILAG, frames.length, 0, 1, LAGS);
        List<Reconciler.Observation> measured = new ArrayList<>();
        for (Reconciler.Observation pair : plan) {
            Transform transform = estimator.estimate(frames[pair.from], frames[pair.to], width);
            if (transform != null) {
                measured.add(new Reconciler.Observation(pair.from, pair.to, transform));
            }
        }
        return Reconciler.multiLag(frames.length, measured);
    }

    private static Solver correct3dSolver(final Correct3DDriftEstimator estimator,
                                          final boolean multiTime) {
        return new Solver() {
            @Override
            public Reconciler.Solution solve(float[][] frames, int width) {
                if (!multiTime) return pairSolver(estimator, false).solve(frames, width);
                double[] x = new double[frames.length];
                double[] y = new double[frames.length];
                int[] dts = {1, 3, 9, 27, frames.length - 1};
                Set<Integer> used = new HashSet<>();
                for (int dt : dts) {
                    if (dt > 0 && dt < frames.length && used.add(dt)) {
                        updateCorrect3D(frames, width, estimator, dt, x, y);
                    }
                }
                Transform[] cumulative = new Transform[frames.length];
                for (int t = 0; t < cumulative.length; t++) {
                    // The script accumulates correction vectors, then inverts them before applying.
                    cumulative[t] = Transform.translation(-x[t], -y[t]);
                }
                return Reconciler.fixed(cumulative, 0);
            }

            @Override
            public void warmup(float[][] frames, int width) {
                estimator.warmup(frames[0], frames[1], width);
            }
        };
    }

    /** Exact no-ROI update loop from Correct_3D_drift.py. */
    private static void updateCorrect3D(float[][] frames, int width,
                                        Correct3DDriftEstimator estimator, int dt,
                                        double[] x, double[] y) {
        int n = frames.length;
        for (int t = dt; t < n + dt; t += dt) {
            int at = Math.min(t, n - 1);
            Transform content = estimator.estimate(frames[at - dt], frames[at], width);
            if (content == null) continue;
            double measuredCorrectionX = -content.dx;
            double measuredCorrectionY = -content.dy;
            double addX = measuredCorrectionX - (x[at] - x[at - dt]);
            double addY = measuredCorrectionY - (y[at] - y[at - dt]);
            for (int tt = at - dt; tt < n; tt++) {
                double f = (tt - (at - dt)) / (double) dt;
                x[tt] += f * addX;
                y[tt] += f * addY;
            }
            if (at == n - 1) break;
        }
    }

    private static long processCpuNanos() {
        try {
            Object os = ManagementFactory.getOperatingSystemMXBean();
            Method method = os.getClass().getMethod("getProcessCpuTime");
            method.setAccessible(true);
            return ((Number) method.invoke(os)).longValue();
        } catch (Exception ignored) {
            return ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
        }
    }

    // ----------------------------------------------------------------------------------------- //

    private static final class TurboRegEstimator implements PairEstimator {
        private static final String SOURCE = "lr_external_turboreg_source";
        private static final String TARGET = "lr_external_turboreg_target";
        private final Class<?> type;
        private final Method run;
        private final Method sourcePoints;
        private final Method targetPoints;
        private final ExternalSettings settings;
        private ImagePlus source;
        private ImagePlus target;
        private int width;
        private int height;

        TurboRegEstimator() throws Exception {
            this(ExternalSettings.defaults());
        }

        TurboRegEstimator(ExternalSettings settings) throws Exception {
            this.settings = settings;
            type = Class.forName("TurboReg_");
            run = type.getMethod("run", String.class);
            sourcePoints = type.getMethod("getSourcePoints");
            targetPoints = type.getMethod("getTargetPoints");
        }

        @Override
        public Transform estimate(float[] from, float[] to, int width) {
            int height = frameHeight(from, width);
            windows(width, height);
            source.getProcessor().setPixels(to.clone());
            target.getProcessor().setPixels(from.clone());
            source.updateAndDraw();
            target.updateAndDraw();
            String points;
            if ("translation".equals(settings.turboRegTransformation)) {
                points = " -translation " + (width / 2) + " " + (height / 2)
                        + " " + (width / 2) + " " + (height / 2);
            } else if ("rigid".equals(settings.turboRegTransformation)) {
                points = " -rigidBody "
                        + (width / 2) + " " + (height / 2) + " "
                        + (width / 2) + " " + (height / 2) + " "
                        + (width / 2) + " " + (height / 4) + " "
                        + (width / 2) + " " + (height / 4) + " "
                        + (width / 2) + " " + (3 * height / 4) + " "
                        + (width / 2) + " " + (3 * height / 4);
            } else {
                throw new IllegalArgumentException("unknown TurboReg model "
                        + settings.turboRegTransformation);
            }
            String options = "-align -window " + SOURCE + " 0 0 " + (width - 1) + " "
                    + (height - 1) + " -window " + TARGET + " 0 0 " + (width - 1) + " "
                    + (height - 1) + points + " -hideOutput";
            try {
                Object plugin = type.getDeclaredConstructor().newInstance();
                run.invoke(plugin, options);
                double[][] sourcePoint = (double[][]) sourcePoints.invoke(plugin);
                double[][] targetPoint = (double[][]) targetPoints.invoke(plugin);
                if (sourcePoint == null || targetPoint == null) return null;
                return fittedRigid(targetPoint, sourcePoint, width, height);
            } catch (Exception e) {
                throw new IllegalStateException("TurboReg failed", e);
            }
        }

        @Override
        public void warmup(float[] from, float[] to, int width) {
            estimate(from, to, width);
        }

        private void windows(int requestedWidth, int requestedHeight) {
            if (source != null && width == requestedWidth && height == requestedHeight) return;
            close();
            width = requestedWidth;
            height = requestedHeight;
            source = new ImagePlus(SOURCE, new FloatProcessor(width, height));
            target = new ImagePlus(TARGET, new FloatProcessor(width, height));
            source.show();
            target.show();
        }

        void close() {
            if (source != null) source.close();
            if (target != null) target.close();
            source = null;
            target = null;
        }
    }

    private static final class ImageStabilizerSolver implements Solver {
        private final Object plugin;
        private final Method estimate;
        private final Method warp;
        private final Method combine;
        private final ExternalSettings settings;

        ImageStabilizerSolver() throws Exception {
            this(ExternalSettings.defaults());
        }

        ImageStabilizerSolver(ExternalSettings settings) throws Exception {
            this.settings = settings;
            Class<?> type = Class.forName("Image_Stabilizer");
            plugin = type.getDeclaredConstructor().newInstance();
            Field alpha = type.getDeclaredField("alpha");
            alpha.setAccessible(true);
            alpha.setDouble(plugin, settings.stabilizerTemplateUpdate);
            estimate = declared(type, "estimateTranslation", ImageProcessor.class,
                    ImageProcessor.class, ImageProcessor[].class, ImageProcessor[].class,
                    int.class, double.class);
            warp = declared(type, "warpTranslation", ImageProcessor.class,
                    ImageProcessor.class, double[][].class);
            combine = declared(type, "combine", ImageProcessor.class, ImageProcessor.class);
        }

        @Override
        public Reconciler.Solution solve(float[][] frames, int width) {
            try {
                int height = frameHeight(frames[0], width);
                ImageProcessor template = processor(frames[0], width);
                Transform[] cumulative = new Transform[frames.length];
                cumulative[0] = Transform.IDENTITY;
                for (int t = 1; t < frames.length; t++) {
                    ImageProcessor current = processor(frames[t], width);
                    ImageProcessor[] currentPyramid = pyramid(width, height, settings.stabilizerPyramidLevel);
                    ImageProcessor[] templatePyramid = pyramid(width, height, settings.stabilizerPyramidLevel);
                    double[][] correction = (double[][]) estimate.invoke(plugin, current, template,
                            currentPyramid, templatePyramid, settings.stabilizerIterations,
                            settings.stabilizerTolerance);
                    // The matrix is expressed as content motion; warpTranslation samples its inverse.
                    cumulative[t] = Transform.translation(correction[0][0], correction[1][0]);
                    ImageProcessor registered = new FloatProcessor(width, height);
                    warp.invoke(plugin, registered, current, correction);
                    combine.invoke(plugin, template, registered);
                }
                return Reconciler.fixed(cumulative, 0);
            } catch (Exception e) {
                throw new IllegalStateException("Image Stabilizer failed", e);
            }
        }

        @Override
        public void warmup(float[][] frames, int width) {
            solve(new float[][]{frames[0], frames[1]}, width);
        }

        private static ImageProcessor[] pyramid(int width, int height, int maximumLevel) {
            ImageProcessor[] out = new ImageProcessor[5];
            out[0] = new FloatProcessor(width, height);
            for (int level = 1; level <= Math.min(4, maximumLevel); level++) {
                int minimum = 100 << (level - 1);
                if (Math.min(width, height) < minimum) break;
                int size = width >> level;
                out[level] = new FloatProcessor(size, height >> level);
            }
            return out;
        }
    }

    private static final class Fast4DRegEstimator implements PairEstimator {
        private final Method correlation;
        private final Method peak;
        private final ExternalSettings settings;

        Fast4DRegEstimator() throws Exception {
            this(ExternalSettings.defaults());
        }

        Fast4DRegEstimator(ExternalSettings settings) throws Exception {
            this.settings = settings;
            Class<?> map = Class.forName("image.transform.CrossCorrelationMap");
            Class<?> estimate = Class.forName("image.drift.EstimateShiftAndTilt");
            correlation = map.getMethod("calculateCrossCorrelationMap", ImageProcessor.class,
                    ImageProcessor.class, boolean.class);
            peak = estimate.getMethod("getShiftFromCrossCorrelationPeak",
                    FloatProcessor.class, int.class);
            Field progress = map.getField("showProgress");
            progress.setBoolean(null, false);
        }

        @Override
        public Transform estimate(float[] from, float[] to, int width) {
            try {
                FloatProcessor map = (FloatProcessor) correlation.invoke(null,
                        processor(from, width), processor(to, width),
                        settings.fast4dNormalizeCorrelation);
                float[] shift = (float[]) peak.invoke(null, map, settings.fast4dPeakMethod);
                // Entry 0 is peak strength; entries 1 and 2 are the correction vector.
                return Transform.translation(-shift[1], -shift[2]);
            } catch (Exception e) {
                throw new IllegalStateException("Fast4DReg/NanoJ failed", e);
            }
        }

        @Override
        public void warmup(float[] from, float[] to, int width) {
            estimate(from, to, width);
        }
    }

    private static final class Correct3DDriftEstimator implements PairEstimator {
        private final Method wrap;
        private final Constructor<?> phaseConstructor;
        private final Method process;
        private final Method getShift;
        private final Method getPosition;
        private final ExternalSettings settings;

        Correct3DDriftEstimator() throws Exception {
            this(ExternalSettings.defaults());
        }

        Correct3DDriftEstimator(ExternalSettings settings) throws Exception {
            this.settings = settings;
            Class<?> adapter = Class.forName("mpicbg.imglib.image.ImagePlusAdapter");
            Class<?> image = Class.forName("mpicbg.imglib.image.Image");
            Class<?> phase = Class.forName("mpicbg.imglib.algorithm.fft.PhaseCorrelation");
            Class<?> phasePeak = Class.forName("mpicbg.imglib.algorithm.fft.PhaseCorrelationPeak");
            wrap = adapter.getMethod("wrap", ImagePlus.class);
            phaseConstructor = phase.getConstructor(image, image, int.class, boolean.class);
            process = phase.getMethod("process");
            getShift = phase.getMethod("getShift");
            getPosition = phasePeak.getMethod("getPosition");
        }

        @Override
        public Transform estimate(float[] from, float[] to, int width) {
            try {
                ImagePlus first = new ImagePlus("first", processor(from, width));
                ImagePlus second = new ImagePlus("second", processor(to, width));
                // Correct_3D_drift.py deliberately calls compute_shift(current, previous).
                Object current = wrap.invoke(null, second);
                Object previous = wrap.invoke(null, first);
                Object phase = phaseConstructor.newInstance(current, previous,
                        settings.correct3dPeaks, settings.correct3dVerifyPeaks);
                if (!((Boolean) process.invoke(phase))) return null;
                Object best = getShift.invoke(phase);
                int[] contentMotion = (int[]) getPosition.invoke(best);
                return Transform.translation(contentMotion[0], contentMotion[1]);
            } catch (Exception e) {
                throw new IllegalStateException("Correct 3D Drift failed", e);
            }
        }

        @Override
        public void warmup(float[] from, float[] to, int width) {
            estimate(from, to, width);
        }
    }

    private static final class SiftEstimator implements PairEstimator {
        private final Constructor<?> paramConstructor;
        private final Constructor<?> siftCoreConstructor;
        private final Constructor<?> siftConstructor;
        private final Constructor<?> modelConstructor;
        private final Method extract;
        private final Method match;
        private final Method ransac;
        private final Method apply;
        private final ExternalSettings settings;

        SiftEstimator() throws Exception {
            this(ExternalSettings.defaults());
        }

        SiftEstimator(ExternalSettings settings) throws Exception {
            this.settings = settings;
            Class<?> param = Class.forName("mpicbg.imagefeatures.FloatArray2DSIFT$Param");
            Class<?> core = Class.forName("mpicbg.imagefeatures.FloatArray2DSIFT");
            Class<?> sift = Class.forName("mpicbg.ij.SIFT");
            Class<?> featureTransform = Class.forName("mpicbg.ij.FeatureTransform");
            Class<?> model = Class.forName("rigid".equals(settings.siftModel)
                    ? "mpicbg.models.RigidModel2D" : "mpicbg.models.TranslationModel2D");
            paramConstructor = param.getConstructor();
            siftCoreConstructor = core.getConstructor(param);
            siftConstructor = sift.getConstructor(core);
            modelConstructor = model.getConstructor();
            extract = sift.getMethod("extractFeatures", ImageProcessor.class, Collection.class);
            match = featureTransform.getMethod("matchFeatures", Collection.class,
                    Collection.class, List.class, float.class);
            ransac = model.getMethod("filterRansac", List.class, Collection.class, int.class,
                    double.class, double.class, int.class);
            apply = model.getMethod("applyInPlace", double[].class);
        }

        @Override
        public Transform estimate(float[] from, float[] to, int width) {
            try {
                Object param = paramConstructor.newInstance();
                set(param, "initialSigma", settings.siftInitialSigma);
                set(param, "steps", settings.siftSteps);
                set(param, "minOctaveSize", settings.siftMinOctave);
                set(param, "maxOctaveSize", settings.siftMaxOctave);
                set(param, "fdSize", settings.siftDescriptorSize);
                set(param, "fdBins", settings.siftDescriptorBins);
                Object sift = siftConstructor.newInstance(siftCoreConstructor.newInstance(param));
                List<Object> first = new ArrayList<>();
                List<Object> second = new ArrayList<>();
                extract.invoke(sift, processor(from, width), first);
                extract.invoke(sift, processor(to, width), second);
                List<Object> candidates = new ArrayList<>();
                match.invoke(null, first, second, candidates, settings.siftRod);
                if (candidates.isEmpty()) return null;
                Object model = modelConstructor.newInstance();
                List<Object> inliers = new ArrayList<>();
                boolean found = (Boolean) ransac.invoke(model, candidates, inliers,
                        settings.siftRansacIterations, settings.siftMaxEpsilon,
                        settings.siftMinInlierRatio, settings.siftMinInliers);
                if (!found) return null;
                return transformFromModel(model, apply, width, frameHeight(from, width));
            } catch (Exception e) {
                throw new IllegalStateException("SIFT failed", e);
            }
        }

        @Override
        public void warmup(float[] from, float[] to, int width) {
            estimate(from, to, width);
        }
    }

    /** Direct no-dialog adapter for Descriptor-based series registration. */
    private static final class DescriptorSeriesSolver implements Solver {
        private final Class<?> parametersType;
        private final Constructor<?> parameterConstructor;
        private final Constructor<?> translationConstructor;
        private final Constructor<?> rigidConstructor;
        private final Method register;
        private final ExternalSettings settings;

        DescriptorSeriesSolver() throws Exception {
            this(ExternalSettings.defaults());
        }

        DescriptorSeriesSolver(ExternalSettings settings) throws Exception {
            this.settings = settings;
            Class<?> parameters = Class.forName("plugin.DescriptorParameters");
            parametersType = parameters;
            Class<?> matching = Class.forName("process.Matching");
            parameterConstructor = parameters.getConstructor();
            translationConstructor = Class.forName(
                    "mpicbg.models.TranslationModel2D").getConstructor();
            rigidConstructor = Class.forName("mpicbg.models.RigidModel2D").getConstructor();
            register = matching.getMethod("descriptorBasedStackRegistration",
                    ImagePlus.class, parameters);
        }

        @Override
        public Reconciler.Solution solve(float[][] frames, int width) {
            try {
                Object parameters = parameterConstructor.newInstance();
                Field brightest = parametersType.getField("brightestNPoints");
                brightest.setInt(null, settings.descriptorBrightestPoints);
                set(parameters, "dimensionality", settings.descriptorDimensionality);
                set(parameters, "sigma1", settings.descriptorSigma1);
                set(parameters, "sigma2", settings.descriptorSigma2);
                set(parameters, "threshold", settings.descriptorThreshold);
                set(parameters, "localization", settings.descriptorLocalization);
                set(parameters, "lookForMaxima", settings.descriptorMaxima);
                set(parameters, "lookForMinima", settings.descriptorMinima);
                set(parameters, "model", "rigid".equals(settings.descriptorModel)
                        ? rigidConstructor.newInstance() : translationConstructor.newInstance());
                set(parameters, "similarOrientation", settings.descriptorSimilarOrientation);
                set(parameters, "numNeighbors", settings.descriptorNeighbors);
                set(parameters, "redundancy", settings.descriptorRedundancy);
                set(parameters, "significance", settings.descriptorSignificance);
                set(parameters, "ransacThreshold", settings.descriptorRansacThreshold);
                set(parameters, "channel1", settings.descriptorChannel1);
                set(parameters, "channel2", settings.descriptorChannel2);
                set(parameters, "regularize", settings.descriptorRegularize);
                set(parameters, "fixFirstTile", settings.descriptorFixFirstTile);
                set(parameters, "globalOpt", settings.descriptorGlobalOptimization);
                set(parameters, "range", settings.descriptorRange);
                set(parameters, "setPointsRois", settings.descriptorSetPointRois);
                set(parameters, "silent", settings.descriptorSilent);
                set(parameters, "fuse", settings.descriptorFuse);
                set(parameters, "interpolation", settings.descriptorInterpolation);

                int height = frameHeight(frames[0], width);
                ij.ImageStack planes = new ij.ImageStack(width, height);
                for (int t = 0; t < frames.length; t++) planes.addSlice("t" + (t + 1), processor(frames[t], width));
                ImagePlus stack = new ImagePlus("descriptor input", planes);
                stack.setDimensions(1, 1, frames.length);
                stack.setOpenAsHyperStack(true);
                Object value;
                try {
                    value = register.invoke(null, stack, parameters);
                } finally {
                    stack.close();
                }
                if (!(value instanceof List)) {
                    throw new IllegalStateException("descriptor plugin returned no models");
                }
                List<?> models = (List<?>) value;
                if (models.size() != frames.length) {
                    throw new IllegalStateException("descriptor plugin returned " + models.size()
                            + " models for " + frames.length + " frames");
                }
                Transform[] cumulative = new Transform[frames.length];
                for (int t = 0; t < cumulative.length; t++) {
                    Object model = models.get(t);
                    if (model == null) {
                        cumulative[t] = Transform.IDENTITY;
                        continue;
                    }
                    Method apply = model.getClass().getMethod("applyInPlace", double[].class);
                    Transform correction = transformFromModel(model, apply, width, height);
                    // The plugin returns the correction to the reference; benchmark transforms
                    // describe the observed content displacement, so invert the rigid transform.
                    cumulative[t] = correction.inverse();
                }
                return Reconciler.fixed(cumulative, 0);
            } catch (Exception e) {
                throw new IllegalStateException("Descriptor-based series registration failed", e);
            }
        }

        @Override
        public void warmup(float[][] frames, int width) {
            solve(new float[][]{frames[0], frames[1]}, width);
        }
    }

    /** Best-fitting rigid map from {@code from} points to {@code to} points. */
    private static Transform fittedRigid(double[][] from, double[][] to, int width) {
        return fittedRigid(from, to, width, width);
    }

    private static Transform fittedRigid(double[][] from, double[][] to, int width, int height) {
        if (from.length == 0 || to.length != from.length) return null;
        if (from.length == 1) {
            return Transform.translation(to[0][0] - from[0][0], to[0][1] - from[0][1]);
        }
        double fromX = 0;
        double fromY = 0;
        double toX = 0;
        double toY = 0;
        for (int i = 0; i < from.length; i++) {
            fromX += from[i][0];
            fromY += from[i][1];
            toX += to[i][0];
            toY += to[i][1];
        }
        fromX /= from.length;
        fromY /= from.length;
        toX /= from.length;
        toY /= from.length;
        double dot = 0;
        double cross = 0;
        for (int i = 0; i < from.length; i++) {
            double ax = from[i][0] - fromX;
            double ay = from[i][1] - fromY;
            double bx = to[i][0] - toX;
            double by = to[i][1] - toY;
            dot += ax * bx + ay * by;
            cross += ax * by - ay * bx;
        }
        double theta = Math.atan2(cross, dot);
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        double interceptX = toX - (cos * fromX - sin * fromY);
        double interceptY = toY - (sin * fromX + cos * fromY);
        return transformFromAffine(cos, -sin, interceptX, sin, cos, interceptY, width, height);
    }

    /** Read a 2-D model through its public apply method without depending on its concrete class. */
    private static Transform transformFromModel(Object model, Method apply, int width)
            throws Exception {
        return transformFromModel(model, apply, width, width);
    }

    private static Transform transformFromModel(Object model, Method apply, int width, int height)
            throws Exception {
        double[] p0 = {0, 0};
        double[] px = {1, 0};
        double[] py = {0, 1};
        apply.invoke(model, (Object) p0);
        apply.invoke(model, (Object) px);
        apply.invoke(model, (Object) py);
        return transformFromAffine(px[0] - p0[0], py[0] - p0[0], p0[0],
                px[1] - p0[1], py[1] - p0[1], p0[1], width, height);
    }

    /** Convert {@code q = A p + b} to Transform's rotate-about-centre convention. */
    private static Transform transformFromAffine(double m00, double m01, double b0,
                                                 double m10, double m11, double b1, int width) {
        return transformFromAffine(m00, m01, b0, m10, m11, b1, width, width);
    }

    private static Transform transformFromAffine(double m00, double m01, double b0,
                                                 double m10, double m11, double b1, int width, int height) {
        double theta = Math.atan2(m10, m00);
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        double center = (width - 1) / 2.0;
        double centerY = (height - 1) / 2.0;
        double rotatedCenterX = cos * center - sin * centerY;
        double rotatedCenterY = sin * center + cos * centerY;
        return new Transform(b0 - center + rotatedCenterX,
                b1 - centerY + rotatedCenterY, theta);
    }

    private static FloatProcessor processor(float[] pixels, int width) {
        return new FloatProcessor(width, frameHeight(pixels, width), pixels.clone(), null);
    }

    private static int frameHeight(float[] pixels, int width) {
        if (width <= 0 || pixels.length == 0 || pixels.length % width != 0)
            throw new IllegalArgumentException("Frame dimensions do not match its pixels");
        return pixels.length / width;
    }

    private static Method declared(Class<?> type, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getField(name);
        field.set(target, value);
    }
}
