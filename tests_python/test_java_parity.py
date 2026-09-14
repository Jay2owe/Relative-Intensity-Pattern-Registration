"""Direct numerical comparison with the Java engine on its own analytic fixture."""

from dataclasses import replace
import os
import re
from pathlib import Path
import shutil
import subprocess

import numpy as np
import pytest

from ripr import (
    AlignerOptions,
    Estimator,
    PixelSupport,
    Reference,
    ReconciliationWeighting,
    RegistrationOptions,
    RobustNorm,
    RotationMode,
)
from ripr.core import PairFit, PairResult, PairUncertainty, _reconcile, register_frames
from ripr.types import Status, Transform
from ripr import ImageType, LogRatioParameters, MotionType, SelectionMode
from ripr.parameters import outlier_mads_for
from ripr.recording_selector import (
    CANDIDATE_MANIFEST_SHA256,
    FEATURE_NAMES,
    FEATURE_CONTRACT_VERSION,
    MODEL_ARTIFACT_SHA256,
    MODEL_KIND,
    MODEL_VERSION,
    PROTOCOL_SHA256,
    VALIDATION_STATUS,
    measure as measure_selector,
    neutral_pilot,
    select as select_selector,
)


ROOT = Path(__file__).resolve().parents[1]


def _java_engine_is_present() -> bool:
    """Both halves of the comparison have to exist: a runtime, and the compiled probe.

    These tests shell out to `java` to get the other language's answer, so without a JDK there
    is nothing to compare against. That is the ordinary case on a machine that pip-installed
    this package, and it is not a failure -- the Java engine is optional. Compiled classes are
    checked too, because a JDK with no `mvn test-compile` behind it fails the same way.
    """
    if shutil.which('java') is None:
        return False
    return (ROOT / 'target' / 'test-classes' / 'ripr' / 'core' / 'PythonParityProbe.class').exists()


pytestmark = pytest.mark.skipif(
    not _java_engine_is_present(),
    reason='no Java runtime, or ripr.core.PythonParityProbe has not been compiled into target/test-classes (run: mvn test-compile)',
)


def analytic_frame(width, height, dx=0.0, dy=0.0, gain=1.0):
    y, x = np.mgrid[:height, :width]
    u = 2 * np.pi * (x - dx) / width
    v = 2 * np.pi * (y - dy) / height
    signal = np.sin(3 * u + 0.4) * np.cos(2 * v - 0.7)
    signal += 0.70 * np.sin(5 * u - 1.1) * np.cos(7 * v + 0.2)
    signal += 0.45 * np.cos(11 * u + 0.9) * np.sin(6 * v + 1.3)
    signal += 0.30 * np.sin(13 * u + 2.1)
    signal += 0.30 * np.cos(9 * v - 0.5)
    return (gain * (2000.0 + 600.0 * signal)).astype(np.float32)


def java_result(classpath, estimator):
    arguments = ["java", "-cp", classpath, "ripr.core.PythonParityProbe"]
    if estimator is Estimator.AREA_CORRELATION_NEWTON:
        arguments.append("area")
    elif estimator is Estimator.AREA_CORRELATION_ECC:
        arguments.append("ecc")
    completed = subprocess.run(arguments, cwd=ROOT, check=True, text=True, capture_output=True)
    return np.loadtxt(completed.stdout.splitlines(), delimiter=",")


def test_recording_selector_evidence_and_decision_match_java():
    classpath_file = ROOT / "target" / "test-classpath.txt"
    probe = ROOT / "target" / "test-classes" / "ripr" / "core" / "PythonParityProbe.class"
    if not classpath_file.exists() or not probe.exists():
        pytest.skip("run Maven test-compile and dependency:build-classpath to enable Java parity")
    classpath = os.pathsep.join((str(ROOT / "target" / "test-classes"),
                                 str(ROOT / "target" / "classes"),
                                 classpath_file.read_text().strip()))
    completed = subprocess.run(
        ["java", "-cp", classpath, "ripr.core.PythonParityProbe", "selector"],
        cwd=ROOT, check=True, text=True, capture_output=True,
    )
    java_features, java_decision, java_contextual, java_version = {}, None, {}, None
    for line in completed.stdout.splitlines():
        fields = line.split(",")
        if fields[0] == "S":
            java_features[int(fields[1])] = (fields[2], float(fields[3]))
        elif fields[0] == "D":
            java_decision = fields[1:5]
        elif fields[0] == "A":
            java_contextual[(fields[1], fields[2], fields[3].lower() == "true")] = fields[4:9]
        elif fields[0] == "V":
            java_version = fields[1:8]
    requested = replace(
        LogRatioParameters.recommended(
            ImageType.PHASE_CONTRAST, MotionType.STEADY_DIRECTIONAL_DRIFT
        ),
        selection_mode=SelectionMode.AUTOMATIC,
        threads=1,
    )
    frames = np.stack([
        analytic_frame(96, 96, 1.25 * frame, -0.7 * frame, 2 ** (-frame / 8))
        for frame in range(5)
    ])
    pilot = neutral_pilot(requested)
    provisional = register_frames(frames, pilot.registration_options())
    evidence = measure_selector(frames, provisional.cumulative, requested)
    assert evidence.valid, evidence.reason
    assert len(java_features) == len(evidence.values) == 48
    assert java_version == [
        MODEL_VERSION,
        FEATURE_CONTRACT_VERSION,
        PROTOCOL_SHA256,
        CANDIDATE_MANIFEST_SHA256,
        MODEL_ARTIFACT_SHA256,
        VALIDATION_STATUS,
        MODEL_KIND,
    ]
    assert [java_features[index][0] for index in range(48)] == list(FEATURE_NAMES)
    # Scalar Java loops and NumPy reductions differ in their last arithmetic digits. The temporal
    # phase-correlation features include an FFT implementation change and use a looser, still
    # decision-protecting tolerance.
    java_values = np.asarray([java_features[index][1] for index in range(48)])
    assert np.asarray(evidence.values[:27]) == pytest.approx(java_values[:27], abs=5e-5)
    assert np.asarray(evidence.values[27:32]) == pytest.approx(java_values[27:32], abs=2e-3)
    assert np.asarray(evidence.values[32:]) == pytest.approx(java_values[32:], abs=0)
    _, decision = select_selector(requested, evidence)
    assert java_decision is not None
    assert decision.recipe == java_decision[0]
    assert decision.predicted_gain == pytest.approx(float(java_decision[1]), abs=1e-12)
    assert decision.confidence_threshold == pytest.approx(float(java_decision[2]), abs=0)
    assert decision.fallback == (java_decision[3].lower() == "true")
    assert len(java_contextual) == len(ImageType) * len(MotionType) * 2
    for image_type in ImageType:
        for motion_type in MotionType:
            contextual = LogRatioParameters(
                image_type=image_type,
                motion_type=motion_type,
                selection_mode=SelectionMode.AUTOMATIC,
                threads=1,
            )
            contextual_evidence = measure_selector(
                frames, provisional.cumulative, contextual
            )
            for rigid in (False, True):
                _, actual = select_selector(
                    replace(contextual, fit_rotation=rigid), contextual_evidence
                )
                expected = java_contextual[(image_type.name, motion_type.name, rigid)]
                assert actual.recipe == expected[0]
                assert actual.predicted_gain == pytest.approx(float(expected[1]), abs=1e-12)
                assert actual.confidence_threshold == pytest.approx(float(expected[2]), abs=0)
                assert actual.fallback == (expected[3].lower() == "true")
                assert actual.reason == expected[4]


def rigid_frame(width, height, truth, gain):
    y, x = np.mgrid[:height, :width]
    source_x, source_y = truth.inverse().apply(x, y, (width - 1) / 2, (height - 1) / 2)
    u = 2 * np.pi * source_x / width
    v = 2 * np.pi * source_y / height
    signal = np.sin(3 * u + 0.4) * np.cos(2 * v - 0.7)
    signal += 0.70 * np.sin(5 * u - 1.1) * np.cos(7 * v + 0.2)
    signal += 0.45 * np.cos(11 * u + 0.9) * np.sin(6 * v + 1.3)
    signal += 0.30 * np.sin(13 * u + 2.1)
    signal += 0.30 * np.cos(9 * v - 0.5)
    return (gain * (2000 + 600 * signal)).astype(np.float32)


@pytest.mark.parametrize(
    ("estimator", "geometry_tolerance", "residual_tolerance"),
    [
        (Estimator.LOG_RATIO_FIT, 1e-6, 1e-8),
        (Estimator.AREA_CORRELATION_NEWTON, 1e-6, 1e-8),
        (Estimator.AREA_CORRELATION_ECC, 1e-6, 1e-8),
    ],
)
def test_python_matches_java_analytic_fixture(estimator, geometry_tolerance, residual_tolerance):
    classpath_file = ROOT / "target" / "test-classpath.txt"
    probe = ROOT / "target" / "test-classes" / "ripr" / "core" / "PythonParityProbe.class"
    if not classpath_file.exists() or not probe.exists():
        pytest.skip("run Maven test-compile and dependency:build-classpath to enable Java parity")
    classpath = os.pathsep.join(
        (str(ROOT / "target" / "test-classes"), str(ROOT / "target" / "classes"), classpath_file.read_text().strip())
    )
    java = java_result(classpath, estimator)
    frames = np.stack(
        [analytic_frame(96, 96, 1.25 * t, -0.7 * t, 2 ** (-t / 8)) for t in range(5)]
    )
    options = RegistrationOptions(
        aligner=AlignerOptions(
            max_shift=8,
            norm=RobustNorm.HUBER,
            support=PixelSupport.ALL,
        ),
        reference=Reference.CONSECUTIVE,
        lags=(1, 2, 4, 8),
        threads=1,
        estimator=estimator,
    )
    python = register_frames(frames, options)
    actual = np.column_stack((
        np.arange(5),
        [item.dx for item in python.cumulative],
        [item.dy for item in python.cumulative],
        python.log2_gain,
        python.residual_before,
        python.residual_after,
    ))
    # NumPy vector reductions change the last few arithmetic bits, so parity is numerical rather than
    # bitwise. Both shipped estimators agree with Java to better than one millionth of a pixel here.
    assert actual[:, 1:4] == pytest.approx(java[:, 1:4], abs=geometry_tolerance)
    assert actual[1:, 4:6] == pytest.approx(java[1:, 4:6], abs=residual_tolerance)


def test_python_matches_java_rigid_multilag_fixture():
    classpath_file = ROOT / "target" / "test-classpath.txt"
    probe = ROOT / "target" / "test-classes" / "ripr" / "core" / "PythonParityProbe.class"
    if not classpath_file.exists() or not probe.exists():
        pytest.skip("run Maven test-compile and dependency:build-classpath to enable Java parity")
    classpath = os.pathsep.join((str(ROOT / "target" / "test-classes"),
                                 str(ROOT / "target" / "classes"),
                                 classpath_file.read_text().strip()))
    completed = subprocess.run(
        ["java", "-cp", classpath, "ripr.core.PythonParityProbe", "rigid"],
        cwd=ROOT, check=True, text=True, capture_output=True,
    )
    java = np.loadtxt(completed.stdout.splitlines(), delimiter=",")
    from ripr import Transform
    frames = np.stack([
        rigid_frame(96, 96, Transform(0.65 * t, -0.4 * t, np.deg2rad(0.7 * t)),
                    2 ** (-t / 8))
        for t in range(5)
    ])
    options = RegistrationOptions(
        aligner=AlignerOptions(max_shift=8, fit_rotation=True,
                               max_rotation=np.deg2rad(5), norm=RobustNorm.HUBER,
                               support=PixelSupport.ALL),
        reference=Reference.MULTILAG, lags=(1, 2, 4), threads=1,
    )
    python = register_frames(frames, options)
    actual = np.column_stack((np.arange(5),
                              [item.dx for item in python.cumulative],
                              [item.dy for item in python.cumulative],
                              [item.theta for item in python.cumulative],
                              python.log2_gain, python.residual_before, python.residual_after))
    # Dense NumPy normal equations and Java's banded accumulation order differ slightly after the
    # same pair fits. The declared parity limits are 1e-4 px, 2e-6 rad, 4e-6 log2 gain and 1e-8
    # residual units; all are well below the engine's recovery tolerances.
    assert actual[:, 1:3] == pytest.approx(java[:, 1:3], abs=1e-4)
    assert actual[:, 3] == pytest.approx(java[:, 3], abs=2e-6)
    assert actual[1:, 4] == pytest.approx(java[1:, 4], abs=4e-6)
    assert actual[1:, 5:7] == pytest.approx(java[1:, 5:7], abs=1e-8)


def test_python_matches_java_known_event_rotation_and_diagnostics(tmp_path):
    classpath_file = ROOT / "target" / "test-classpath.txt"
    probe = ROOT / "target" / "test-classes" / "ripr" / "core" / "PythonParityProbe.class"
    if not classpath_file.exists() or not probe.exists():
        pytest.skip("run Maven test-compile and dependency:build-classpath to enable Java parity")
    classpath = os.pathsep.join((
        str(ROOT / "target" / "test-classes"),
        str(ROOT / "target" / "classes"),
        classpath_file.read_text().strip(),
    ))
    frames = np.stack([
        rigid_frame(
            96, 96,
            Transform(0.35 * frame, -0.22 * frame,
                      np.deg2rad(0 if frame < 4 else 2)),
            2 ** (-frame / 20),
        )
        for frame in range(8)
    ])
    raw_fixture = tmp_path / "event_frames_f32le.raw"
    frames.astype("<f4", copy=False).tofile(raw_fixture)
    completed = subprocess.run(
        ["java", "-cp", classpath, "ripr.core.PythonParityProbe",
         "event_raw", str(raw_fixture)],
        cwd=ROOT, check=True, text=True, capture_output=True,
    )
    java_events, java_frames = [], []
    for line in completed.stdout.splitlines():
        fields = line.split(",")
        if fields[0] == "E":
            java_events.append(fields[1:])
        elif fields[0] == "F":
            java_frames.append([float(value) for value in fields[1:]])
    options = RegistrationOptions(
        aligner=AlignerOptions(
            max_shift=8,
            max_rotation=np.deg2rad(5),
            norm=RobustNorm.HUBER,
            support=PixelSupport.ALL,
        ),
        rotation_mode=RotationMode.KNOWN_EVENTS,
        rotation_event_frames=(4,),
        rotation_event_window=2,
        reference=Reference.MULTILAG,
        lags=(1, 2, 4),
        threads=1,
    )
    python = register_frames(frames, options)
    assert python.event_rotations is not None
    assert len(java_events) == len(python.event_rotations.events) == 1
    for java, event in zip(java_events, python.event_rotations.events):
        assert event.frame == int(java[0])
        assert event.delta_theta == pytest.approx(float(java[1]), abs=2e-6)
        assert event.cumulative_theta == pytest.approx(float(java[2]), abs=2e-6)
        assert event.candidate_pairs == int(java[3])
        assert event.usable_pairs == int(java[4])
        assert event.inlier_pairs == int(java[5])
        # A four-pair circular MAD can choose a neighbouring order statistic after the Java and
        # NumPy pair solvers differ by their normal floating-point reduction noise. The estimate
        # itself retains the frozen 2e-6-radian gate; this spread diagnostic agrees to 4e-6.
        assert event.circular_mad == pytest.approx(float(java[6]), abs=4e-6)
        assert event.status.name == java[7]
        assert (
            event.first_pre_frame,
            event.last_pre_frame,
            event.first_post_frame,
            event.last_post_frame,
        ) == tuple(int(value) for value in java[8:12])

    java_frames = np.asarray(java_frames)
    actual = np.column_stack((
        np.arange(8),
        [item.dx for item in python.cumulative],
        [item.dy for item in python.cumulative],
        [item.theta for item in python.cumulative],
    ))
    # Fixed-angle fits are a two-degree-of-freedom reduction. NumPy's dense reductions and Java's
    # scalar accumulation differ more after the overdetermined graph combines all crossing pairs
    # than they do in one pair: this fixture's measured maximum is 4.85e-4 pixels.
    assert actual[:, 1:3] == pytest.approx(java_frames[:, 1:3], abs=6e-4)
    assert actual[:, 3] == pytest.approx(java_frames[:, 3], abs=2e-6)
    assert len(set(actual[:4, 3])) == 1
    assert len(set(actual[4:, 3])) == 1


def test_python_matches_java_confidence_weighted_reconciliation():
    classpath_file = ROOT / "target" / "test-classpath.txt"
    probe = ROOT / "target" / "test-classes" / "ripr" / "core" / "ReconciliationParityProbe.class"
    if not classpath_file.exists() or not probe.exists():
        pytest.skip("run Maven test-compile and dependency:build-classpath to enable Java parity")
    classpath = os.pathsep.join((str(ROOT / "target" / "test-classes"),
                                 str(ROOT / "target" / "classes"),
                                 classpath_file.read_text().strip()))
    completed = subprocess.run(
        ["java", "-cp", classpath, "ripr.core.ReconciliationParityProbe"],
        cwd=ROOT, check=True, text=True, capture_output=True,
    )
    cumulative_rows, edge_rows, uncertainty_row = {}, {}, None
    for line in completed.stdout.splitlines():
        fields = line.split(",")
        if fields[0] == "C":
            cumulative_rows[(fields[1], int(fields[2]))] = np.asarray(fields[3:6], dtype=float)
        elif fields[0] == "E":
            edge_rows[(fields[1], int(fields[2]))] = np.asarray(fields[3:6], dtype=float)
        elif fields[0] == "U":
            uncertainty_row = np.asarray(fields[1:], dtype=float)

    covariance_values = (
        (0.10, 0.02, 0.20),
        (0.15, -0.01, 0.12),
        (0.08, 0.00, 0.20),
        (0.20, 0.03, 0.18),
        (0.12, -0.02, 0.14),
        (3.00, 0.20, 2.00),
    )
    endpoints = ((0, 1), (1, 2), (2, 3), (0, 2), (1, 3), (0, 3))
    movements = ((1, 0.1), (1, -0.1), (1, 0), (2, 0), (2, -0.1), (10, 2))
    pairs = []
    for (source, target), (dx, dy), (xx, xy, yy) in zip(
        endpoints, movements, covariance_values
    ):
        uncertainty = PairUncertainty.from_covariance([[xx, xy], [xy, yy]])
        fit = PairFit(Transform(dx, dy), 1, 0.1, 0, 1, 1, Status.OK, uncertainty)
        pairs.append(PairResult(source, target, fit))

    for weighting in ReconciliationWeighting:
        cumulative, _, influences = _reconcile(
            Reference.MULTILAG, 4, 0, pairs, weighting, return_influences=True
        )
        for frame, transform in enumerate(cumulative):
            java = cumulative_rows[(weighting.name, frame)]
            assert [transform.dx, transform.dy, transform.theta] == pytest.approx(java, abs=1e-8)
        for index, influence in enumerate(influences):
            java = edge_rows[(weighting.name, index)]
            assert influence.standardized_residual == pytest.approx(java[0], abs=1e-6)
            assert influence.robust_factor == pytest.approx(java[1], abs=1e-6)
            assert influence.final_information_scale == pytest.approx(java[2], abs=1e-7)

    first = pairs[0].fit.uncertainty
    expected = np.concatenate((first.covariance.ravel(), first.information.ravel()))
    assert uncertainty_row == pytest.approx(expected, abs=1e-7)


JAVA_RECOMMENDATION_SOURCE = (
    ROOT / "src/main/java/ripr/api/RelativeIntensityPatternParameters.java"
)


def test_recommendation_resolves_outlier_repair_exactly_as_java_does():
    """A declared motion type must mean the same thing in both engines.

    Not an engine comparison and it needs no Java toolchain: it reads the rule
    out of the Java source, which is the authority for it, and checks that the
    Python recommendation layer resolves to the same numbers for every
    declaration a caller can make.

    This is the test that was missing. Until 2026-08-29 the Python
    recommendation left step-outlier repair on for declared intermittent-jump
    motion while Java turned it off, which put the trajectory 3.2 px out on
    every jump folder of the controlled benchmark. See
    `docs/repair_jump_motion_parity_findings.md`.
    """
    source = JAVA_RECOMMENDATION_SOURCE.read_text(encoding="utf-8")
    stated = re.search(
        r"outlierMads\s*=\s*motion\s*==\s*MotionType\.(\w+)\s*\?\s*"
        r"([0-9.]+)\s*:\s*([0-9.]+)\s*;",
        source,
    )
    assert stated, (
        "the Java recommendation no longer states outlierMads as one "
        "motion-dependent expression. Read it and update "
        "ripr.parameters.outlier_mads_for by hand; do not delete this test."
    )
    exceptional = MotionType.parse(stated.group(1).lower())
    when_exceptional, otherwise = float(stated.group(2)), float(stated.group(3))

    assert outlier_mads_for(exceptional) == when_exceptional
    for motion in MotionType:
        expected = when_exceptional if motion is exceptional else otherwise
        assert outlier_mads_for(motion) == expected

    for image in ImageType:
        for motion in MotionType:
            expected = when_exceptional if motion is exceptional else otherwise
            recommended = LogRatioParameters.recommended(
                image_type=image, motion_type=motion
            )
            assert recommended.outlier_mads == expected, (image, motion)
            requested = LogRatioParameters(
                image_type=image,
                motion_type=motion,
                selection_mode=SelectionMode.RECOMMENDED,
            )
            assert requested.resolve().outlier_mads == expected, (image, motion)
            assert (
                recommended.registration_options().outlier_mads == expected
            ), (image, motion)
