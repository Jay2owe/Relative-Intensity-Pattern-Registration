/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ripr.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Machine-readable fixed graph for Java/Python confidence-weighting parity tests. */
public final class ReconciliationParityProbe {

    private ReconciliationParityProbe() {
    }

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        List<Reconciler.Observation> graph = graph();
        for (Reconciler.Weighting weighting : Reconciler.Weighting.values()) {
            Reconciler.Solution solution = Reconciler.multiLag(4, graph,
                    new Reconciler.Options(weighting, 2, 50.0));
            for (int frame = 0; frame < solution.cumulative.length; frame++) {
                Transform transform = solution.cumulative[frame];
                System.out.printf(Locale.ROOT, "C,%s,%d,%.17g,%.17g,%.17g,%d%n",
                        weighting, frame, transform.dx, transform.dy, transform.theta,
                        solution.support[frame]);
            }
            for (Reconciler.PairInfluence influence : solution.influences) {
                System.out.printf(Locale.ROOT,
                        "E,%s,%d,%.17g,%.17g,%.17g,%s,%s%n",
                        weighting, influence.planIndex, influence.standardizedResidual,
                        influence.robustFactor, influence.finalInformationScale,
                        influence.factorFloored, influence.uncertaintyFallback);
            }
        }
        PairUncertainty first = graph.get(0).uncertainty;
        double[] covariance = first.covariance();
        double[] information = first.information();
        System.out.printf(Locale.ROOT,
                "U,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g%n",
                covariance[0], covariance[1], covariance[2], covariance[3],
                information[0], information[1], information[2], information[3]);
    }

    private static List<Reconciler.Observation> graph() {
        List<Reconciler.Observation> output = new ArrayList<>();
        output.add(edge(0, 1, 1.0, 0.1, covariance(0.10, 0.02, 0.20), 0));
        output.add(edge(1, 2, 1.0, -0.1, covariance(0.15, -0.01, 0.12), 1));
        output.add(edge(2, 3, 1.0, 0.0, covariance(0.08, 0.00, 0.20), 2));
        output.add(edge(0, 2, 2.0, 0.0, covariance(0.20, 0.03, 0.18), 3));
        output.add(edge(1, 3, 2.0, -0.1, covariance(0.12, -0.02, 0.14), 4));
        output.add(edge(0, 3, 10.0, 2.0, covariance(3.0, 0.20, 2.0), 5));
        return output;
    }

    private static Reconciler.Observation edge(
            int from, int to, double dx, double dy, PairUncertainty uncertainty, int index) {
        return new Reconciler.Observation(from, to, Transform.translation(dx, dy),
                uncertainty, index);
    }

    private static PairUncertainty covariance(double xx, double xy, double yy) {
        return PairUncertainty.fromCovariance(2, new double[]{xx, xy, xy, yy},
                Double.NaN, false, false, false);
    }
}
