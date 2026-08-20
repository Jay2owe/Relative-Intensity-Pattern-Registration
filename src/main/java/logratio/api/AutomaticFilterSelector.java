/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.api;

import logratio.core.FrameSource;
import logratio.core.Transform;

import java.util.Arrays;

/**
 * Measures the recording evidence used to choose optional preprocessing and pixel removal.
 *
 * <p>The selector deliberately leaves the caller's base log-ratio model alone. It may add one of the
 * small set of combinations that won consistently in the balanced controlled benchmark, or add
 * nothing when either classification is uncertain. Image evidence comes from the first, middle and
 * final frames. Movement evidence comes from a provisional unfiltered registration, so filtering can
 * never decide which filter should be tested by first changing the pixels.
 *
 * <p><b>This is the narrower, superseded selector.</b> It reaches one dimension — the six filter and
 * mask combinations in {@link Recipe} — and cannot change pixel support, the intensity band, or the
 * pairwise estimator. {@link AutomaticRegistrationSelector} is the current one: a candidate space of
 * two axes and 112 candidates, 96 log-ratio recipes over support, band, filter and mask plus 16
 * {@link logratio.core.PairEstimator.Kind#AREA_CORRELATION} candidates over band and filter. A
 * recording routed here therefore cannot receive the estimator the full selector would have chosen
 * for it. This class stays because {@link LogRatioRegistration#resolveAutomaticFilters} is public
 * API and because the full selector reuses its feature measurements verbatim — see
 * {@link #IMAGE_FEATURE_NAMES} and {@link #MOTION_FEATURE_NAMES}, which are the first columns of the
 * full selector's evidence vector.
 */
public final class AutomaticFilterSelector {

    /**
     * Frozen after 20 leave-one-source-series-out folds on the balanced controlled library.
     * The five one-versus-rest rules reproduced 74 of 80 complete recipes, made no unsupported
     * additions, and deliberately fell back to no addition for uncertain recordings.
     */
    public static final double MASK_THRESHOLD = 1.50;
    public static final double MEDIAN_MASK_THRESHOLD = 0.85;
    public static final double MEDIAN_THRESHOLD = 2.00;
    public static final double GAUSSIAN_0_7_THRESHOLD = 1.25;
    public static final double GAUSSIAN_1_0_THRESHOLD = 1.25;

    private static final double[] FEATURE_MEAN = {
            0.26525034369378264, 0.35580808096462124, 0.37878226525181236,
            1.7091882143062123, 0.07716125588197373, 0.0187887319605275,
            2.5590509992639943, 48.03994171748874, 0.05438950664375878,
            0.1555454343064075, 0.029658012761930003, 0.09446968146424375,
            0.03570815258345126, 0.11394167738646248, 0.7984765371881251,
            0.7698742778808125, 0.760688096500975, 0.5498799910325876,
            1.2582629148378, 1.7348273748198373, 4.263483615192376,
            0.4170664972764843, 0.23159445468436496, 0.49493752021051235,
            1.1639073598011627, 1.764186677490375, 9.937807164336125
    };

    private static final double[] FEATURE_SCALE = {
            0.16941641371713936, 0.18764410354203584, 0.1619908205521054,
            2.2941820532622663, 0.07281560179960994, 0.013426906144392256,
            4.272401078796081, 101.51277560508068, 0.02735143892754771,
            0.062296585764784154, 0.016364232948695455, 0.04510550338980435,
            0.016663156851066032, 0.055904231126087674, 0.112330977803736,
            0.16878396353464797, 0.1740095935975624, 0.5548677502445416,
            1.1770321647393807, 1.7136214365754776, 4.374249891102899,
            0.31193275404240933, 0.13173691376186025, 0.850914861274261,
            1.8394731695164706, 2.694939959938663, 5.915168243520942
    };

    private static final LinearRule MASK = new LinearRule(-3.4037494399041037,
            MASK_THRESHOLD, new double[] {
            -0.6737572967877742, -0.4811740246300905, 1.288192397095922,
            -0.538995818780842, -0.43734631385569045, 0.10346343445710361,
            0.023249396093202274, -0.16820935304377596, -0.3609489555532719,
            0.31087346465849386, -0.49498767564327995, -0.3005641313485201,
            -0.13999452455473263, 0.8156850670333309, -0.5333272345000357,
            -0.35341511534833797, -0.19870476159434183, 0.13860451755839087,
            0.4963212079318708, 0.7043226785044483, 0.7543782467118417,
            0.5585910095850466, 0.027642456409974082, -0.050293649230142325,
            0.25558152913350213, 0.38842799836005143, 0.7782605814077534
    });

    private static final LinearRule MEDIAN_MASK = new LinearRule(-4.969962072966295,
            MEDIAN_MASK_THRESHOLD, new double[] {
            -0.527406962766762, -0.4917834217535575, 1.1162353370889375,
            -0.24783905314826957, -0.11773167964578926, 0.16957062594435968,
            0.0027951962391804313, -0.09163552261407804, -0.25601311010102257,
            0.28786994679335143, -0.30371663115660014, -0.15634747635993823,
            -0.010580900229561045, 0.8497018163260749, -0.16966597542972536,
            0.12235473671979177, -0.4294070371062968, 0.04830945878084973,
            -0.2815930951826758, 0.8469362792395398, 0.19643027120431558,
            0.09583481740768998, -1.8354423040817647, -0.18900118497537258,
            0.19495781204457716, 0.5009592159728397, 0.631545136540901
    });

    private static final LinearRule MEDIAN = new LinearRule(-6.435463095659824,
            MEDIAN_THRESHOLD, new double[] {
            1.1120437020594374, -0.7036097146558088, -0.34835468078692056,
            -0.14004278006346024, -0.2826907804818676, -0.08666995685641492,
            -0.18593444904506434, -0.13299059233262372, -0.15060755614130988,
            -0.21157407296810993, -0.25142690568036125, -0.1780257470690865,
            -0.1571714307837231, -0.17782239190978297, 0.83329051310844,
            0.27830873643303145, 0.2841896387303976, -0.32534264678310365,
            -0.5597224970612377, -0.5990200221871853, -0.6299382287234189,
            -0.9530520508990422, 0.0859868309573269, 0.2055660178738247,
            -0.11917100183752043, -0.1872587900223714, -1.216608423521082
    });

    private static final LinearRule GAUSSIAN_0_7 = new LinearRule(-5.5173703429208425,
            GAUSSIAN_0_7_THRESHOLD, new double[] {
            0.5766213588804933, 0.03092455178313698, -0.6386228301228593,
            -0.2779245450721957, -0.4400085928092129, -0.3735980375044871,
            -1.403761154062696, -0.010233611814735227, 0.34324387931792893,
            0.0514186281422686, 0.45401516375229045, 0.27505331881208883,
            0.3023090193601723, 0.003018288518429836, -0.9677766121740391,
            0.052909121994923786, 0.1514616286736769, -0.511423276263544,
            0.3850593458271759, 0.5625589364194461, 1.3605106527505553,
            0.058123577382437425, 0.3520342576487542, -0.25120001102237083,
            0.24392611939544664, 0.3754834613797071, -0.2174492919151622
    });

    private static final LinearRule GAUSSIAN_1_0 = new LinearRule(-5.715885206932553,
            GAUSSIAN_1_0_THRESHOLD, new double[] {
            -0.0340022410303746, 0.5189109988926084, -0.57527411748311,
            -0.4643528769673248, -0.49469783972228093, -0.36929338960004016,
            -1.4991179743689154, -0.03865716511839268, 0.37481866181270096,
            0.12175965469149809, 0.3996791074162602, 0.23231995581480816,
            0.316886318248544, 0.05076438816375743, -0.44859746068640244,
            0.02325201119047094, 0.3860824375635264, -0.3472892409054721,
            -0.5341302636024915, -0.5603428799138138, -0.55723446171531,
            -0.892115324697564, 0.03007198154364279, 0.17380528705545192,
            -0.10325839789546613, -0.1640047817638471, -1.1816558617780069
    });

    /** Image measurements in the frozen model's declared order. */
    public static final String[] IMAGE_FEATURE_NAMES = {
            "median position", "99-to-95 percentile tail", "95-to-median range",
            "maximum tail", "dark-pixel share", "bright-pixel share", "skewness",
            "excess kurtosis", "median gradient", "90th-percentile gradient",
            "median impulse residual", "90th-percentile impulse residual",
            "median blur residual", "90th-percentile blur residual", "histogram entropy",
            "horizontal neighbour correlation", "vertical neighbour correlation"
    };

    /** Provisional movement measurements in the frozen model's declared order. */
    public static final String[] MOTION_FEATURE_NAMES = {
            "median step", "90th-percentile step", "maximum step", "jump ratio",
            "path efficiency", "linearity residual", "median acceleration",
            "90th-percentile acceleration", "maximum acceleration", "90th-percentile reach"
    };

    /** Immutable evidence bundle, exposed so a settings report can state exactly what was measured. */
    public static final class Evidence {
        public final double[] imageFeatures;
        public final double[] motionFeatures;

        Evidence(double[] imageFeatures, double[] motionFeatures) {
            this.imageFeatures = imageFeatures.clone();
            this.motionFeatures = motionFeatures.clone();
        }
    }

    /** Only combinations that cleared the four-independent-source consistency gate. */
    public enum Recipe {
        NONE("No preprocessing or automatic pixel removal"),
        GAUSSIAN_0_7("Gaussian smoothing, 0.7 pixel"),
        GAUSSIAN_1_0("Gaussian smoothing, 1.0 pixel"),
        MEDIAN_3X3("Median denoising, 3 by 3"),
        SPATIAL_MASK_25("Remove the least spatially informative 25%"),
        MEDIAN_3X3_AND_SPATIAL_MASK_25(
                "Median denoising plus removal of the least spatially informative 25%");

        private final String label;

        Recipe(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Frozen decision, all five raw model scores and the resolved editable parameters. */
    public static final class Result {
        public final Evidence evidence;
        public final Recipe recipe;
        public final double maskScore;
        public final double medianMaskScore;
        public final double medianScore;
        public final double gaussian07Score;
        public final double gaussian10Score;
        public final LogRatioParameters parameters;

        Result(Evidence evidence, Recipe recipe, double maskScore, double medianMaskScore,
               double medianScore, double gaussian07Score, double gaussian10Score,
               LogRatioParameters parameters) {
            this.evidence = evidence;
            this.recipe = recipe;
            this.maskScore = maskScore;
            this.medianMaskScore = medianMaskScore;
            this.medianScore = medianScore;
            this.gaussian07Score = gaussian07Score;
            this.gaussian10Score = gaussian10Score;
            this.parameters = parameters;
        }

        /** Short settings-report text; scores are margins before their frozen thresholds. */
        public String explanation() {
            return recipe.label() + String.format(java.util.Locale.ROOT,
                    " (scores: mask %.2f/%.2f, median-mask %.2f/%.2f, median %.2f/%.2f, "
                            + "Gaussian-0.7 %.2f/%.2f, Gaussian-1.0 %.2f/%.2f)",
                    maskScore, MASK_THRESHOLD, medianMaskScore, MEDIAN_MASK_THRESHOLD,
                    medianScore, MEDIAN_THRESHOLD, gaussian07Score, GAUSSIAN_0_7_THRESHOLD,
                    gaussian10Score, GAUSSIAN_1_0_THRESHOLD);
        }
    }

    private static final class LinearRule {
        final double intercept;
        final double threshold;
        final double[] weights;

        LinearRule(double intercept, double threshold, double[] weights) {
            this.intercept = intercept;
            this.threshold = threshold;
            this.weights = weights;
        }

        double score(double[] standardized) {
            double value = intercept;
            for (int i = 0; i < weights.length; i++) value += weights[i] * standardized[i];
            return value;
        }
    }

    private AutomaticFilterSelector() {
    }

    /** Measure one recording using an already-computed provisional movement path. */
    public static Evidence measure(FrameSource rawSource, Transform[] provisionalCumulative) {
        if (rawSource == null || provisionalCumulative == null) {
            throw new IllegalArgumentException("source and provisional transforms are required");
        }
        if (rawSource.count() != provisionalCumulative.length) {
            throw new IllegalArgumentException("source and provisional transforms have different frame counts");
        }
        if (rawSource.count() < 1) throw new IllegalArgumentException("source has no frames");
        return new Evidence(imageFeatures(rawSource), motionFeatures(provisionalCumulative));
    }

    /** Apply the frozen rules to measured evidence while preserving every base-model setting. */
    public static Result select(Evidence evidence, LogRatioParameters base) {
        if (evidence == null || base == null) {
            throw new IllegalArgumentException("evidence and base parameters are required");
        }
        double[] values = new double[FEATURE_MEAN.length];
        System.arraycopy(evidence.imageFeatures, 0, values, 0, evidence.imageFeatures.length);
        System.arraycopy(evidence.motionFeatures, 0, values, evidence.imageFeatures.length,
                evidence.motionFeatures.length);
        double[] standardized = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            double value = Double.isFinite(values[i]) ? values[i] : FEATURE_MEAN[i];
            standardized[i] = (value - FEATURE_MEAN[i]) / FEATURE_SCALE[i];
        }
        double mask = MASK.score(standardized);
        double medianMask = MEDIAN_MASK.score(standardized);
        double median = MEDIAN.score(standardized);
        double gaussian07 = GAUSSIAN_0_7.score(standardized);
        double gaussian10 = GAUSSIAN_1_0.score(standardized);
        Recipe recipe = choose(mask, medianMask, median, gaussian07, gaussian10);
        LogRatioParameters.Builder resolved = base.toBuilder().useRecommendation(false)
                .automaticFilterSelection(false)
                .preprocessing(Preprocessing.NONE)
                .pixelSelectionStrategy(PixelSelectionStrategy.NONE)
                .pixelSelectionPreprocessing(Preprocessing.NONE)
                .pixelRemovalPercent(25.0);
        switch (recipe) {
            case GAUSSIAN_0_7:
                resolved.preprocessing(Preprocessing.GAUSSIAN_0_7);
                break;
            case GAUSSIAN_1_0:
                resolved.preprocessing(Preprocessing.GAUSSIAN_1_0);
                break;
            case MEDIAN_3X3:
                resolved.preprocessing(Preprocessing.MEDIAN_3X3);
                break;
            case MEDIAN_3X3_AND_SPATIAL_MASK_25:
                resolved.preprocessing(Preprocessing.MEDIAN_3X3);
                // fall through: the filtered provisional fit and raw spatial mask are separate.
            case SPATIAL_MASK_25:
                resolved.pixelSelectionStrategy(PixelSelectionStrategy.REMOVE_LEAST_INFORMATIVE);
                break;
            case NONE:
            default:
                break;
        }
        return new Result(evidence, recipe, mask, medianMask, median, gaussian07, gaussian10,
                resolved.build());
    }

    /** Pure boundary decision used by tests and settings audits. */
    public static Recipe choose(double maskScore, double medianMaskScore, double medianScore,
                                double gaussian07Score, double gaussian10Score) {
        if (maskScore > MASK_THRESHOLD) {
            return medianMaskScore > MEDIAN_MASK_THRESHOLD
                    ? Recipe.MEDIAN_3X3_AND_SPATIAL_MASK_25 : Recipe.SPATIAL_MASK_25;
        }
        if (medianScore > MEDIAN_THRESHOLD) return Recipe.MEDIAN_3X3;
        if (gaussian07Score > GAUSSIAN_0_7_THRESHOLD) return Recipe.GAUSSIAN_0_7;
        if (gaussian10Score > GAUSSIAN_1_0_THRESHOLD) return Recipe.GAUSSIAN_1_0;
        return Recipe.NONE;
    }

    static double[] imageFeatures(FrameSource source) {
        int[] frames = {0, source.count() / 2, source.count() - 1};
        double[][] measured = new double[frames.length][];
        for (int i = 0; i < frames.length; i++) {
            measured[i] = imageFeatures(source.plane(frames[i]), source.width(), source.height());
        }
        double[] out = new double[IMAGE_FEATURE_NAMES.length];
        double[] values = new double[measured.length];
        for (int feature = 0; feature < out.length; feature++) {
            for (int frame = 0; frame < measured.length; frame++) {
                values[frame] = measured[frame][feature];
            }
            out[feature] = medianFinite(values);
        }
        return out;
    }

    static double[] imageFeatures(float[] frame, int width, int height) {
        if (frame == null || frame.length != width * height) {
            throw new IllegalArgumentException("frame dimensions do not match");
        }
        double[] finite = new double[frame.length];
        int count = 0;
        for (float value : frame) if (Float.isFinite(value)) finite[count++] = value;
        if (count < 2) {
            double[] missing = new double[IMAGE_FEATURE_NAMES.length];
            Arrays.fill(missing, Double.NaN);
            return missing;
        }
        Arrays.sort(finite, 0, count);
        double p1 = quantileSorted(finite, count, 0.01);
        double p50 = quantileSorted(finite, count, 0.50);
        double p95 = quantileSorted(finite, count, 0.95);
        double p99 = quantileSorted(finite, count, 0.99);
        double minimum = finite[0];
        double maximum = finite[count - 1];
        double range = Math.max(1e-9, p99 - p1);

        int dark = 0;
        int bright = 0;
        double mean = 0;
        for (int i = 0; i < count; i++) {
            double value = finite[i];
            if (value <= p1 + 0.05 * range) dark++;
            if (value >= p1 + 0.95 * range) bright++;
            mean += value;
        }
        mean /= count;
        double m2 = 0;
        double m3 = 0;
        double m4 = 0;
        for (int i = 0; i < count; i++) {
            double d = finite[i] - mean;
            double d2 = d * d;
            m2 += d2;
            m3 += d2 * d;
            m4 += d2 * d2;
        }
        m2 /= count;
        m3 /= count;
        m4 /= count;
        double skewness = m2 > 0 ? m3 / Math.pow(m2, 1.5) : 0;
        double kurtosis = m2 > 0 ? m4 / (m2 * m2) - 3.0 : 0;

        double[] gradient = gradients(frame, width, height);
        double[] impulse = absoluteDifference(frame, median3(frame, width, height));
        double[] blur = absoluteDifference(frame, binomialBlur(frame, width, height));
        double entropy = entropy(frame, p1, range);
        return new double[] {
                (p50 - p1) / range,
                (p99 - p95) / range,
                (p95 - p50) / range,
                (maximum - p99) / range,
                dark / (double) count,
                bright / (double) count,
                skewness,
                kurtosis,
                quantileFinite(gradient, 0.50) / range,
                quantileFinite(gradient, 0.90) / range,
                quantileFinite(impulse, 0.50) / range,
                quantileFinite(impulse, 0.90) / range,
                quantileFinite(blur, 0.50) / range,
                quantileFinite(blur, 0.90) / range,
                entropy,
                neighbourCorrelation(frame, width, height, true),
                neighbourCorrelation(frame, width, height, false)
        };
    }

    static double[] motionFeatures(Transform[] cumulative) {
        int n = cumulative.length;
        if (n < 2) {
            double[] missing = new double[MOTION_FEATURE_NAMES.length];
            Arrays.fill(missing, Double.NaN);
            return missing;
        }
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            Transform transform = cumulative[i] == null ? Transform.IDENTITY : cumulative[i];
            x[i] = transform.dx;
            y[i] = transform.dy;
        }
        double[] steps = new double[n - 1];
        double[] dx = new double[n - 1];
        double[] dy = new double[n - 1];
        double total = 0;
        for (int i = 1; i < n; i++) {
            dx[i - 1] = x[i] - x[i - 1];
            dy[i - 1] = y[i] - y[i - 1];
            steps[i - 1] = Math.hypot(dx[i - 1], dy[i - 1]);
            total += steps[i - 1];
        }
        double[] acceleration = new double[Math.max(0, n - 2)];
        for (int i = 1; i < dx.length; i++) {
            acceleration[i - 1] = Math.hypot(dx[i] - dx[i - 1], dy[i] - dy[i - 1]);
        }
        double medianStep = quantileFinite(steps, 0.50);
        double displacement = Math.hypot(x[n - 1] - x[0], y[n - 1] - y[0]);
        double[] reach = new double[n];
        for (int i = 0; i < n; i++) reach[i] = Math.hypot(x[i] - x[0], y[i] - y[0]);
        double p90Reach = quantileFinite(reach, 0.90);
        double linearResidual = linearResidual(x, y) / (p90Reach + 1e-9);
        return new double[] {
                medianStep,
                quantileFinite(steps, 0.90),
                quantileFinite(steps, 1.00),
                quantileFinite(steps, 1.00) / (medianStep + 1e-9),
                displacement / (total + 1e-9),
                linearResidual,
                quantileFinite(acceleration, 0.50),
                quantileFinite(acceleration, 0.90),
                quantileFinite(acceleration, 1.00),
                p90Reach
        };
    }

    private static double linearResidual(double[] x, double[] y) {
        int n = x.length;
        double mt = 0.5 * (n - 1);
        double mx = mean(x);
        double my = mean(y);
        double tt = 0;
        double tx = 0;
        double ty = 0;
        for (int i = 0; i < n; i++) {
            double dt = i - mt;
            tt += dt * dt;
            tx += dt * (x[i] - mx);
            ty += dt * (y[i] - my);
        }
        double bx = tt > 0 ? tx / tt : 0;
        double by = tt > 0 ? ty / tt : 0;
        double squared = 0;
        for (int i = 0; i < n; i++) {
            double fx = mx + bx * (i - mt);
            double fy = my + by * (i - mt);
            squared += (x[i] - fx) * (x[i] - fx) + (y[i] - fy) * (y[i] - fy);
        }
        return Math.sqrt(squared / n);
    }

    private static double mean(double[] values) {
        double total = 0;
        for (double value : values) total += value;
        return values.length == 0 ? Double.NaN : total / values.length;
    }

    private static double[] gradients(float[] frame, int width, int height) {
        double[] out = new double[(width - 1) * height + width * (height - 1)];
        int count = 0;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x + 1 < width; x++) {
                float a = frame[row + x];
                float b = frame[row + x + 1];
                if (Float.isFinite(a) && Float.isFinite(b)) out[count++] = Math.abs(b - a);
            }
        }
        for (int y = 0; y + 1 < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                float a = frame[row + x];
                float b = frame[row + width + x];
                if (Float.isFinite(a) && Float.isFinite(b)) out[count++] = Math.abs(b - a);
            }
        }
        return Arrays.copyOf(out, count);
    }

    private static float[] median3(float[] input, int width, int height) {
        float[] output = new float[input.length];
        float[] values = new float[9];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int count = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int sy = clamp(y + dy, 0, height - 1);
                    for (int dx = -1; dx <= 1; dx++) {
                        int sx = clamp(x + dx, 0, width - 1);
                        float value = input[sy * width + sx];
                        if (Float.isFinite(value)) values[count++] = value;
                    }
                }
                if (count == 0) output[y * width + x] = Float.NaN;
                else {
                    Arrays.sort(values, 0, count);
                    output[y * width + x] = values[count / 2];
                }
            }
        }
        return output;
    }

    private static float[] binomialBlur(float[] input, int width, int height) {
        int[] kernel = {1, 4, 6, 4, 1};
        float[] horizontal = new float[input.length];
        float[] output = new float[input.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double total = 0;
                double weight = 0;
                for (int k = -2; k <= 2; k++) {
                    float value = input[y * width + clamp(x + k, 0, width - 1)];
                    if (!Float.isFinite(value)) continue;
                    total += kernel[k + 2] * value;
                    weight += kernel[k + 2];
                }
                horizontal[y * width + x] = weight > 0 ? (float) (total / weight) : Float.NaN;
            }
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double total = 0;
                double weight = 0;
                for (int k = -2; k <= 2; k++) {
                    float value = horizontal[clamp(y + k, 0, height - 1) * width + x];
                    if (!Float.isFinite(value)) continue;
                    total += kernel[k + 2] * value;
                    weight += kernel[k + 2];
                }
                output[y * width + x] = weight > 0 ? (float) (total / weight) : Float.NaN;
            }
        }
        return output;
    }

    private static double[] absoluteDifference(float[] a, float[] b) {
        double[] out = new double[a.length];
        int count = 0;
        for (int i = 0; i < a.length; i++) {
            if (Float.isFinite(a[i]) && Float.isFinite(b[i])) out[count++] = Math.abs(a[i] - b[i]);
        }
        return Arrays.copyOf(out, count);
    }

    private static double entropy(float[] frame, double p1, double range) {
        int[] bins = new int[32];
        int count = 0;
        for (float value : frame) {
            if (!Float.isFinite(value)) continue;
            double normalized = Math.max(0, Math.min(1, (value - p1) / range));
            int bin = Math.min(31, (int) Math.floor(32 * normalized));
            bins[bin]++;
            count++;
        }
        if (count == 0) return Double.NaN;
        double entropy = 0;
        for (int bin : bins) {
            if (bin == 0) continue;
            double probability = bin / (double) count;
            entropy -= probability * (Math.log(probability) / Math.log(2));
        }
        return entropy / 5.0;
    }

    private static double neighbourCorrelation(float[] frame, int width, int height,
                                               boolean horizontal) {
        double meanA = 0;
        double meanB = 0;
        int count = 0;
        int maxY = horizontal ? height : height - 1;
        int maxX = horizontal ? width - 1 : width;
        for (int y = 0; y < maxY; y++) {
            for (int x = 0; x < maxX; x++) {
                float a = frame[y * width + x];
                float b = horizontal ? frame[y * width + x + 1] : frame[(y + 1) * width + x];
                if (!Float.isFinite(a) || !Float.isFinite(b)) continue;
                meanA += a;
                meanB += b;
                count++;
            }
        }
        if (count < 2) return Double.NaN;
        meanA /= count;
        meanB /= count;
        double aa = 0;
        double bb = 0;
        double ab = 0;
        for (int y = 0; y < maxY; y++) {
            for (int x = 0; x < maxX; x++) {
                float a = frame[y * width + x];
                float b = horizontal ? frame[y * width + x + 1] : frame[(y + 1) * width + x];
                if (!Float.isFinite(a) || !Float.isFinite(b)) continue;
                double da = a - meanA;
                double db = b - meanB;
                aa += da * da;
                bb += db * db;
                ab += da * db;
            }
        }
        double denominator = Math.sqrt(aa * bb);
        return denominator > 0 ? ab / denominator : Double.NaN;
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static double medianFinite(double[] values) {
        return quantileFinite(values, 0.50);
    }

    private static double quantileFinite(double[] values, double fraction) {
        double[] finite = new double[values.length];
        int count = 0;
        for (double value : values) if (Double.isFinite(value)) finite[count++] = value;
        if (count == 0) return Double.NaN;
        Arrays.sort(finite, 0, count);
        return quantileSorted(finite, count, fraction);
    }

    private static double quantileSorted(double[] sorted, int count, double fraction) {
        if (count == 0) return Double.NaN;
        double position = Math.max(0, Math.min(1, fraction)) * (count - 1);
        int low = (int) Math.floor(position);
        int high = Math.min(count - 1, low + 1);
        double part = position - low;
        return sorted[low] * (1 - part) + sorted[high] * part;
    }
}
