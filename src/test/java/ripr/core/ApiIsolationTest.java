/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Asserts, from the compiled bytecode, that the estimator depends on no ImageJ class.
 *
 * <p>Not a stylistic preference. Three things follow from it, and all three are load-bearing:
 *
 * <ul>
 *   <li>The engine is testable without a headless ImageJ, which is why every other test in this package
 *       runs in milliseconds against synthetic frames instead of standing up an {@code ImagePlus}.</li>
 *   <li>The plugin's public Java API can honestly promise it opens no dialogs, shows no windows and
 *       needs no active ImageJ window — because the code that does the work cannot reach any of that.</li>
 *   <li>Another plugin can compile the estimator in without inheriting a UI. If a convenience import
 *       ever creeps in, that option quietly closes, and the failure shows up only when someone tries.</li>
 * </ul>
 *
 * <p>Checked against the constant pool rather than by reading the source, because an import is not the
 * only way to reach a class and a source-level grep would miss the rest.
 */
public class ApiIsolationTest {

    private static final Class<?>[] ENGINE = {
            Transform.class,
            LogPlane.class,
            RobustNorm.class,
            PairAligner.class,
            Reconciler.class,
            Warper.class,
            ChainRepair.class,
            PyramidCache.class,
            PairScheduler.class,
            Registration.class,
            FrameSource.class,
    };

    @Test
    public void noEngineClassReferencesImageJ() throws IOException {
        for (Class<?> c : ENGINE) {
            assertNoReference(c, "ij/");
        }
    }

    /** Nor anything else that would drag in a UI toolkit or a scientific-library dependency. */
    @Test
    public void noEngineClassReferencesAUiToolkitOrAnExternalLibrary() throws IOException {
        for (Class<?> c : ENGINE) {
            assertNoReference(c, "javax/swing/");
            assertNoReference(c, "java/awt/");
            assertNoReference(c, "net/imglib2/");
            assertNoReference(c, "org/scijava/");
            assertNoReference(c, "gnu/trove/");
            assertNoReference(c, "mcib3d/");
        }
    }

    /**
     * The engine must not read or write files either.
     *
     * <p>Same reason as the UI: a facade that promises to write nothing cannot keep that promise if the
     * layer underneath it can open a stream.
     */
    @Test
    public void noEngineClassTouchesTheFilesystem() throws IOException {
        for (Class<?> c : ENGINE) {
            assertNoReference(c, "java/io/File");
            assertNoReference(c, "java/nio/file/");
        }
    }

    /** Nested classes count — a helper is as capable of an unwanted import as its outer class. */
    @Test
    public void nestedClassesAreCoveredToo() throws IOException {
        for (Class<?> outer : ENGINE) {
            for (Class<?> nested : outer.getDeclaredClasses()) {
                assertNoReference(nested, "ij/");
                assertNoReference(nested, "java/awt/");
            }
        }
    }

    /** The scan itself must be able to fail, or it is asserting nothing. */
    @Test
    public void theScanDetectsAReferenceThatIsReallyThere() throws IOException {
        // PairScheduler genuinely uses the concurrency package; if the scan cannot see that, it
        // cannot see an ij import either.
        assertTrue("the scan must find a reference that is present",
                references(PairScheduler.class, "java/util/concurrent/"));
        assertFalse("and must not invent one that is absent",
                references(Transform.class, "java/util/concurrent/"));
    }

    private static void assertNoReference(Class<?> c, String internalName) throws IOException {
        assertFalse(c.getName() + " must not reference " + internalName
                        + " — see this class's javadoc for why the engine stays ImageJ-free",
                references(c, internalName));
    }

    private static boolean references(Class<?> c, String internalName) throws IOException {
        byte[] bytes = bytecode(c);
        byte[] needle = internalName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + needle.length <= bytes.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static byte[] bytecode(Class<?> c) throws IOException {
        String resource = c.getName().replace('.', '/') + ".class";
        try (InputStream in = c.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull("could not read bytecode for " + c.getName(), in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }
}
