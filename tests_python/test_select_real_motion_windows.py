from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

import numpy as np


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "select_real_motion_windows.py"
SPEC = spec_from_file_location("select_real_motion_windows", SCRIPT)
selector = module_from_spec(SPEC)
SPEC.loader.exec_module(selector)


def test_consensus_uses_the_agreeing_channels_and_rejects_one_outlier():
    dx, dy, agreeing, response = selector.consensus_shift([
        (2.0, -1.0, 0.9, 0), (2.1, -1.1, 0.8, 1),
        (1.9, -0.9, 0.7, 2), (19.0, 12.0, 0.95, 3),
    ], maximum_shift=30.0)

    assert agreeing == 3
    assert abs(dx - 2.0) < 0.15
    assert abs(dy + 1.0) < 0.15
    assert response == 0.8


def test_motion_labels_distinguish_jumps_from_steady_drift():
    steady = np.tile([0.5, 0.0], (39, 1))
    steady_positions = np.vstack(([0.0, 0.0], np.cumsum(steady, axis=0)))
    jump = steady.copy()
    jump[20] = [8.0, 0.0]
    jump_positions = np.vstack(([0.0, 0.0], np.cumsum(jump, axis=0)))

    assert selector.motion_type(steady, steady_positions) == "STEADY_DIRECTIONAL_DRIFT"
    assert selector.motion_type(jump, jump_positions) == "INTERMITTENT_JUMPS"
