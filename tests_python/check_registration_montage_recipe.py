"""Executable contract check for the reusable registration montage producer."""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path


def main() -> None:
    candidate = Path(sys.argv[1]).resolve()
    spec = importlib.util.spec_from_file_location("registration_montage_recipe", candidate)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    assert callable(module.produce)
    assert module.checked_label_lines("RIPR | Automatic") == ["RIPR", "Automatic"]
    try:
        module.checked_label_lines("this label is deliberately much too wide for one panel")
    except ValueError:
        pass
    else:
        raise AssertionError("oversized label was accepted")


if __name__ == "__main__":
    main()
