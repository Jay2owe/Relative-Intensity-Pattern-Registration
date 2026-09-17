#!/usr/bin/env python3
"""Generate portable public context artifacts from ``ripr.context``."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))


def build_bundle() -> dict[str, Any]:
    """Return the complete, JSON-safe public context bundle."""
    import ripr
    from ripr import context

    return {
        "schema_version": 1,
        "package": "ripr",
        "distribution": "Relative-Intensity-Pattern-Registration",
        "version": ripr.__version__,
        "reader": {
            "purpose": "Read-only orientation for agents choosing a RIPR workflow.",
            "python": "from ripr import context; context.read(format='json')",
            "topic": "context.read(topic, format='json')",
            "search": "context.search(query)",
        },
        "topics": [
            context.read(topic, format="json")
            for topic in context.topics()
        ],
    }


def _fence(content: str) -> str:
    """Choose a Markdown fence longer than any backtick run in ``content``."""
    runs = [len(run) for run in re.findall(r"`+", content)]
    return "`" * max(3, (max(runs) + 1) if runs else 3)


def render_markdown(bundle: dict[str, Any]) -> str:
    """Render a standalone guide for agents that cannot import the package."""
    topics = bundle["topics"]
    lines = [
        "# Relative-Intensity Pattern Registration agent context",
        "",
        "This is the portable, read-only orientation guide for the Relative-Intensity "
        "Pattern Registration (RIPR) Python package.",
        "",
        f"Package version: `{bundle['version']}`",
        "",
        "Use this file when the agent cannot import the package or run its local "
        "command-line interface (CLI). The structured companion is "
        "[`ripr_context.json`](ripr_context.json).",
        "",
        "## Public context interface",
        "",
        "Python clients can read the same guidance with:",
        "",
        "```python",
        "from ripr import context",
        "context.read(format='json')",
        "context.read('simple_registration', format='json')",
        "context.search('backend fallback')",
        "```",
        "",
        "Non-CLI clients should use the topic records below or the structured JSON "
        "bundle. Read the relevant topic before choosing a workflow.",
        "",
        "## Topic index",
        "",
    ]
    for item in topics:
        lines.append(f"- `{item['topic']}` — {item['title']}")

    lines.extend(["", "## Topics", ""])
    for item in topics:
        lines.extend([
            f"### {item['title']}",
            "",
            f"Topic key: `{item['topic']}`",
            "",
        ])
        prerequisites = item.get("prerequisites", [])
        related = item.get("related_topics", [])
        if prerequisites:
            lines.extend([f"Prerequisites: {', '.join(f'`{value}`' for value in prerequisites)}", ""])
        if related:
            lines.extend([f"Related topics: {', '.join(f'`{value}`' for value in related)}", ""])
        content = str(item["content"])
        fence = _fence(content)
        lines.extend([f"{fence}text", content, fence, ""])

    lines.extend([
        "## Machine-readable metadata",
        "",
        "The JSON bundle contains the package version, schema version, and every "
        "structured topic record. It is read-only orientation data; it does not "
        "contain image data, user files, credentials, or execution results.",
        "",
    ])
    return "\n".join(lines)


def expected_outputs() -> dict[Path, str]:
    bundle = build_bundle()
    return {
        ROOT / "ripr_context.json": json.dumps(bundle, indent=2, ensure_ascii=False) + "\n",
        ROOT / "README_AI.md": render_markdown(bundle),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="fail if the tracked artifacts differ from the public context",
    )
    args = parser.parse_args(argv)

    outputs = expected_outputs()
    mismatches: list[Path] = []
    for path, content in outputs.items():
        if args.check:
            if not path.is_file() or path.read_text(encoding="utf-8") != content:
                mismatches.append(path)
        else:
            path.write_text(content, encoding="utf-8", newline="\n")

    if mismatches:
        for path in mismatches:
            print(f"stale or missing: {path.relative_to(ROOT)}", file=sys.stderr)
        return 1
    if args.check:
        print("agent context artifacts are current")
    else:
        for path in outputs:
            print(f"generated {path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
