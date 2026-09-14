/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

/** Deliberately conflicting host class: the private runtime must never load this. */
public final class LongitudinalIsolatedWorker {
    private LongitudinalIsolatedWorker() { }
    public static java.util.Map<String,Object> runtimeInfo() {
        throw new AssertionError("POISON HOST ENGINE WAS USED");
    }
}
