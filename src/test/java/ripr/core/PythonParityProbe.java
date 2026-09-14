/*
 * Cross-language analytic fixture used by tests_python/test_java_parity.py.
 * It deliberately prints only machine-readable result rows.
 */
package ripr.core;

import ij.ImagePlus;
import ij.ImageStack;
import ripr.api.AutomaticRegistrationSelector;
import ripr.api.AutomaticRegistrationSelectorModel;
import ripr.api.ImageType;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.RelativeIntensityPatternRegistration;
import ripr.api.MotionType;
import ripr.api.SelectionMode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;

public final class PythonParityProbe {
    private PythonParityProbe() { }

    public static void main(String[] args) throws IOException {
        boolean eventMode = false;
        String eventRaw = null;
        boolean rigid = false;
        boolean area = false;
        boolean ecc = false;
        boolean selectorMode = false;
        for (String arg : args) {
            rigid |= "rigid".equalsIgnoreCase(arg);
            area |= "area".equalsIgnoreCase(arg);
            ecc |= "ecc".equalsIgnoreCase(arg);
            selectorMode |= "selector".equalsIgnoreCase(arg);
            eventMode |= "event".equalsIgnoreCase(arg) || "event_raw".equalsIgnoreCase(arg);
        }
        if (args.length >= 2 && "event_raw".equalsIgnoreCase(args[0])) eventRaw = args[1];
        final int width = 96;
        final int height = 96;
        final int frames = eventMode ? 8 : 5;
        float[][] planes = new float[frames][];
        for (int t = 0; t < frames; t++) {
            planes[t] = rigid || eventMode
                    ? rigidFrame(width, height, new Transform(
                            eventMode ? 0.35 * t : 0.65 * t,
                            eventMode ? -0.22 * t : -0.4 * t,
                            Math.toRadians(eventMode ? (t < 4 ? 0.0 : 2.0) : 0.7 * t)),
                            Math.pow(2.0, -t / (eventMode ? 20.0 : 8.0)))
                    : Synth.frame(width, height, 1.25 * t, -0.7 * t,
                            Math.pow(2.0, -t / 8.0), 0.0);
        }
        if (eventRaw != null) {
            byte[] bytes = Files.readAllBytes(Paths.get(eventRaw));
            int expected = frames * width * height * Float.BYTES;
            if (bytes.length != expected) {
                throw new IllegalArgumentException(
                        "event raw fixture has " + bytes.length + " bytes, expected " + expected);
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int frame = 0; frame < frames; frame++) {
                for (int pixel = 0; pixel < width * height; pixel++) {
                    planes[frame][pixel] = buffer.getFloat();
                }
            }
        }
        if (selectorMode) {
            RelativeIntensityPatternParameters base = RelativeIntensityPatternParameters.builder()
                    .recommendation(ImageType.PHASE_CONTRAST,
                            MotionType.STEADY_DIRECTIONAL_DRIFT)
                    .selectionMode(SelectionMode.MANUAL).threads(1).build();
            RelativeIntensityPatternParameters pilot = RelativeIntensityPatternRegistration.automaticSelectorPilot(base);
            Registration.Result provisional = Registration.run(
                    Synth.source(width, height, planes), pilot.registrationOptions(), null, null);
            AutomaticRegistrationSelector.Evidence evidence =
                    AutomaticRegistrationSelector.measure(
                            Synth.source(width, height, planes), provisional.cumulative, base);
            System.out.printf(Locale.ROOT, "M,%s,%s%n", evidence.valid,
                    evidence.validityReason.replace(',', ';'));
            System.out.printf(Locale.ROOT, "V,%s,%s,%s,%s,%s,%s,%s%n",
                    AutomaticRegistrationSelectorModel.MODEL_VERSION,
                    AutomaticRegistrationSelectorModel.FEATURE_CONTRACT_VERSION,
                    AutomaticRegistrationSelectorModel.PROTOCOL_SHA256,
                    AutomaticRegistrationSelectorModel.CANDIDATE_MANIFEST_SHA256,
                    AutomaticRegistrationSelectorModel.MODEL_ARTIFACT_SHA256,
                    AutomaticRegistrationSelectorModel.VALIDATION_STATUS,
                    AutomaticRegistrationSelectorModel.MODEL_KIND);
            double[] vector = evidence.vector();
            for (int i = 0; i < vector.length; i++) {
                System.out.printf(Locale.ROOT, "S,%d,%s,%.17g%n", i,
                        AutomaticRegistrationSelector.FEATURE_NAMES[i], vector[i]);
            }
            AutomaticRegistrationSelector.Result decision =
                    AutomaticRegistrationSelector.select(evidence, base, false);
            System.out.printf(Locale.ROOT, "D,%s,%.17g,%.17g,%s,%s%n",
                    decision.recipe.id(), decision.predictedGain,
                    decision.confidenceThreshold, decision.fallback,
                    decision.explanation().replace(',', ';'));
            ImageStack stack = new ImageStack(width, height);
            for (float[] plane : planes) stack.addSlice(null, plane.clone());
            ImagePlus image = new ImagePlus("selector parity", stack);
            try {
                for (ImageType imageType : ImageType.values()) {
                    for (MotionType motionType : MotionType.values()) {
                        for (boolean rotationRequested : new boolean[]{false, true}) {
                            RelativeIntensityPatternParameters contextualBase = RelativeIntensityPatternParameters.builder()
                                    .recommendation(imageType, motionType)
                                    .selectionMode(SelectionMode.AUTOMATIC).threads(1)
                                    .fitRotation(rotationRequested).build();
                            AutomaticRegistrationSelector.Result contextualDecision =
                                    RelativeIntensityPatternRegistration.resolveAutomaticSettings(
                                            image, contextualBase);
                        System.out.printf(Locale.ROOT,
                                "A,%s,%s,%s,%s,%.17g,%.17g,%s,%s%n",
                                imageType.name(), motionType.name(), rotationRequested,
                                contextualDecision.recipe.id(), contextualDecision.predictedGain,
                                contextualDecision.confidenceThreshold,
                                contextualDecision.fallback,
                                contextualDecision.declineReason == null ? ""
                                        : contextualDecision.declineReason.replace(',', ';'));
                        }
                    }
                }
            } finally {
                image.close();
            }
            return;
        }
        Registration.Options options = new Registration.Options();
        options.reference = rigid || eventMode
                ? Reconciler.Reference.MULTILAG : Reconciler.Reference.CONSECUTIVE;
        options.lags = new int[]{1, 2, 4};
        options.aligner.maxShift = 8;
        options.aligner.fitRotation = rigid;
        options.aligner.maxRotation = Math.toRadians(5);
        options.aligner.norm = RobustNorm.HUBER;
        options.aligner.support = PairAligner.PixelSupport.ALL;
        if (eventMode) {
            options.rotationMode = RotationMode.KNOWN_EVENTS;
            options.rotationEventFrames = new int[]{4};
            options.rotationEventWindow = 2;
        }
        if (area || ecc) {
            options.estimator = ecc ? PairEstimator.Kind.AREA_CORRELATION_ECC
                    : PairEstimator.Kind.AREA_CORRELATION_NEWTON;
        }
        options.threads = 1;
        Registration.Result result = Registration.run(
                Synth.source(width, height, planes), options, null, null);
        if (eventMode) {
            for (RotationEventResult.Event event : result.eventRotations.events) {
                System.out.printf(Locale.ROOT,
                        "E,%d,%.12f,%.12f,%d,%d,%d,%.12f,%s,%d,%d,%d,%d%n",
                        event.frame, event.deltaTheta, event.cumulativeTheta,
                        event.candidatePairs, event.usablePairs, event.inlierPairs,
                        event.circularMad, event.status.name(), event.firstPreFrame,
                        event.lastPreFrame, event.firstPostFrame, event.lastPostFrame);
            }
            for (Registration.PairResult pair : result.pairs) {
                System.out.printf(Locale.ROOT, "P,%d,%d,%.12f,%.12f,%.12f,%s%n",
                        pair.from, pair.to, pair.fit.transform.dx, pair.fit.transform.dy,
                        pair.fit.transform.theta, pair.fit.status.name());
            }
            for (int t = 0; t < frames; t++) {
                System.out.printf(Locale.ROOT, "F,%d,%.12f,%.12f,%.12f%n",
                        t, result.cumulative[t].dx, result.cumulative[t].dy,
                        result.cumulative[t].theta);
            }
            return;
        }
        for (int t = 0; t < frames; t++) {
            if (rigid) {
                System.out.printf(Locale.ROOT, "%d,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f%n",
                        t, result.cumulative[t].dx, result.cumulative[t].dy,
                        result.cumulative[t].theta, result.log2Gain[t],
                        result.residualBefore[t], result.residualAfter[t]);
            } else {
                System.out.printf(Locale.ROOT, "%d,%.12f,%.12f,%.12f,%.12f,%.12f%n",
                        t, result.cumulative[t].dx, result.cumulative[t].dy,
                        result.log2Gain[t], result.residualBefore[t], result.residualAfter[t]);
            }
        }
    }

    private static float[] rigidFrame(int width, int height, Transform truth, double gain) {
        float[] out = new float[width * height];
        Transform inverse = truth.inverse();
        double cx = (width - 1) / 2.0;
        double cy = (height - 1) / 2.0;
        double[] source = new double[2];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                inverse.apply(x, y, cx, cy, source);
                double u = 2 * Math.PI * source[0] / width;
                double v = 2 * Math.PI * source[1] / height;
                double signal = Math.sin(3 * u + 0.4) * Math.cos(2 * v - 0.7);
                signal += 0.70 * Math.sin(5 * u - 1.1) * Math.cos(7 * v + 0.2);
                signal += 0.45 * Math.cos(11 * u + 0.9) * Math.sin(6 * v + 1.3);
                signal += 0.30 * Math.sin(13 * u + 2.1);
                signal += 0.30 * Math.cos(9 * v - 0.5);
                out[y * width + x] = (float) (gain * (2000.0 + 600.0 * signal));
            }
        }
        return out;
    }
}
