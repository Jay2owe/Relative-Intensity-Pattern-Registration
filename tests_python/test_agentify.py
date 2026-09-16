import json
from pathlib import Path
import subprocess
import sys

import numpy as np
import tifffile

from ripr import context
from ripr.actions import dispatch, discover, serialize


def test_registry_reconciles_against_live_backend():
    report = discover()
    assert report["missing"] == []
    assert report["unregistered"] == []
    assert {item["name"] for item in report["actions"]} == {
        "inspect_tiff", "discover_tiffs", "rank_channels", "recommend",
        "estimate", "register", "register_batch",
    }


def test_recommend_action_coerces_recipe_and_returns_json_clean_result():
    response = dispatch("recommend", {"recipe": "bright_dim", "channel": "2"})
    assert response["ok"] is True
    assert response["result"]["recipe"] == "bright_dim"
    assert response["result"]["parameters"]["channel"] == 2
    json.dumps(response, allow_nan=False)


def test_write_actions_guard_existing_outputs(tmp_path):
    existing = tmp_path / "already.tif"
    existing.touch()
    response = dispatch("register", {"input": "missing.tif", "output_path": str(existing)})
    assert response["ok"] is False
    assert "confirm_overwrite" in response["error"]["message"]
    response = dispatch("register_batch", {"input_directory": "in", "output_directory": "out", "overwrite": True})
    assert response["ok"] is False
    assert "confirm_overwrite" in response["error"]["message"]


def test_malformed_dispatch_returns_json_error_envelope():
    response = dispatch("not_an_action", {})
    assert response["ok"] is False
    json.dumps(response, allow_nan=False)


def test_serialize_replaces_nonfinite_numbers_and_summarises_arrays():
    value = serialize({"nan": float("nan"), "image": __import__("numpy").zeros((2, 3))})
    assert value["nan"] is None
    assert value["image"]["shape"] == [2, 3]
    json.dumps(value, allow_nan=False)


def test_public_context_supports_topics_and_structured_search():
    text = context.read("simple_registration")
    assert "recipe" in text
    result = context.read("backend and troubleshooting", format="json")
    assert result["topic"] == "backend_troubleshooting"
    assert context.search("Java fallback")["results"][0]["topic"] == "backend_troubleshooting"
    unknown = context.read("missing", format="json")
    assert unknown["ok"] is False
    assert context.read("simple_registration", format="yaml")["ok"] is False


def test_equivalent_script_compiles_with_parameter_objects():
    from ripr import LogRatioParameters
    from ripr.actions import build_equivalent_script

    script = build_equivalent_script("estimate", {
        "input": "sample.tif", "parameters": LogRatioParameters.manual(),
    })
    compile(script, "<equivalent_script>", "exec")
    assert "parameters=parameters" in script


def test_codex_runner_bridge_emits_json_only():
    runner = Path(__file__).parents[1] / ".codex" / "skills" / "ripr" / "scripts" / "ripr_runner.py"
    completed = subprocess.run(
        [sys.executable, str(runner), "discover"],
        check=True,
        capture_output=True,
        text=True,
    )
    payload = json.loads(completed.stdout)
    assert payload["ok"] is True
    assert payload["result"]["missing"] == []


def test_runner_reports_bad_json_without_traceback():
    runner = Path(__file__).parents[1] / ".claude" / "skills" / "ripr" / "scripts" / "ripr_runner.py"
    completed = subprocess.run(
        [sys.executable, str(runner), "run"],
        input="{not json",
        check=False,
        capture_output=True,
        text=True,
    )
    payload = json.loads(completed.stdout)
    assert completed.returncode != 0
    assert payload["ok"] is False
    assert "JSON" in payload["error"]["type"]


def test_inspect_and_register_actions_use_throwaway_tiff(tmp_path):
    source = tmp_path / "source.tif"
    target = tmp_path / "registered.tif"
    tifffile.imwrite(source, np.random.default_rng(5).random((3, 64, 64)).astype("float32"))
    inspected = dispatch("inspect_tiff", {"path": str(source)})
    assert inspected["ok"] is True
    assert inspected["result"]["axes"] == "CYX"
    registered = dispatch("register", {
        "input": str(source), "output_path": str(target), "recipe": "landmarks",
        "channel": 1, "longitudinal": False, "backend": "python",
    })
    assert registered["ok"] is True
    assert target.exists()
    assert registered["result"]["output_path"] == str(target)
