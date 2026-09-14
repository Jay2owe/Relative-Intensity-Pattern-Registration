/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr;

import ripr.api.AutomaticReconciliationSelectorModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Gated selector-policy freeze; fitting is skipped when oracle headroom cannot clear its gate. */
public final class AutomaticReconciliationSelectorTraining {

    private AutomaticReconciliationSelectorTraining() {
    }

    public static void main(String[] args) throws IOException {
        if (!Boolean.getBoolean("automaticReconciliationSelector.run")) {
            throw new IllegalStateException(
                    "set -DautomaticReconciliationSelector.run=true for the gated run");
        }
        Path output = Paths.get("target", "confidence_weighted_reconciliation_v1", "selector");
        Files.createDirectories(output);
        String policy = "model_kind=" + AutomaticReconciliationSelectorModel.modelKind() + "\n"
                + "safe_fallback=" + AutomaticReconciliationSelectorModel.safeFallback() + "\n"
                + "feature_order_sha256="
                + AutomaticReconciliationSelectorModel.featureOrderSha256() + "\n"
                + "status=FIXED_ONLY_INSUFFICIENT_ORACLE_HEADROOM\n";
        Files.write(output.resolve("frozen_selector_policy.properties"),
                policy.getBytes(StandardCharsets.UTF_8));
    }
}
