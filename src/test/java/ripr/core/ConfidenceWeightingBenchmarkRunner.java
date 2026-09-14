/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import ripr.ControlledMotionProfile;
import ripr.ThevenazProtocolBenchmark;
import ripr.api.AutomaticReconciliationSelector;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Resumable, source-balanced runner for the confidence-weighting evidence protocol. */
final class ConfidenceWeightingBenchmarkRunner {

    static final int FRAMES = 48;
    static final int[] LAGS = {1, 2, 4, 8, 16};
    private static final int PAIR_MAGIC = 0x43575032; // CWP2
    private static final int PAIR_VERSION = 1;
    private static final String ROUND = "library/benchmark/v2/benchmarks/"
            + "confidence_weighted_reconciliation_v1/tuning/rounds/R01_full_evidence";

    enum Scope { TRANSLATION, RIGID }

    private ConfidenceWeightingBenchmarkRunner() {
    }

    static boolean run(Path requestedOutput) throws IOException {
        Path project = findProjectRoot(requestedOutput);
        Path round = project.resolve(ROUND);
        ConfidenceWeightingBenchmark.Split split = splitProperty();
        boolean smoke = Boolean.getBoolean("confidenceWeighting.smoke");
        String attempt = System.getProperty("confidenceWeighting.attempt",
                smoke ? "SMOKE" : "A000");
        Path output = outputPath(project, round, requestedOutput, split, attempt, smoke);
        Policy policy = Policy.fromSystemProperties();
        if (!smoke) ensureSplitAuthorized(round, split, policy);
        List<Source> sources = sources(project, round, split);
        if (smoke && sources.size() > 1) sources = new ArrayList<>(sources.subList(0, 1));
        assertBalanced(sources, split, smoke);
        Files.createDirectories(output.resolve("recordings"));
        Files.createDirectories(round.resolve("pair_cache"));
        writeManifest(project, round, output, split, attempt, policy, sources, smoke);

        int complete = 0;
        outer:
        for (Source source : sources) {
            Base base = loadBase(source.path);
            for (ControlledMotionProfile profile : ControlledMotionProfile.values()) {
                for (Scope scope : Scope.values()) {
                    for (PairEstimator.Kind estimator : new PairEstimator.Kind[]{
                            PairEstimator.Kind.LOG_RATIO_FIT,
                            PairEstimator.Kind.AREA_CORRELATION}) {
                        String id = recordingId(source, profile, scope, estimator);
                        Path directory = output.resolve("recordings").resolve(id);
                        if (complete(directory, policy)) {
                            complete++;
                        } else {
                            runRecording(round, directory, source, base, profile, scope,
                                    estimator, policy);
                            complete++;
                        }
                        System.out.printf(Locale.ROOT, "confidence benchmark %s: %d complete%n",
                                split.name().toLowerCase(Locale.ROOT), complete);
                        if (smoke) break outer;
                    }
                }
            }
        }
        aggregate(output);
        return true;
    }

    private static Path outputPath(Path project, Path round, Path requested,
                                   ConfidenceWeightingBenchmark.Split split,
                                   String attempt, boolean smoke) {
        String explicit = System.getProperty("confidenceWeighting.output");
        if (explicit != null && !explicit.trim().isEmpty()) {
            Path value = project.resolve(explicit).normalize();
            if (!value.startsWith(project)) {
                throw new IllegalArgumentException("benchmark output leaves the project");
            }
            return value;
        }
        if (requested != null) {
            Path absoluteRequested = requested.isAbsolute()
                    ? requested.normalize() : project.resolve(requested).normalize();
            if (!absoluteRequested.startsWith(project.resolve("target"))) {
                return absoluteRequested;
            }
        }
        return round.resolve("runs")
                .resolve(smoke ? "smoke" : split.name().toLowerCase(Locale.ROOT))
                .resolve(attempt);
    }

    private static ConfidenceWeightingBenchmark.Split splitProperty() {
        String value = System.getProperty("confidenceWeighting.split", "development");
        return ConfidenceWeightingBenchmark.Split.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    private static Path findProjectRoot(Path requested) {
        Path current = Paths.get("").toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("pom.xml"))) {
            current = current.getParent();
        }
        if (current == null && requested != null) {
            current = requested.toAbsolutePath().normalize();
            while (current != null && !Files.isRegularFile(current.resolve("pom.xml"))) {
                current = current.getParent();
            }
        }
        if (current == null) throw new IllegalStateException("cannot locate project pom.xml");
        return current;
    }

    private static final class Source {
        final String id;
        final String category;
        final String acquisition;
        final Path path;
        final String sha256;
        final ConfidenceWeightingBenchmark.Split split;

        Source(String id, String category, String acquisition, Path path, String sha256,
               ConfidenceWeightingBenchmark.Split split) {
            this.id = id;
            this.category = category;
            this.acquisition = acquisition;
            this.path = path;
            this.sha256 = sha256;
            this.split = split;
        }
    }

    private static List<Source> sources(Path project, Path round,
                                        ConfidenceWeightingBenchmark.Split split)
            throws IOException {
        List<Source> out = split == ConfidenceWeightingBenchmark.Split.DEVELOPMENT
                ? developmentSources(project) : freshSources(round, split);
        out.sort(Comparator.comparing((Source source) -> source.category)
                .thenComparing(source -> source.id));
        List<ConfidenceWeightingBenchmark.RecordingKey> keys = new ArrayList<>();
        for (Source source : out) {
            keys.add(new ConfidenceWeightingBenchmark.RecordingKey(
                    source.id, source.acquisition, source.split));
        }
        ConfidenceWeightingBenchmark.assertSourceIsolation(keys);
        return out;
    }

    private static List<Source> developmentSources(Path project) throws IOException {
        Path controlled = project.resolve(
                "library/benchmark/v2/benchmarks/controlled_motion");
        List<Source> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> categories = Files.list(controlled)) {
            for (Path category : (Iterable<Path>) categories.filter(Files::isDirectory)
                    .sorted()::iterator) {
                String categoryName = category.getFileName().toString();
                if (!isCategory(categoryName)) continue;
                try (java.util.stream.Stream<Path> acquisitions = Files.list(category)) {
                    for (Path acquisition : (Iterable<Path>) acquisitions.filter(Files::isDirectory)
                            .sorted()::iterator) {
                        Path seed = acquisition.resolve("CURVED_OSCILLATING_DRIFT")
                                .resolve("CLEAN").resolve("00_input_uncorrected.tif");
                        if (!Files.isRegularFile(seed)) continue;
                        String id = acquisition.getFileName().toString();
                        out.add(new Source(id, categoryName, id, seed, sha256(seed),
                                ConfidenceWeightingBenchmark.Split.DEVELOPMENT));
                    }
                }
            }
        }
        return out;
    }

    private static List<Source> freshSources(Path round,
                                             ConfidenceWeightingBenchmark.Split split)
            throws IOException {
        List<String> lines = Files.readAllLines(round.resolve("sources/source_manifest.csv"),
                StandardCharsets.UTF_8);
        if (lines.isEmpty()) throw new IOException("empty source manifest");
        String[] header = csv(lines.get(0));
        Map<String, Integer> columns = index(header);
        List<Source> out = new ArrayList<>();
        String wanted = split.name().toLowerCase(Locale.ROOT);
        for (int row = 1; row < lines.size(); row++) {
            if (lines.get(row).trim().isEmpty()) continue;
            String[] values = csv(lines.get(row));
            if (!wanted.equals(value(values, columns, "split"))) continue;
            Path prepared = round.resolve(value(values, columns, "prepared_path")).normalize();
            String expected = value(values, columns, "prepared_sha256");
            String actual = sha256(prepared);
            if (!expected.equals(actual)) {
                throw new IOException("prepared source hash changed: " + prepared);
            }
            out.add(new Source(value(values, columns, "case_id"),
                    value(values, columns, "category"),
                    value(values, columns, "acquisition_group"), prepared, actual, split));
        }
        return out;
    }

    private static boolean isCategory(String value) {
        return value.equals("PHASE") || value.equals("BRIGHTFIELD_DIC")
                || value.equals("DENSE_FLUOR") || value.equals("SPARSE_LOWLIGHT")
                || value.equals("FIDUCIAL_STATIC");
    }

    private static void assertBalanced(List<Source> sources,
                                       ConfidenceWeightingBenchmark.Split split,
                                       boolean smoke) {
        if (smoke) {
            if (sources.size() != 1) throw new IllegalStateException("smoke needs one source");
            return;
        }
        int expected = split == ConfidenceWeightingBenchmark.Split.DEVELOPMENT ? 4 : 2;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Source source : sources) counts.merge(source.category, 1, Integer::sum);
        for (String category : Arrays.asList("PHASE", "BRIGHTFIELD_DIC", "DENSE_FLUOR",
                "SPARSE_LOWLIGHT", "FIDUCIAL_STATIC")) {
            if (counts.getOrDefault(category, 0) != expected) {
                throw new IllegalStateException(category + " has "
                        + counts.getOrDefault(category, 0) + " sources, expected " + expected);
            }
        }
    }

    private static final class Base {
        final float[] pixels;
        final int width;
        final int height;

        Base(float[] pixels, int width, int height) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
        }
    }

    private static Base loadBase(Path path) throws IOException {
        ImagePlus image = IJ.openImage(path.toString());
        if (image == null) throw new IOException("ImageJ could not open " + path);
        try {
            ImageProcessor processor = image.getStack().getProcessor(1).convertToFloatProcessor();
            return new Base(((float[]) processor.getPixels()).clone(),
                    processor.getWidth(), processor.getHeight());
        } finally {
            image.close();
        }
    }

    private static final class Generated implements FrameSource {
        final float[][] planes;
        final Transform[] truth;
        final int width;
        final int height;

        Generated(float[][] planes, Transform[] truth, int width, int height) {
            this.planes = planes;
            this.truth = truth;
            this.width = width;
            this.height = height;
        }

        @Override public int count() { return planes.length; }
        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public float[] plane(int frame) { return planes[frame]; }
    }

    private static Generated generate(Base base, ControlledMotionProfile profile, Scope scope) {
        double[] coefficients = ThevenazProtocolBenchmark.spline7Coefficients(
                base.pixels, base.width, base.height);
        Transform[] translations = profile.translationTrajectory();
        Transform[] truth = new Transform[FRAMES];
        float[][] planes = new float[FRAMES][];
        for (int frame = 0; frame < FRAMES; frame++) {
            double theta = scope == Scope.RIGID ? rigidAngle(profile, frame) : 0.0;
            truth[frame] = new Transform(translations[frame].dx, translations[frame].dy, theta);
            planes[frame] = ThevenazProtocolBenchmark.spline7Warp(
                    coefficients, base.width, base.height, truth[frame].inverse());
        }
        return new Generated(planes, truth, base.width, base.height);
    }

    private static double rigidAngle(ControlledMotionProfile profile, int frame) {
        double u = frame / (double) (FRAMES - 1);
        switch (profile) {
            case CURVED_OSCILLATING_DRIFT:
                return Math.toRadians(1.2 * Math.sin(2 * Math.PI * u) + 0.5 * u);
            case STEADY_DIRECTIONAL_DRIFT:
                return Math.toRadians(1.6 * u);
            case SUBPIXEL_RANDOM_WALK:
                java.util.Random random = new java.util.Random(2_026_082_5L);
                double angle = 0;
                for (int i = 1; i <= frame; i++) angle += Math.toRadians(0.08 * random.nextGaussian());
                return angle;
            case INTERMITTENT_JUMPS:
            default:
                double degrees = 0.4 * u;
                if (frame >= 12) degrees += 0.8;
                if (frame >= 27) degrees -= 1.4;
                if (frame >= 39) degrees += 1.0;
                return Math.toRadians(degrees);
        }
    }

    private static String recordingId(Source source, ControlledMotionProfile profile,
                                      Scope scope, PairEstimator.Kind estimator) {
        return safe(source.id) + "__" + profile.name() + "__"
                + scope.name() + "__" + estimator.name();
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]+", "_");
    }

    private static boolean complete(Path directory, Policy policy) throws IOException {
        Path marker = directory.resolve("complete.properties");
        if (!Files.isRegularFile(marker)) return false;
        Properties properties = loadProperties(marker);
        return policy.id().equals(properties.getProperty("policy_id"))
                && Files.isRegularFile(directory.resolve("recording_summary.csv"))
                && Files.isRegularFile(directory.resolve("pair_trials.csv"))
                && Files.isRegularFile(directory.resolve("selector_features.csv"));
    }

    static final class Policy {
        final double minimumStd;
        final double maximumStd;
        final Reconciler.InformationNormalizer normalizer;
        final double robustConstant;
        final double robustFloor;
        final int robustIterations;

        Policy(double minimumStd, double maximumStd,
               Reconciler.InformationNormalizer normalizer,
               double robustConstant, double robustFloor, int robustIterations) {
            this.minimumStd = minimumStd;
            this.maximumStd = maximumStd;
            this.normalizer = normalizer;
            this.robustConstant = robustConstant;
            this.robustFloor = robustFloor;
            this.robustIterations = robustIterations;
            validate();
        }

        static Policy fromSystemProperties() {
            return new Policy(
                    number("confidenceWeighting.minimumStd", 0.02),
                    number("confidenceWeighting.maximumStd", 20.0),
                    Reconciler.InformationNormalizer.valueOf(System.getProperty(
                            "confidenceWeighting.normalizer", "MEDIAN").toUpperCase(Locale.ROOT)),
                    number("confidenceWeighting.robustConstant", 1.345),
                    number("confidenceWeighting.robustFloor", 0.05),
                    integer("confidenceWeighting.robustIterations", 8));
        }

        private void validate() {
            if (!(minimumStd > 0) || !(maximumStd >= minimumStd)
                    || !(robustConstant > 0) || !(robustFloor > 0 && robustFloor <= 1)
                    || robustIterations < 1) {
                throw new IllegalArgumentException("invalid confidence-weighting policy");
            }
        }

        String id() {
            return String.format(Locale.ROOT, "min_%s__max_%s__norm_%s__c_%s__floor_%s__iter_%d",
                    token(minimumStd), token(maximumStd), normalizer,
                    token(robustConstant), token(robustFloor), robustIterations);
        }

        Reconciler.Options reconciliation(ConfidenceWeightingBenchmark.Arm arm, Scope scope,
                                          int width, int height) {
            Reconciler.Options out = new Reconciler.Options(arm.weighting,
                    scope == Scope.RIGID ? 3 : 2,
                    PairUncertainty.rmsRadius(width, height));
            out.informationNormalizer = normalizer;
            out.robustLossConstant = robustConstant;
            out.minimumRobustFactor = robustFloor;
            out.maximumRobustIterations = robustIterations;
            return out;
        }

        private static String token(double value) {
            return Double.toString(value).replace('-', 'm').replace('.', 'p');
        }
    }

    private static double number(String name, double fallback) {
        return Double.parseDouble(System.getProperty(name, Double.toString(fallback)));
    }

    private static int integer(String name, int fallback) {
        return Integer.parseInt(System.getProperty(name, Integer.toString(fallback)));
    }

    private static void runRecording(
            Path round, Path directory, Source source, Base base,
            ControlledMotionProfile profile, Scope scope, PairEstimator.Kind estimator,
            Policy policy) throws IOException {
        Files.createDirectories(directory);
        Generated generated = generate(base, profile, scope);
        String id = recordingId(source, profile, scope, estimator);
        Path cache = pairCache(round, source, profile, scope, estimator, policy);
        Path cacheTiming = cache.resolveSibling(cache.getFileName() + ".properties");
        List<Registration.PairResult> pairs;
        long pairNanos;
        if (Files.isRegularFile(cache) && Files.isRegularFile(cacheTiming)) {
            pairs = readPairs(cache);
            pairNanos = Long.parseLong(loadProperties(cacheTiming)
                    .getProperty("pair_estimation_nanos"));
        } else {
            Registration.Options options = registrationOptions(scope, estimator, policy);
            long start = System.nanoTime();
            Registration.Result result = Registration.run(generated, options,
                    PairScheduler.Progress.NONE, PairScheduler.Cancellation.NEVER);
            pairNanos = System.nanoTime() - start;
            pairs = immutableFits(result.pairs);
            writePairs(cache, pairs);
            Properties timing = new Properties();
            timing.setProperty("pair_estimation_nanos", Long.toString(pairNanos));
            timing.setProperty("pair_fit_hash", ConfidenceWeightingBenchmark.pairFitHash(pairs));
            storeProperties(cacheTiming, timing);
            List<Registration.PairResult> restored = readPairs(cache);
            if (!ConfidenceWeightingBenchmark.pairFitHash(pairs).equals(
                    ConfidenceWeightingBenchmark.pairFitHash(restored))) {
                throw new IOException("pair cache round-trip changed " + id);
            }
            pairs = restored;
        }
        String pairHash = ConfidenceWeightingBenchmark.pairFitHash(pairs);
        writeTruth(directory.resolve("truth.csv"), generated.truth);

        List<String> summaries = new ArrayList<>();
        summaries.add("recording_id,source_id,category,acquisition_group,split,profile,scope,"
                + "estimator,arm,median_error_px,maximum_error_px,mean_error_px,failed_pairs,"
                + "bound_pairs,repaired_frames,unavailable_uncertainty_pairs,covariance_floor_pairs,"
                + "covariance_cap_pairs,downweighted_pairs,robust_converged,pair_time_ms,"
                + "reconciliation_time_ms,total_time_ms,pair_fit_hash");
        List<String> pairRows = new ArrayList<>();
        pairRows.add("recording_id,arm,pair_index,from_frame,to_frame,lag,pair_error_px,status,"
                + "uncertainty_available,uncertainty_floored,uncertainty_capped,peak_ambiguity,"
                + "standardized_graph_residual,robust_factor,final_information_scale,factor_floored,"
                + "uncertainty_fallback,robust_converged,pair_fit_hash");

        Reconciler.Solution equal = null;
        for (ConfidenceWeightingBenchmark.Arm arm : ConfidenceWeightingBenchmark.Arm.values()) {
            ArmResult result = reconcileRepeated(pairs, generated.truth, arm, scope, policy,
                    generated.width, generated.height);
            if (arm == ConfidenceWeightingBenchmark.Arm.EQUAL) equal = result.solution;
            if (!pairHash.equals(ConfidenceWeightingBenchmark.pairFitHash(pairs))) {
                throw new IllegalStateException("pair fits changed while running " + arm);
            }
            summaries.add(summaryRow(id, source, profile, scope, estimator, arm, result,
                    pairs, pairNanos, pairHash));
            addPairRows(pairRows, id, arm, pairs, generated.truth, result.solution,
                    generated.width, generated.height, pairHash);
        }
        if (equal == null) throw new AssertionError("equal arm did not run");
        writeSelector(directory.resolve("selector_features.csv"), id,
                AutomaticReconciliationSelector.measure(pairs, equal, estimator,
                        scope == Scope.RIGID));
        atomicWrite(directory.resolve("recording_summary.csv"), summaries);
        atomicWrite(directory.resolve("pair_trials.csv"), pairRows);
        Properties done = new Properties();
        done.setProperty("recording_id", id);
        done.setProperty("pair_fit_hash", pairHash);
        done.setProperty("policy_id", policy.id());
        done.setProperty("completed_utc", Instant.now().toString());
        storeProperties(directory.resolve("complete.properties"), done);
    }

    private static Registration.Options registrationOptions(
            Scope scope, PairEstimator.Kind estimator, Policy policy) {
        Registration.Options options = Registration.Options.recommendedFor(
                Reconciler.Reference.MULTILAG);
        options.lags = LAGS.clone();
        options.estimator = estimator;
        options.reconciliationWeighting = Reconciler.Weighting.EQUAL;
        options.aligner.fitRotation = scope == Scope.RIGID;
        options.aligner.uncertaintyMinimumStdPixels = policy.minimumStd;
        options.aligner.uncertaintyMaximumStdPixels = policy.maximumStd;
        options.threads = integer("confidenceWeighting.threads", 0);
        if (scope == Scope.RIGID) {
            options.incrementalRotation = true;
            options.lagAwareWarmStarts = true;
            options.minimumRotationResidualGain = 0.005;
        }
        return options;
    }

    private static List<Registration.PairResult> immutableFits(
            List<Registration.PairResult> source) {
        List<Registration.PairResult> out = new ArrayList<>(source.size());
        for (Registration.PairResult pair : source) {
            out.add(new Registration.PairResult(pair.from, pair.to, pair.fit));
        }
        return out;
    }

    private static final class ArmResult {
        final Reconciler.Solution solution;
        final ChainRepair.Result repaired;
        final double[] errors;
        final long reconciliationNanos;

        ArmResult(Reconciler.Solution solution, ChainRepair.Result repaired,
                  double[] errors, long reconciliationNanos) {
            this.solution = solution;
            this.repaired = repaired;
            this.errors = errors;
            this.reconciliationNanos = reconciliationNanos;
        }
    }

    private static ArmResult reconcileRepeated(
            List<Registration.PairResult> pairs, Transform[] truth,
            ConfidenceWeightingBenchmark.Arm arm, Scope scope, Policy policy,
            int width, int height) {
        Reconciler.Solution first = null;
        ChainRepair.Result repaired = null;
        long total = 0;
        for (int repeat = 0; repeat < 3; repeat++) {
            long start = System.nanoTime();
            Reconciler.Solution current = reconcile(pairs, arm, scope, policy, width, height);
            ChainRepair.Result currentRepair = ChainRepair.repair(current.cumulative,
                    current.support, 0, ChainRepair.DEFAULT_OUTLIER_MADS, width, height);
            total += System.nanoTime() - start;
            if (first == null) {
                first = current;
                repaired = currentRepair;
            } else {
                assertSameTrajectory(first.cumulative, current.cumulative);
                assertSameTrajectory(repaired.cumulative, currentRepair.cumulative);
            }
        }
        double radius = PairUncertainty.rmsRadius(width, height);
        double[] errors = new double[truth.length];
        for (int frame = 0; frame < truth.length; frame++) {
            errors[frame] = ConfidenceWeightingBenchmark.warpingIndex(
                    truth[frame], repaired.cumulative[frame], radius);
        }
        return new ArmResult(first, repaired, errors, total / 3);
    }

    private static Reconciler.Solution reconcile(
            List<Registration.PairResult> pairs, ConfidenceWeightingBenchmark.Arm arm,
            Scope scope, Policy policy, int width, int height) {
        List<Reconciler.Observation> observations = new ArrayList<>();
        for (int index = 0; index < pairs.size(); index++) {
            Registration.PairResult pair = pairs.get(index);
            if (pair.fit != null && pair.fit.usable()) {
                observations.add(new Reconciler.Observation(pair.from, pair.to,
                        pair.fit.transform, pair.fit.uncertainty, index));
            }
        }
        return Reconciler.multiLag(FRAMES, observations,
                policy.reconciliation(arm, scope, width, height));
    }

    private static void assertSameTrajectory(Transform[] expected, Transform[] actual) {
        if (expected.length != actual.length) throw new AssertionError("trajectory length changed");
        for (int frame = 0; frame < expected.length; frame++) {
            Transform a = expected[frame];
            Transform b = actual[frame];
            if (Double.doubleToLongBits(a.dx) != Double.doubleToLongBits(b.dx)
                    || Double.doubleToLongBits(a.dy) != Double.doubleToLongBits(b.dy)
                    || Double.doubleToLongBits(a.theta) != Double.doubleToLongBits(b.theta)) {
                throw new AssertionError("non-deterministic reconciliation at frame " + frame);
            }
        }
    }

    private static String summaryRow(
            String id, Source source, ControlledMotionProfile profile, Scope scope,
            PairEstimator.Kind estimator, ConfidenceWeightingBenchmark.Arm arm,
            ArmResult result, List<Registration.PairResult> pairs, long pairNanos,
            String pairHash) {
        int failed = 0;
        int bound = 0;
        int unavailable = 0;
        int floored = 0;
        int capped = 0;
        for (Registration.PairResult pair : pairs) {
            PairAligner.Fit fit = pair.fit;
            if (fit == null || !fit.usable()) failed++;
            if (fit != null && isBound(fit.status)) bound++;
            PairUncertainty uncertainty = fit == null ? null : fit.uncertainty;
            if (uncertainty == null || !uncertainty.available()) unavailable++;
            else {
                if (uncertainty.eigenvalueFloored) floored++;
                if (uncertainty.eigenvalueCapped) capped++;
            }
        }
        int downweighted = 0;
        for (Reconciler.PairInfluence influence : result.solution.influences) {
            if (influence.used && influence.robustFactor < 1.0 - 1e-15) downweighted++;
        }
        double pairMs = pairNanos / 1e6;
        double reconcileMs = result.reconciliationNanos / 1e6;
        return csvRow(id, source.id, source.category, source.acquisition,
                source.split.name().toLowerCase(Locale.ROOT), profile.name(), scope.name(),
                estimator.name(), arm.name(), f(median(result.errors)), f(max(result.errors)),
                f(mean(result.errors)), failed, bound, result.repaired.repairedCount, unavailable,
                floored, capped, downweighted, result.solution.robustConverged,
                f(pairMs), f(reconcileMs), f(pairMs + reconcileMs), pairHash);
    }

    private static boolean isBound(PairAligner.Status status) {
        return status == PairAligner.Status.AT_SHIFT_BOUND
                || status == PairAligner.Status.AT_ROTATION_BOUND
                || status == PairAligner.Status.AT_SHIFT_AND_ROTATION_BOUND;
    }

    private static void addPairRows(
            List<String> rows, String id, ConfidenceWeightingBenchmark.Arm arm,
            List<Registration.PairResult> pairs, Transform[] truth,
            Reconciler.Solution solution, int width, int height, String pairHash) {
        Map<Integer, Reconciler.PairInfluence> influenceByPlan = new LinkedHashMap<>();
        for (Reconciler.PairInfluence influence : solution.influences) {
            influenceByPlan.put(influence.planIndex, influence);
        }
        double radius = PairUncertainty.rmsRadius(width, height);
        for (int index = 0; index < pairs.size(); index++) {
            Registration.PairResult pair = pairs.get(index);
            PairAligner.Fit fit = pair.fit;
            Transform pairTruth = truth[pair.from].inverse().then(truth[pair.to]);
            double error = fit == null ? Double.NaN
                    : ConfidenceWeightingBenchmark.warpingIndex(pairTruth, fit.transform, radius);
            PairUncertainty uncertainty = fit == null ? null : fit.uncertainty;
            Reconciler.PairInfluence influence = influenceByPlan.get(index);
            rows.add(csvRow(id, arm.name(), index, pair.from, pair.to, pair.lag(), f(error),
                    fit == null ? "MISSING" : fit.status.name(),
                    uncertainty != null && uncertainty.available(),
                    uncertainty != null && uncertainty.eigenvalueFloored,
                    uncertainty != null && uncertainty.eigenvalueCapped,
                    f(uncertainty == null ? Double.NaN : uncertainty.peakAmbiguity),
                    f(influence == null ? Double.NaN : influence.standardizedResidual),
                    f(influence == null ? Double.NaN : influence.robustFactor),
                    f(influence == null ? Double.NaN : influence.finalInformationScale),
                    influence != null && influence.factorFloored,
                    influence != null && influence.uncertaintyFallback,
                    influence == null || influence.robustConverged, pairHash));
        }
    }

    private static void writeTruth(Path path, Transform[] truth) throws IOException {
        List<String> rows = new ArrayList<>();
        rows.add("frame,dx,dy,theta_radians");
        for (int frame = 0; frame < truth.length; frame++) {
            rows.add(csvRow(frame, f(truth[frame].dx), f(truth[frame].dy),
                    f(truth[frame].theta)));
        }
        atomicWrite(path, rows);
    }

    private static void writeSelector(
            Path path, String recordingId, AutomaticReconciliationSelector.Evidence evidence)
            throws IOException {
        ConfidenceWeightingBenchmark.assertTruthFreeSelectorColumns(
                AutomaticReconciliationSelector.FEATURE_NAMES);
        StringBuilder header = new StringBuilder("recording_id");
        StringBuilder values = new StringBuilder(recordingId);
        double[] vector = evidence.vector();
        for (int index = 0; index < AutomaticReconciliationSelector.FEATURE_NAMES.length; index++) {
            header.append(',').append(AutomaticReconciliationSelector.FEATURE_NAMES[index]);
            values.append(',').append(f(vector[index]));
        }
        atomicWrite(path, Arrays.asList(header.toString(), values.toString()));
    }

    private static Path pairCache(
            Path round, Source source, ControlledMotionProfile profile, Scope scope,
            PairEstimator.Kind estimator, Policy policy) throws IOException {
        String identity = source.sha256 + "\n" + profile.name() + "\n" + scope.name()
                + "\n" + estimator.name() + "\n" + policy.minimumStd + "\n"
                + policy.maximumStd + "\nframes=" + FRAMES + "\nlags=" + Arrays.toString(LAGS)
                + "\ngenerator=thevenaz-spline7-v1\nrotation=incremental-0.005"
                + "\nregistration=" + sha256(findProjectRoot(round).resolve(
                        "src/main/java/ripr/core/Registration.java"))
                + "\npair_aligner=" + sha256(findProjectRoot(round).resolve(
                        "src/main/java/ripr/core/PairAligner.java"))
                + "\narea_correlation=" + sha256(findProjectRoot(round).resolve(
                        "src/main/java/ripr/core/AreaCorrelation.java"))
                + "\npair_uncertainty=" + sha256(findProjectRoot(round).resolve(
                        "src/main/java/ripr/core/PairUncertainty.java"))
                + "\ngenerator_code=" + sha256(findProjectRoot(round).resolve(
                        "src/test/java/ripr/ThevenazProtocolBenchmark.java"))
                + "\nprofile_code=" + sha256(findProjectRoot(round).resolve(
                        "src/test/java/ripr/ControlledMotionProfile.java"));
        String key = sha256(identity.getBytes(StandardCharsets.UTF_8));
        return round.resolve("pair_cache").resolve(key + ".pairs");
    }

    static void writePairs(Path path, List<Registration.PairResult> pairs) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = temporary(path);
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(temporary)))) {
            output.writeInt(PAIR_MAGIC);
            output.writeInt(PAIR_VERSION);
            output.writeInt(pairs.size());
            for (Registration.PairResult pair : pairs) {
                output.writeInt(pair.from);
                output.writeInt(pair.to);
                PairAligner.Fit fit = pair.fit;
                output.writeBoolean(fit != null);
                if (fit == null) continue;
                writeTransform(output, fit.transform);
                output.writeDouble(fit.residualBefore);
                output.writeDouble(fit.residualAfter);
                output.writeDouble(fit.logGain);
                output.writeDouble(fit.validFraction);
                output.writeInt(fit.iterations);
                output.writeInt(fit.status.ordinal());
                output.writeBoolean(fit.rotationEvidence != null);
                if (fit.rotationEvidence != null) {
                    PairAligner.RotationEvidence rotation = fit.rotationEvidence;
                    output.writeDouble(rotation.residualGain);
                    output.writeDouble(rotation.translationResidual);
                    output.writeDouble(rotation.rigidResidual);
                    output.writeDouble(rotation.proposedAngleRadians);
                    output.writeBoolean(rotation.accepted);
                }
                PairUncertainty uncertainty = fit.uncertainty == null
                        ? PairUncertainty.unavailable(
                                fit.transform.theta == 0.0 ? 2 : 3) : fit.uncertainty;
                output.writeInt(uncertainty.dimensions);
                output.writeBoolean(uncertainty.available());
                if (uncertainty.available()) {
                    for (double value : uncertainty.covariance()) output.writeDouble(value);
                }
                output.writeDouble(uncertainty.peakAmbiguity);
                output.writeBoolean(uncertainty.calibrated);
                output.writeBoolean(uncertainty.eigenvalueFloored);
                output.writeBoolean(uncertainty.eigenvalueCapped);
            }
        }
        atomicMove(temporary, path);
    }

    static List<Registration.PairResult> readPairs(Path path) throws IOException {
        List<Registration.PairResult> pairs = new ArrayList<>();
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(path)))) {
            if (input.readInt() != PAIR_MAGIC || input.readInt() != PAIR_VERSION) {
                throw new IOException("unsupported pair cache " + path);
            }
            int count = input.readInt();
            if (count < 0 || count > 1_000_000) throw new IOException("invalid pair count");
            for (int index = 0; index < count; index++) {
                int from = input.readInt();
                int to = input.readInt();
                if (!input.readBoolean()) {
                    pairs.add(new Registration.PairResult(from, to, null));
                    continue;
                }
                Transform transform = readTransform(input);
                double residualBefore = input.readDouble();
                double residualAfter = input.readDouble();
                double logGain = input.readDouble();
                double validFraction = input.readDouble();
                int iterations = input.readInt();
                PairAligner.Status status = enumValue(PairAligner.Status.values(), input.readInt(),
                        "pair status");
                PairAligner.RotationEvidence rotation = null;
                if (input.readBoolean()) {
                    rotation = new PairAligner.RotationEvidence(input.readDouble(), input.readDouble(),
                            input.readDouble(), input.readDouble(), input.readBoolean());
                }
                int dimensions = input.readInt();
                boolean available = input.readBoolean();
                double[] covariance = null;
                if (available) {
                    covariance = new double[dimensions * dimensions];
                    for (int i = 0; i < covariance.length; i++) covariance[i] = input.readDouble();
                }
                double ambiguity = input.readDouble();
                boolean calibrated = input.readBoolean();
                boolean floored = input.readBoolean();
                boolean capped = input.readBoolean();
                PairUncertainty uncertainty = available
                        ? PairUncertainty.fromCovariance(dimensions, covariance, ambiguity,
                                calibrated, floored, capped)
                        : PairUncertainty.unavailable(dimensions);
                PairAligner.Fit fit = new PairAligner.Fit(transform, residualBefore, residualAfter,
                        logGain, validFraction, iterations, status, rotation, uncertainty);
                pairs.add(new Registration.PairResult(from, to, fit));
            }
            if (input.read() != -1) throw new IOException("trailing bytes in pair cache " + path);
        } catch (RuntimeException malformed) {
            throw new IOException("malformed pair cache " + path, malformed);
        }
        return pairs;
    }

    private static <T> T enumValue(T[] values, int ordinal, String name) throws IOException {
        if (ordinal < 0 || ordinal >= values.length) throw new IOException("invalid " + name);
        return values[ordinal];
    }

    private static void writeTransform(DataOutputStream output, Transform transform)
            throws IOException {
        output.writeDouble(transform.dx);
        output.writeDouble(transform.dy);
        output.writeDouble(transform.theta);
    }

    private static Transform readTransform(DataInputStream input) throws IOException {
        return new Transform(input.readDouble(), input.readDouble(), input.readDouble());
    }

    private static void ensureSplitAuthorized(
            Path round, ConfidenceWeightingBenchmark.Split split, Policy policy) throws IOException {
        if (split == ConfidenceWeightingBenchmark.Split.DEVELOPMENT) return;
        Path frozen = round.resolve("policy/frozen_policy.properties");
        if (!Files.isRegularFile(frozen)) {
            throw new IOException("validation is locked until policy/frozen_policy.properties exists");
        }
        Properties properties = loadProperties(frozen);
        require(properties, "minimum_std", Double.toString(policy.minimumStd));
        require(properties, "maximum_std", Double.toString(policy.maximumStd));
        require(properties, "normalizer", policy.normalizer.name());
        require(properties, "robust_constant", Double.toString(policy.robustConstant));
        require(properties, "robust_floor", Double.toString(policy.robustFloor));
        require(properties, "robust_iterations", Integer.toString(policy.robustIterations));
        require(properties, "protocol_sha256", sha256(findProjectRoot(round).resolve(
                "docs/confidence-weighted-reconciliation/weighting_protocol_v2.md")));
        require(properties, "source_manifest_sha256",
                sha256(round.resolve("sources/source_manifest.csv")));
        if (split == ConfidenceWeightingBenchmark.Split.FINAL) {
            Path verdict = round.resolve("policy/validation_verdict.properties");
            Path opening = round.resolve("policy/final_opening.properties");
            if (!Files.isRegularFile(verdict)
                    || !"PASS".equals(loadProperties(verdict).getProperty("verdict"))) {
                throw new IOException("final is locked until validation verdict is PASS");
            }
            if (!Files.isRegularFile(opening)) {
                throw new IOException("final is locked until final_opening.properties exists");
            }
        }
    }

    private static void require(Properties properties, String key, String expected)
            throws IOException {
        String actual = properties.getProperty(key);
        if (!expected.equals(actual)) {
            throw new IOException("frozen policy mismatch for " + key + ": expected "
                    + expected + ", found " + actual);
        }
    }

    private static void aggregate(Path output) throws IOException {
        Path recordings = output.resolve("recordings");
        List<Path> directories = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(recordings)) {
            stream.filter(Files::isDirectory).sorted().forEach(directories::add);
        }
        List<String> summaries = combine(directories, "recording_summary.csv");
        List<String> pairs = combine(directories, "pair_trials.csv");
        List<String> selectors = combine(directories, "selector_features.csv");
        atomicWrite(output.resolve("recording_summary.csv"), summaries);
        atomicWrite(output.resolve("pair_trials.csv"), pairs);
        atomicWrite(output.resolve("selector_features.csv"), selectors);
        atomicWrite(output.resolve("recording_strategy_matrix.csv"), matrix(summaries));
        List<String> strategy = strategySummary(summaries);
        atomicWrite(output.resolve("strategy_summary.csv"), strategy);
        atomicWrite(output.resolve("REPORT.md"), report(output, summaries, strategy));
    }

    private static List<String> combine(List<Path> directories, String file) throws IOException {
        List<String> out = new ArrayList<>();
        String header = null;
        for (Path directory : directories) {
            Path path = directory.resolve(file);
            if (!Files.isRegularFile(path)) continue;
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (lines.isEmpty()) continue;
            if (header == null) {
                header = lines.get(0);
                out.add(header);
            } else if (!header.equals(lines.get(0))) {
                throw new IOException("schema changed in " + path);
            }
            out.addAll(lines.subList(1, lines.size()));
        }
        if (header == null) throw new IOException("no completed " + file + " artifacts");
        return out;
    }

    private static List<String> matrix(List<String> summaries) throws IOException {
        String[] header = csv(summaries.get(0));
        Map<String, Integer> columns = index(header);
        Map<String, String[]> first = new LinkedHashMap<>();
        Map<String, Map<String, String>> errors = new LinkedHashMap<>();
        for (int row = 1; row < summaries.size(); row++) {
            String[] values = csv(summaries.get(row));
            String id = value(values, columns, "recording_id");
            first.putIfAbsent(id, values);
            errors.computeIfAbsent(id, ignored -> new LinkedHashMap<>()).put(
                    value(values, columns, "arm"), value(values, columns, "median_error_px"));
        }
        List<String> out = new ArrayList<>();
        out.add("recording_id,source_id,category,acquisition_group,split,profile,scope,estimator,"
                + "equal_error_px,uncertainty_error_px,robust_error_px,combined_error_px");
        for (Map.Entry<String, String[]> entry : first.entrySet()) {
            String[] values = entry.getValue();
            Map<String, String> byArm = errors.get(entry.getKey());
            out.add(csvRow(entry.getKey(), value(values, columns, "source_id"),
                    value(values, columns, "category"),
                    value(values, columns, "acquisition_group"),
                    value(values, columns, "split"), value(values, columns, "profile"),
                    value(values, columns, "scope"), value(values, columns, "estimator"),
                    byArm.get("EQUAL"), byArm.get("UNCERTAINTY_ONLY"),
                    byArm.get("ROBUST_ONLY"), byArm.get("UNCERTAINTY_AND_ROBUST")));
        }
        return out;
    }

    private static final class StrategyGroup {
        final List<Double> medianErrors = new ArrayList<>();
        final List<Double> maximumErrors = new ArrayList<>();
        final List<Double> reconcileMillis = new ArrayList<>();
        final List<Double> totalMillis = new ArrayList<>();
        int failures;
        int bounds;
        int repairs;
    }

    private static List<String> strategySummary(List<String> summaries) throws IOException {
        Map<String, Integer> columns = index(csv(summaries.get(0)));
        Map<String, StrategyGroup> groups = new LinkedHashMap<>();
        for (int row = 1; row < summaries.size(); row++) {
            String[] values = csv(summaries.get(row));
            String key = value(values, columns, "category") + "|"
                    + value(values, columns, "scope") + "|"
                    + value(values, columns, "estimator") + "|"
                    + value(values, columns, "arm");
            StrategyGroup group = groups.computeIfAbsent(key, ignored -> new StrategyGroup());
            group.medianErrors.add(Double.parseDouble(value(values, columns, "median_error_px")));
            group.maximumErrors.add(Double.parseDouble(value(values, columns, "maximum_error_px")));
            group.reconcileMillis.add(Double.parseDouble(
                    value(values, columns, "reconciliation_time_ms")));
            group.totalMillis.add(Double.parseDouble(value(values, columns, "total_time_ms")));
            group.failures += Integer.parseInt(value(values, columns, "failed_pairs"));
            group.bounds += Integer.parseInt(value(values, columns, "bound_pairs"));
            group.repairs += Integer.parseInt(value(values, columns, "repaired_frames"));
        }
        List<String> out = new ArrayList<>();
        out.add("category,scope,estimator,arm,recordings,source_balanced_median_error_px,"
                + "worst_recording_error_px,failed_pairs,bound_pairs,repaired_frames,"
                + "median_reconciliation_time_ms,median_total_time_ms");
        for (Map.Entry<String, StrategyGroup> entry : groups.entrySet()) {
            String[] key = entry.getKey().split("\\|", -1);
            StrategyGroup group = entry.getValue();
            out.add(csvRow(key[0], key[1], key[2], key[3], group.medianErrors.size(),
                    f(median(group.medianErrors)), f(max(group.maximumErrors)), group.failures,
                    group.bounds, group.repairs, f(median(group.reconcileMillis)),
                    f(median(group.totalMillis))));
        }
        return out;
    }

    private static List<String> report(Path output, List<String> summaries,
                                       List<String> strategy) {
        List<String> out = new ArrayList<>();
        out.add("# Confidence-weighted reconciliation run");
        out.add("");
        out.add("- Completed recordings: " + ((summaries.size() - 1) / 4));
        out.add("- Pair fits are serialized once and reused byte-identically by all four arms.");
        out.add("- Independent source recording is the statistical unit; `strategy_summary.csv` "
                + "summarizes recording-level errors.");
        out.add("- Gate evaluation and promotion decisions are written by the immutable evaluation stage.");
        out.add("");
        out.add("Artifacts: `recording_summary.csv`, `strategy_summary.csv`, `pair_trials.csv`, "
                + "`recording_strategy_matrix.csv`, and truth-free `selector_features.csv`.");
        return out;
    }

    private static void writeManifest(
            Path project, Path round, Path output, ConfidenceWeightingBenchmark.Split split,
            String attempt, Policy policy, List<Source> sources, boolean smoke) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("schema", "confidence-weighting-run-v2");
        properties.setProperty("started_utc", Instant.now().toString());
        properties.setProperty("split", split.name());
        properties.setProperty("attempt", attempt);
        properties.setProperty("smoke", Boolean.toString(smoke));
        properties.setProperty("frames", Integer.toString(FRAMES));
        properties.setProperty("lags", Arrays.toString(LAGS));
        properties.setProperty("sources", Integer.toString(sources.size()));
        properties.setProperty("minimum_std", Double.toString(policy.minimumStd));
        properties.setProperty("maximum_std", Double.toString(policy.maximumStd));
        properties.setProperty("normalizer", policy.normalizer.name());
        properties.setProperty("robust_constant", Double.toString(policy.robustConstant));
        properties.setProperty("robust_floor", Double.toString(policy.robustFloor));
        properties.setProperty("robust_iterations", Integer.toString(policy.robustIterations));
        properties.setProperty("policy_id", policy.id());
        properties.setProperty("protocol_sha256", sha256(project.resolve(
                "docs/confidence-weighted-reconciliation/weighting_protocol_v2.md")));
        properties.setProperty("source_manifest_sha256",
                sha256(round.resolve("sources/source_manifest.csv")));
        properties.setProperty("runner_sha256", sha256(project.resolve(
                "src/test/java/ripr/core/ConfidenceWeightingBenchmarkRunner.java")));
        MessageDigest cohort = digest();
        for (Source source : sources) {
            cohort.update(source.id.getBytes(StandardCharsets.UTF_8));
            cohort.update((byte) 0);
            cohort.update(source.sha256.getBytes(StandardCharsets.UTF_8));
            cohort.update((byte) '\n');
        }
        properties.setProperty("cohort_sha256", hex(cohort.digest()));
        storeProperties(output.resolve("run_manifest.properties"), properties);
    }

    private static Map<String, Integer> index(String[] header) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) out.put(header[i], i);
        return out;
    }

    private static String value(String[] values, Map<String, Integer> columns, String name)
            throws IOException {
        Integer index = columns.get(name);
        if (index == null || index >= values.length) throw new IOException("missing CSV column " + name);
        return values[index];
    }

    private static String[] csv(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    value.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                values.add(value.toString());
                value.setLength(0);
            } else {
                value.append(character);
            }
        }
        values.add(value.toString());
        return values.toArray(new String[0]);
    }

    private static String csvRow(Object... values) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (index > 0) out.append(',');
            String value = values[index] == null ? "" : values[index].toString();
            if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                    || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                out.append('"').append(value.replace("\"", "\"\"")).append('"');
            } else {
                out.append(value);
            }
        }
        return out.toString();
    }

    private static String f(double value) {
        if (Double.isNaN(value)) return "NaN";
        if (value == Double.POSITIVE_INFINITY) return "Infinity";
        if (value == Double.NEGATIVE_INFINITY) return "-Infinity";
        return String.format(Locale.ROOT, "%.12g", value);
    }

    private static double mean(double[] values) {
        double sum = 0;
        for (double value : values) sum += value;
        return sum / values.length;
    }

    private static double max(double[] values) {
        double maximum = Double.NEGATIVE_INFINITY;
        for (double value : values) maximum = Math.max(maximum, value);
        return maximum;
    }

    private static double max(List<Double> values) {
        double maximum = Double.NEGATIVE_INFINITY;
        for (double value : values) maximum = Math.max(maximum, value);
        return maximum;
    }

    private static double median(double[] values) {
        double[] ordered = values.clone();
        Arrays.sort(ordered);
        int middle = ordered.length / 2;
        return ordered.length % 2 == 0
                ? 0.5 * (ordered[middle - 1] + ordered[middle]) : ordered[middle];
    }

    private static double median(List<Double> values) {
        double[] ordered = new double[values.size()];
        for (int index = 0; index < ordered.length; index++) ordered[index] = values.get(index);
        return median(ordered);
    }

    private static void storeProperties(Path path, Properties properties) throws IOException {
        Files.createDirectories(path.getParent());
        List<String> keys = new ArrayList<>();
        for (Object key : properties.keySet()) keys.add(key.toString());
        keys.sort(String::compareTo);
        List<String> lines = new ArrayList<>();
        for (String key : keys) lines.add(key + "=" + properties.getProperty(key));
        atomicWrite(path, lines);
    }

    private static Properties loadProperties(Path path) throws IOException {
        Properties properties = new Properties();
        try (java.io.InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static void atomicWrite(Path path, List<String> lines) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = temporary(path);
        Files.write(temporary, lines, StandardCharsets.UTF_8);
        atomicMove(temporary, path);
    }

    private static Path temporary(Path path) {
        return path.resolveSibling(path.getFileName().toString() + ".tmp-"
                + Long.toUnsignedString(System.nanoTime()));
    }

    private static void atomicMove(Path temporary, Path path) throws IOException {
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = digest();
        try (java.io.InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String sha256(byte[] bytes) {
        MessageDigest digest = digest();
        digest.update(bytes);
        return hex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) output.append(String.format("%02x", value & 0xff));
        return output.toString();
    }
}
