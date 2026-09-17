/*
 * Copyright (c) 2026 Jamie Malcolm
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio.api;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The trained model's scalar constants must be read when the program runs, not copied at build time.
 *
 * <p><b>The failure this exists to catch, in one sentence:</b> a {@code static final} field
 * initialised with a literal is a compile-time constant, so javac copies its value into every class
 * that reads it, and a class that is not recompiled after a retrain keeps answering with the previous
 * model's numbers while reporting no problem at all.
 *
 * <p>It has cost this project twice. It scored the first opening of the sealed test set against the
 * previously shipped model rather than the retrained one, and on 2026-08-18 it failed
 * {@code mvn -o test} with {@code expected:<2> but was:<0>} — a message that says nothing about the
 * real cause. Both are recorded in {@code docs/performance_optimisation_findings.md}.
 *
 * <p>The two tests below cover the two halves of the problem. One catches a build that has already
 * gone stale, and says so in plain terms. The other catches a regeneration that reopens the trap,
 * which no runtime check can see.
 */
public class ModelConstantsAreReadAtRuntimeTest {

    /** The generated model, as source. Regenerated wholesale by {@code logratio.FullSelectorTraining}. */
    private static final Path MODEL_SOURCE = Paths.get(
            "src", "main", "java", "logratio", "api", "AutomaticRegistrationSelectorModel.java");

    /** Every scalar the generator emits. Arrays are never compile-time constants, so they are safe. */
    private static final String[] SCALARS = {
            "FEATURE_COUNT", "MODEL_KIND", "TRAINED_ON", "CONFIDENCE_THRESHOLD"};

    /**
     * What this class reads directly must equal what the model actually holds.
     *
     * <p>The left-hand side of each comparison is an ordinary reference, which javac would have
     * copied into this test class at build time had the field stayed a compile-time constant. The
     * right-hand side is read reflectively, which always fetches the value the loaded class really
     * carries. Equal means the build is honest. Unequal means this test class is older than the model
     * and every other stale class in the build is lying in the same way — including, if the main
     * classes are the stale ones, the confidence threshold the selector compares against.
     */
    @Test
    public void aStaleBuildCannotDisagreeWithTheModelItLoaded() throws Exception {
        assertEquals(staleMessage("FEATURE_COUNT"), read("FEATURE_COUNT"),
                AutomaticRegistrationSelectorModel.FEATURE_COUNT);
        assertEquals(staleMessage("MODEL_KIND"), read("MODEL_KIND"),
                AutomaticRegistrationSelectorModel.MODEL_KIND);
        assertEquals(staleMessage("TRAINED_ON"), read("TRAINED_ON"),
                AutomaticRegistrationSelectorModel.TRAINED_ON);
        assertEquals(staleMessage("CONFIDENCE_THRESHOLD"), read("CONFIDENCE_THRESHOLD"),
                AutomaticRegistrationSelectorModel.CONFIDENCE_THRESHOLD);
    }

    /**
     * The generated source must route every scalar through {@code readAtRuntime}.
     *
     * <p>This is the half a running test cannot see: in a clean build, bare literals and wrapped ones
     * behave identically, and the difference only appears later, in someone else's incremental build,
     * as a wrong answer rather than an error. Since the model file is overwritten in full on every
     * retrain, the check that matters is on what the generator writes.
     *
     * <p>Skipped rather than failed when the source cannot be found, so the test still passes for
     * anyone running from a jar rather than a working tree.
     */
    @Test
    public void theGeneratedModelDeclaresNoBareLiteralConstants() throws IOException {
        if (!Files.isRegularFile(MODEL_SOURCE)) return;
        String source = new String(Files.readAllBytes(MODEL_SOURCE), StandardCharsets.UTF_8);
        for (String name : SCALARS) {
            int at = source.indexOf(" " + name + " = ");
            assertTrue("no declaration of " + name + " in " + MODEL_SOURCE, at >= 0);
            String initialiser = source.substring(at + name.length() + 4);
            assertTrue(name + " is declared as a bare literal, which javac copies into every class"
                            + " that reads it; a class not recompiled after the next retrain would"
                            + " keep the old value silently. Wrap it in readAtRuntime(...), and fix"
                            + " logratio.FullSelectorTraining so the next regeneration does too.",
                    initialiser.startsWith("readAtRuntime("));
        }
    }

    private static Object read(String name) throws Exception {
        Field field = AutomaticRegistrationSelectorModel.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static String staleMessage(String name) {
        return "This build is stale: " + name + " was copied into "
                + ModelConstantsAreReadAtRuntimeTest.class.getSimpleName()
                + " when it was compiled and no longer matches the model on the classpath. Run"
                + " `mvn -o clean test`. If this happens after a retrain, the cause is a compile-time"
                + " constant in AutomaticRegistrationSelectorModel;";
    }
}
