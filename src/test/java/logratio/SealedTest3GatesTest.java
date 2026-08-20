/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the gates over the third sealed test set, and proves they bite.
 *
 * <p>A gate written before a set is opened is only worth something if it cannot be softened after
 * the numbers arrive. These tests are the record of what the limits were: changing
 * {@link SealedTest3Report}'s constants breaks this class, so a change has to be deliberate and
 * shows up in a diff rather than in a quietly edited field.
 *
 * <p>The second half does the more useful job. It feeds the scorer results that should fail and
 * checks that they do, because a gate nobody has watched fail is a gate nobody knows works.
 */
public class SealedTest3GatesTest {

    // ---- the limits, recorded so they cannot drift ---------------------------------------- //

    @Test
    public void theGatesAreWhatWasDeclaredBeforeTheSetWasOpened() {
        assertEquals(40, SealedTest3Report.GATE_RECORDINGS);
        assertEquals(10, SealedTest3Report.GATE_SERIES);
        assertEquals(5, SealedTest3Report.GATE_IMAGE_TYPES);
        assertEquals(2, SealedTest3Report.GATE_SERIES_PER_IMAGE_TYPE);
        assertEquals(0, SealedTest3Report.GATE_FAILURES);
        assertEquals(1.0, SealedTest3Report.GATE_MEAN_RATIO, 0);
        assertEquals(0.002, SealedTest3Report.GATE_IMAGE_TYPE_REGRESSION, 0);
        assertEquals(0.05, SealedTest3Report.GATE_RECORDING_REGRESSION, 0);
        assertEquals(1.0, SealedTest3Report.GATE_SECONDS_RATIO, 0);
        assertEquals(0.0, SealedTest3Report.GATE_CONTROL_ARM_DIFFERENCE, 0);
    }

    /**
     * The seconds gate is a ratio here where the previous two sets used an absolute of 2.2 seconds,
     * because two of this set's ten series carry several times the pixels of a typical earlier seed
     * and an absolute limit would fail the set on frame size. Recorded as a test so the difference
     * reads as a decision rather than an oversight.
     */
    @Test
    public void theSecondsGateIsARatioAndNotTheOldAbsolute() {
        assertEquals(2.2, SealedTestReport.GATE_MEAN_SECONDS, 0);
        assertEquals(1.0, SealedTest3Report.GATE_SECONDS_RATIO, 0);
    }

    // ---- the gates actually bite ---------------------------------------------------------- //

    @Test
    public void anUnchangedModelPassesEveryGate() {
        Fixture f = Fixture.complete();
        SealedTest3Report.Result r = SealedTest3Report.score(f.baseline, f.candidate);
        assertTrue(r.reasons().toString(), r.passed());
        assertEquals(40, r.counted);
    }

    @Test
    public void aPartialRunIsRefusedEvenWhenEveryScoredRecordingImproved() {
        Fixture f = Fixture.complete();
        f.dropCandidate(9);                       // nine recordings lost to a crash
        f.improveCandidate(0.5);                  // and everything left looks better
        SealedTest3Report.Result r = SealedTest3Report.score(f.baseline, f.candidate);
        assertFalse(r.passed());
        assertTrue(r.reasons().toString(),
                r.reasons().toString().contains("scored 31 recordings"));
    }

    @Test
    public void aWorseCandidateFailsTheMeanRatio() {
        Fixture f = Fixture.complete();
        f.worsenCandidate(1.0001);
        SealedTest3Report.Result r = SealedTest3Report.score(f.baseline, f.candidate);
        assertFalse(r.passed());
        assertTrue(r.reasons().toString(), r.reasons().toString().contains("mean median ratio"));
    }

    @Test
    public void oneImageTypeGoingBackwardsFailsEvenWhenTheMeanImproves() {
        Fixture f = Fixture.complete();
        f.improveCandidate(0.5);                  // large overall win ...
        f.worsenImageType("PHASE", 0.01);         // ... paid for by one image type
        SealedTest3Report.Result r = SealedTest3Report.score(f.baseline, f.candidate);
        assertFalse(r.passed());
        assertTrue(r.reasons().toString(), r.reasons().toString().contains("PHASE regression"));
    }

    @Test
    public void aSingleBadRecordingFailsEvenWhenEveryAverageIsFine() {
        Fixture f = Fixture.complete();
        f.worsenOneRecording(0.2);
        SealedTest3Report.Result r = SealedTest3Report.score(f.baseline, f.candidate);
        assertFalse(r.passed());
        assertTrue(r.reasons().toString(),
                r.reasons().toString().contains("per-recording regressions"));
    }

    /** The control: if the arm neither model touches moved, nothing else is attributable. */
    @Test
    public void driftInTheControlArmInvalidatesTheRun() {
        Fixture f = Fixture.complete();
        f.driftCategoryArm(1e-6);
        SealedTest3Report.Result r = SealedTest3Report.score(f.baseline, f.candidate);
        assertFalse(r.passed());
        assertTrue(r.reasons().toString(), r.reasons().toString().contains("control arm moved"));
    }

    @Test
    public void aSlowerCandidateFailsEvenWhenItIsMoreAccurate() {
        Fixture f = Fixture.complete();
        f.improveCandidate(0.5);
        f.slowCandidate(1.05);
        SealedTest3Report.Result r = SealedTest3Report.score(f.baseline, f.candidate);
        assertFalse(r.passed());
        assertTrue(r.reasons().toString(), r.reasons().toString().contains("mean seconds ratio"));
    }

    @Test
    public void anArmThatDidNotCompleteIsAFailureAndNotASkippedRow() {
        Fixture f = Fixture.complete();
        f.breakCandidate(1);
        SealedTest3Report.Result r = SealedTest3Report.score(f.baseline, f.candidate);
        assertFalse(r.passed());
        assertTrue(r.reasons().toString(), r.reasons().toString().contains("registration failures"));
    }

    // ---- a synthetic set with this set's shape -------------------------------------------- //

    /** Ten series, two per image type, four motion profiles each: the shape of the real set. */
    private static final class Fixture {
        final Map<String, Map<String, SealedTest3Report.Row>> baseline = new LinkedHashMap<>();
        final Map<String, Map<String, SealedTest3Report.Row>> candidate = new LinkedHashMap<>();
        final java.util.List<String> keys = new java.util.ArrayList<>();

        static Fixture complete() {
            Fixture f = new Fixture();
            String[] classes = {"PHASE", "BRIGHTFIELD_DIC", "DENSE_FLUOR", "SPARSE_LOWLIGHT",
                    "FIDUCIAL_STATIC"};
            String[] motions = {"CURVED_OSCILLATING_DRIFT", "STEADY_DIRECTIONAL_DRIFT",
                    "SUBPIXEL_RANDOM_WALK", "INTERMITTENT_JUMPS"};
            double median = 0.01;
            for (String imageClass : classes) {
                for (int s = 1; s <= 2; s++) {
                    for (String motion : motions) {
                        String series = imageClass.toLowerCase(java.util.Locale.ROOT) + "_" + s;
                        f.add(imageClass, series, motion, median, 1.5);
                        median += 0.001;
                    }
                }
            }
            return f;
        }

        void add(String imageClass, String series, String motion, double median, double seconds) {
            String key = imageClass + '/' + series + '/' + motion + "/CLEAN";
            keys.add(key);
            for (Map<String, Map<String, SealedTest3Report.Row>> side
                    : java.util.Arrays.asList(baseline, candidate)) {
                Map<String, SealedTest3Report.Row> arms = new LinkedHashMap<>();
                arms.put(SealedTest3Report.SELECTOR_ARM, row(imageClass, series, motion,
                        SealedTest3Report.SELECTOR_ARM, true, median, seconds));
                arms.put(SealedTest3Report.CATEGORY_ARM, row(imageClass, series, motion,
                        SealedTest3Report.CATEGORY_ARM, true, median * 1.2, seconds));
                side.put(key, arms);
            }
        }

        private static SealedTest3Report.Row row(String imageClass, String series, String motion,
                                                 String arm, boolean ok, double median,
                                                 double seconds) {
            java.util.List<String> f = new java.util.ArrayList<>();
            for (int i = 0; i < 14; i++) f.add("");
            f.set(1, imageClass);
            f.set(2, series);
            f.set(4, motion);
            f.set(5, "CLEAN");
            f.set(6, arm);
            f.set(7, ok ? "ok" : "failed");
            f.set(8, String.valueOf(median));
            f.set(11, String.valueOf(seconds));
            f.set(13, "recipe");
            return SealedTest3Report.Row.parse(f);
        }

        private void mutate(String key, String arm, java.util.function.Function<
                SealedTest3Report.Row, SealedTest3Report.Row> change) {
            Map<String, SealedTest3Report.Row> arms = candidate.get(key);
            if (arms == null) return;   // already dropped, e.g. by dropCandidate
            arms.put(arm, change.apply(arms.get(arm)));
        }

        private SealedTest3Report.Row scaled(SealedTest3Report.Row r, double medianFactor,
                                             double secondsFactor, boolean ok) {
            return row(r.imageClass, r.series, r.motion, r.arm, ok,
                    r.median * medianFactor, r.seconds * secondsFactor);
        }

        void improveCandidate(double factor) {
            for (String k : keys) {
                mutate(k, SealedTest3Report.SELECTOR_ARM, r -> scaled(r, factor, 1, true));
            }
        }

        void worsenCandidate(double factor) {
            improveCandidate(factor);
        }

        void slowCandidate(double factor) {
            for (String k : keys) {
                mutate(k, SealedTest3Report.SELECTOR_ARM, r -> scaled(r, 1, factor, true));
            }
        }

        void worsenImageType(String imageClass, double addPx) {
            for (String k : keys) {
                if (!k.startsWith(imageClass + '/')) continue;
                mutate(k, SealedTest3Report.SELECTOR_ARM, r -> row(r.imageClass, r.series,
                        r.motion, r.arm, true, r.median + addPx, r.seconds));
            }
        }

        void worsenOneRecording(double addPx) {
            String k = keys.get(0);
            mutate(k, SealedTest3Report.SELECTOR_ARM, r -> row(r.imageClass, r.series, r.motion,
                    r.arm, true, r.median + addPx, r.seconds));
        }

        void breakCandidate(int howMany) {
            for (int i = 0; i < howMany; i++) {
                mutate(keys.get(i), SealedTest3Report.SELECTOR_ARM,
                        r -> row(r.imageClass, r.series, r.motion, r.arm, false, 0, 0));
            }
        }

        void dropCandidate(int howMany) {
            for (int i = 0; i < howMany; i++) candidate.remove(keys.get(i));
        }

        void driftCategoryArm(double addPx) {
            mutate(keys.get(0), SealedTest3Report.CATEGORY_ARM, r -> row(r.imageClass, r.series,
                    r.motion, r.arm, true, r.median + addPx, r.seconds));
        }
    }
}
