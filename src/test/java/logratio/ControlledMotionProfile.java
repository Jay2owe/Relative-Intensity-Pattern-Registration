/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package logratio;

import java.util.Random;

/** Fixed camera-motion paths applied unchanged to every controlled signal class. */
enum ControlledMotionProfile {
    CURVED_OSCILLATING_DRIFT {
        @Override void fill(int[] x, int[] y) {
            for (int t = 0; t < x.length; t++) {
                x[t] = (int) Math.round(24 * Math.sin(2 * Math.PI * t / 37.0) + 0.55 * t);
                y[t] = (int) Math.round(-18 * Math.cos(2 * Math.PI * t / 29.0) + 0.35 * t);
            }
            rebase(x, y);
        }
    },
    STEADY_DIRECTIONAL_DRIFT {
        @Override void fill(int[] x, int[] y) {
            for (int t = 0; t < x.length; t++) {
                x[t] = (int) Math.round(1.20 * t);
                y[t] = (int) Math.round(-0.70 * t);
            }
            rebase(x, y);
        }
    },
    SUBPIXEL_RANDOM_WALK {
        @Override void fill(int[] x, int[] y) {
            Random random = new Random(2_026_081_3L);
            for (int t = 1; t < x.length; t++) {
                x[t] = x[t - 1] + random.nextInt(3) - 1;
                y[t] = y[t - 1] + random.nextInt(3) - 1;
            }
        }
    },
    INTERMITTENT_JUMPS {
        @Override void fill(int[] x, int[] y) {
            for (int t = 0; t < x.length; t++) {
                x[t] = (int) Math.round(0.45 * t);
                y[t] = (int) Math.round(-0.25 * t);
                if (t >= 12) { x[t] += 12; y[t] -= 8; }
                if (t >= 27) { x[t] -= 20; y[t] += 16; }
                if (t >= 39) { x[t] += 16; y[t] -= 12; }
            }
            rebase(x, y);
        }
    };

    abstract void fill(int[] x, int[] y);

    private static void rebase(int[] x, int[] y) {
        int x0 = x[0];
        int y0 = y[0];
        for (int t = 0; t < x.length; t++) {
            x[t] -= x0;
            y[t] -= y0;
        }
    }
}
