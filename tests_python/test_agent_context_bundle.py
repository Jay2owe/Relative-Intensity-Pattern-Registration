from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "generate_agent_context.py"


def test_portable_context_artifacts_are_current():
    completed = subprocess.run(
        [sys.executable, str(SCRIPT), "--check"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    assert completed.returncode == 0, completed.stderr or completed.stdout


def test_context_bundle_contains_every_public_topic():
    from ripr import context

    bundle = json.loads((ROOT / "ripr_context.json").read_text(encoding="utf-8"))
    assert bundle["package"] == "ripr"
    assert bundle["version"]
    assert [item["topic"] for item in bundle["topics"]] == list(context.topics())
    assert all(item["ok"] is True for item in bundle["topics"])


def test_agent_guide_mentions_structured_and_python_entrypoints():
    guide = (ROOT / "README_AI.md").read_text(encoding="utf-8")
    assert "ripr_context.json" in guide
    assert "from ripr import context" in guide
    assert "context.search('backend fallback')" in guide
