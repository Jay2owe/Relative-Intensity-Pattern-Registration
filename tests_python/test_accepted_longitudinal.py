from dataclasses import replace
import numpy as np
import pytest
import ripr
from ripr import ImageType, MotionType, SelectionMode, LogRatioParameters
from ripr.registration import resolve_backend


def parameters(mode=SelectionMode.ACCEPTED_LONGITUDINAL):
    return replace(LogRatioParameters.recommended(image_type=ImageType.DENSE_FLUORESCENCE,
        motion_type=MotionType.INTERMITTENT_JUMPS), selection_mode=mode)


@pytest.mark.parametrize("mode", [SelectionMode.ACCEPTED_LONGITUDINAL, SelectionMode.ACCEPTED_MOVING_CELLS])
def test_no_python_substitute(mode):
    with pytest.raises(ValueError, match="pinned Java"):
        resolve_backend("python", parameters(mode))


def test_missing_java_never_falls_back(monkeypatch):
    monkeypatch.setattr(ripr.java_backend, "available", lambda: False)
    with pytest.raises(ripr.java_backend.JavaBackendUnavailable):
        resolve_backend("auto", parameters())


def test_fixed_execution_cannot_be_silently_overridden():
    with pytest.raises(ValueError, match="threads=0"):
        resolve_backend("java", replace(parameters(), threads=3))


def test_executed_recipe_is_retained():
    csv = """# elapsed_seconds,1.0
# workers,12
# recipe_provenance,selection=manual_explicit_accepted_java_recipe; recipe=bright_dim_references_phase_seed_rescue__dense_fluorescence; automatic_router_used=false
frame,dx,dy,theta,log2_gain,support,residual_before,residual_after,valid_fraction,status,repair
0,0,0,0,nan,-1,nan,nan,nan,,
"""
    parsed = ripr.java_backend._parse(csv)
    assert "phase_seed_rescue" in parsed.recipe_provenance
    assert "automatic_router_used=false" in parsed.recipe_provenance
    assert parsed.support[0] == -1
    assert np.isnan(parsed.residual_after[0])


def test_action_summary_keeps_estimation_provenance():
    from ripr.actions import _registration_summary
    from ripr.core import RegistrationResult
    from ripr.types import Transform
    values = np.array([np.nan])
    result = RegistrationResult((Transform(0,0,0),), (), np.array([-1]), (None,),
        values, values, values, values, (None,), (), 0,
        recipe_provenance="recipe=bright_dim_references_phase_seed_rescue__dense_fluorescence; automatic_router_used=false")
    assert "phase_seed_rescue" in _registration_summary(result)["recipe"]


def test_legacy_shortcut_not_silently_changed():
    assert LogRatioParameters.for_recipe("bright_dim").selection_mode is SelectionMode.LONGITUDINAL_ACCURACY


def test_accepted_helper_is_explicit(monkeypatch):
    import ripr.accepted_longitudinal as module
    calls=[]
    monkeypatch.setattr(module, "register", lambda image, p, **kwargs: calls.append((p,kwargs)))
    module.register_accepted(np.zeros((2,16,16),np.float32), recipe="moving_cells", channel=2)
    p,kwargs=calls[0]
    assert p.selection_mode is SelectionMode.ACCEPTED_MOVING_CELLS
    assert p.channel == 2
    assert kwargs["backend"] == "java"
