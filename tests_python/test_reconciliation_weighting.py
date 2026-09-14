import struct

import numpy as np
import pytest

from ripr import PairUncertainty, ReconciliationWeighting, Reference, Transform
from ripr.core import (
    AlignerOptions,
    PairFit,
    PairResult,
    RegistrationOptions,
    _reconcile,
    register_frames,
)
from ripr.types import Status


def _pair(source, target, transform, covariance=None):
    uncertainty = (
        PairUncertainty.unavailable(2)
        if covariance is None
        else PairUncertainty.from_covariance(np.asarray(covariance, dtype=float))
    )
    fit = PairFit(transform, 1.0, 0.1, 0.0, 1.0, 1, Status.OK, uncertainty)
    return PairResult(source, target, fit)


def _bits(transforms):
    return [struct.pack(">ddd", item.dx, item.dy, item.theta) for item in transforms]


def test_explicit_equal_reconciliation_is_bit_exact_with_legacy_default():
    pairs = [
        _pair(0, 1, Transform(1.2, -0.3)),
        _pair(1, 2, Transform(1.1, 0.2)),
        _pair(0, 2, Transform(2.4, -0.2)),
    ]
    legacy, legacy_support = _reconcile(Reference.MULTILAG, 3, 0, pairs)
    explicit, explicit_support = _reconcile(
        Reference.MULTILAG, 3, 0, pairs, ReconciliationWeighting.EQUAL
    )
    assert _bits(explicit) == _bits(legacy)
    assert np.array_equal(explicit_support, legacy_support)


def test_uncertainty_weighting_suppresses_a_low_information_bad_edge():
    good = np.eye(2) * 0.01
    weak = np.eye(2) * 100.0
    pairs = [
        _pair(0, 1, Transform(1, 0), good),
        _pair(1, 2, Transform(1, 0), good),
        _pair(2, 3, Transform(1, 0), good),
        _pair(0, 3, Transform(9, 0), weak),
    ]
    equal, _ = _reconcile(Reference.MULTILAG, 4, 0, pairs)
    weighted, _, influences = _reconcile(
        Reference.MULTILAG,
        4,
        0,
        pairs,
        ReconciliationWeighting.UNCERTAINTY,
        return_influences=True,
    )
    assert abs(weighted[-1].dx - 3) < abs(equal[-1].dx - 3)
    assert weighted[-1].dx == pytest.approx(3, abs=0.01)
    assert influences[-1].final_information_scale < influences[0].final_information_scale


def test_robust_reconciliation_downweights_a_graph_inconsistent_edge():
    pairs = [
        _pair(0, 1, Transform(1, 0)),
        _pair(1, 2, Transform(1, 0)),
        _pair(2, 3, Transform(1, 0)),
        _pair(0, 2, Transform(2, 0)),
        _pair(1, 3, Transform(2, 0)),
        _pair(0, 3, Transform(10, 0)),
    ]
    equal, _ = _reconcile(Reference.MULTILAG, 4, 0, pairs)
    robust, _, influences = _reconcile(
        Reference.MULTILAG,
        4,
        0,
        pairs,
        ReconciliationWeighting.ROBUST,
        return_influences=True,
    )
    assert abs(robust[-1].dx - 3) < abs(equal[-1].dx - 3)
    assert influences[-1].robust_factor < 0.2
    assert all(item.used for item in influences)

    reversed_result, _, reversed_influences = _reconcile(
        Reference.MULTILAG,
        4,
        0,
        list(reversed(pairs)),
        ReconciliationWeighting.ROBUST,
        return_influences=True,
    )
    assert _bits(reversed_result) == _bits(robust)
    factors = {(item.from_frame, item.to_frame): item.robust_factor for item in influences}
    reversed_factors = {
        (item.from_frame, item.to_frame): item.robust_factor for item in reversed_influences
    }
    assert reversed_factors == pytest.approx(factors, abs=1e-12)


def test_uncertainty_covariance_is_defensive_bounded_and_scale_aware():
    uncertainty = PairUncertainty.bounded(
        np.diag([1e-12, 1e6, 4e-4]), radius=50.0
    )
    assert uncertainty.available
    assert uncertainty.eigenvalue_floored
    assert uncertainty.eigenvalue_capped
    covariance = uncertainty.covariance
    covariance[0, 0] = 999
    assert uncertainty.covariance[0, 0] != 999
    scaled = uncertainty.scale_translations(2)
    assert scaled.covariance[0, 0] == pytest.approx(4 * uncertainty.covariance[0, 0])
    assert scaled.covariance[2, 2] == pytest.approx(uncertainty.covariance[2, 2])


def test_combined_strategy_runs_through_registration_and_attaches_diagnostics():
    y, x = np.mgrid[:48, :48]
    base = 2000 + 400 * np.sin(0.31 * x + 0.2) * np.cos(0.23 * y - 0.4)
    frames = np.stack([
        np.roll(np.roll(base, frame, axis=1), -frame, axis=0)
        for frame in range(5)
    ]).astype(np.float32)
    options = RegistrationOptions(
        aligner=AlignerOptions(levels=1, max_shift=6),
        reference=Reference.MULTILAG,
        lags=(1, 2, 4),
        threads=1,
        reconciliation_weighting=ReconciliationWeighting.COMBINED,
    )
    result = register_frames(frames, options)
    assert result.pairs
    assert result.unavailable_uncertainty_pairs < len(result.pairs)
    assert all(pair.influence is not None for pair in result.pairs)
    assert [pair.influence.plan_index for pair in result.pairs] == list(range(len(result.pairs)))
    assert all(pair.influence.used for pair in result.pairs)
    assert result.downweighted_pairs >= 0

    scaled = result.scale_translations(2)
    for original, changed in zip(result.pairs, scaled.pairs):
        if original.fit.uncertainty.available:
            assert changed.fit.uncertainty.covariance[0, 0] == pytest.approx(
                4 * original.fit.uncertainty.covariance[0, 0]
            )
