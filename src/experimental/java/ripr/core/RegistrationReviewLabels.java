/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

/** Short review titles derived from executed recipes, never guessed from the input's name. */
public final class RegistrationReviewLabels {
    private RegistrationReviewLabels() { }

    public static String forRecipe(String recipeId) {
        if (recipeId == null || recipeId.isBlank())
            throw new IllegalArgumentException("Executed (or failed attempted) recipe is missing");
        String route = recipeId.split("__", 2)[0];
        if (route.equals("bright_dim_references") || route.equals("bright_dim_references_guarded_terminal_rigid_chain"))
            return "RIPR | Bright/dim";
        if (route.equals("edge_dark_landmarks") || route.equals("edge_dark_landmarks_guarded_rigid_jump"))
            return "RIPR | Landmarks";
        if (recipeId.startsWith("biological_foreground_recommended__"))
            return "RIPR | Moving cells";
        throw new IllegalArgumentException("Unknown recipe needs an explicit review label: " + recipeId);
    }

    public static void verify(String recipeId, String displayLabel) {
        if (!forRecipe(recipeId).equals(displayLabel))
            throw new IllegalArgumentException("Review title does not identify the recorded recipe");
    }
}
