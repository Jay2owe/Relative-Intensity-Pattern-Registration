"""Small public orientation guide for AI clients and automation tools.

This module intentionally contains only standard-library data and functions.
Reading context never reads image data, starts ImageJ, or starts a worker.
"""

from __future__ import annotations

import re
from typing import Any

from . import __version__


_TOPICS: dict[str, dict[str, Any]] = {
    "overview": {
        "title": "RIPR overview",
        "content": (
            "Relative-Intensity Pattern Registration (RIPR) registers microscopy time series while "
            "preserving intensity ratios. The normal workflow is choose a recipe, channel, and "
            "longitudinal mode, then call ripr.register. Java is the default backend; an unavailable "
            "Java backend emits a clear BackendFallbackWarning before Python is used."
        ),
        "prerequisites": [],
        "related_topics": ["simple_registration", "backend_troubleshooting"],
    },
    "simple_registration": {
        "title": "Simple registration",
        "content": (
            "Choose recipe (landmarks, bright_dim, or moving_cells), one-based channel, and "
            "longitudinal mode. Defaults are landmarks, channel 1, and longitudinal=True. A minimal "
            "array example is:\n\n"
            "import numpy as np\nfrom ripr import register\n"
            "stack = np.random.default_rng(4).random((3, 64, 64)).astype('float32')\n"
            "result = register(stack, recipe='landmarks', channel=1, longitudinal=True)\n"
            "corrected = result.corrected\n\n"
            "For a TIFF, pass its path and output_path='registered.tif'. The result contains corrected "
            "pixels, transforms, residual diagnostics, the resolved recipe, and axes. Use "
            "longitudinal=False for the automatic fixed-recipe route; moving_cells requires that mode."
        ),
        "prerequisites": ["overview"],
        "related_topics": ["advanced_settings", "folder_batches"],
    },
    "advanced_settings": {
        "title": "Advanced settings",
        "content": (
            "Expert LogRatioParameters fields can be passed as keywords to register or estimate, "
            "such as max_shift (pixels), interpolation, reference, estimator, norm, preprocessing, "
            "crop, rotation_mode, and max_iterations. Unknown names raise ValueError. These settings "
            "can make the request differ from the Java preset and therefore produce a visible "
            "BackendFallbackWarning. Use backend='java' to require the reference engine, or "
            "backend='python' to choose Python explicitly."
        ),
        "prerequisites": ["simple_registration"],
        "related_topics": ["backend_troubleshooting"],
    },
    "folder_batches": {
        "title": "Folder batches",
        "content": (
            "Call ripr.register_batch(input_directory, output_directory, recipe='landmarks', "
            "channel=1, longitudinal=True). TIFF files are isolated on failure; existing outputs are "
            "skipped unless overwrite=True. The action runner additionally requires explicit "
            "confirm_overwrite=true before overwriting. The returned BatchResult includes completed, "
            "skipped, and error counts plus log_ratio_batch_report.csv."
        ),
        "prerequisites": ["simple_registration"],
        "related_topics": ["backend_troubleshooting"],
    },
    "backend_troubleshooting": {
        "title": "Backend and troubleshooting",
        "content": (
            "Java is preferred by default and explicit backend='java' is strict. If Java is missing "
            "or cannot represent an advanced setting, the default/auto route emits "
            "BackendFallbackWarning and uses Python. Install/configure RIPR_JAVA and RIPR_JAR, or "
            "select backend='python' deliberately. Longitudinal maximum accuracy is defined by Java; "
            "Python is available explicitly with a compatibility warning. If a run appears slow, "
            "inspect the structured warnings and backend field rather than assuming Java ran."
        ),
        "prerequisites": ["simple_registration"],
        "related_topics": ["advanced_settings"],
    },
}


def _normalize_topic(topic: str) -> str:
    normalized = re.sub(r"[^a-z0-9]+", "_", topic.strip().lower()).strip("_")
    aliases = {
        "simple": "simple_registration",
        "advanced": "advanced_settings",
        "folder_batch": "folder_batches",
        "backend_and_troubleshooting": "backend_troubleshooting",
        "backend": "backend_troubleshooting",
    }
    return aliases.get(normalized, normalized)


def topics() -> tuple[str, ...]:
    """Return stable topic keys in their public order."""
    return tuple(_TOPICS)


def _structured(topic: str) -> dict[str, Any]:
    item = _TOPICS[topic]
    return {
        "ok": True,
        "version": __version__,
        "topic": topic,
        "title": item["title"],
        "content": item["content"],
        "prerequisites": list(item["prerequisites"]),
        "related_topics": list(item["related_topics"]),
    }


def read(topic: str | None = None, *, format: str = "text") -> Any:
    """Read one orientation topic as text or a structured JSON-safe object."""
    if format not in {"text", "json"}:
        return {"ok": False, "version": __version__, "error": "format must be 'text' or 'json'"}
    if topic is None:
        if format == "json":
            return {
                "ok": True,
                "version": __version__,
                "topic": None,
                "title": "Available RIPR context",
                "content": _TOPICS["overview"]["content"],
                "topics": list(_TOPICS),
            }
        return "Available topics: " + ", ".join(_TOPICS)
    if not isinstance(topic, str) or not topic.strip():
        message = "topic must be a non-empty string"
        return {"ok": False, "error": message} if format == "json" else message
    chosen = _normalize_topic(topic)
    if chosen not in _TOPICS:
        message = f"Unknown context topic {topic!r}. Available topics: {', '.join(_TOPICS)}"
        if format == "json":
            return {
                "ok": False,
                "version": __version__,
                "error": message,
                "suggestions": search(topic)["results"][:3],
                "available_topics": list(_TOPICS),
            }
        return message
    structured = _structured(chosen)
    return structured if format == "json" else structured["content"]


def search(query: str, limit: int = 5) -> dict[str, Any]:
    """Find orientation topics and return structured relevance snippets."""
    if not isinstance(query, str) or not query.strip():
        return {"ok": True, "results": []}
    if not isinstance(limit, int) or isinstance(limit, bool) or limit < 1:
        return {"ok": False, "error": "limit must be a positive integer", "results": []}
    terms = {term for term in re.findall(r"[a-z0-9]+", query.lower()) if len(term) > 1}
    scored: list[tuple[int, str]] = []
    for topic, item in _TOPICS.items():
        haystack = f"{topic} {item['title']} {item['content']}".lower()
        score = sum(haystack.count(term) for term in terms)
        if score:
            scored.append((score, topic))
    scored.sort(key=lambda item: (-item[0], list(_TOPICS).index(item[1])))
    return {
        "ok": True,
        "results": [
            {
                "topic": topic,
                "title": _TOPICS[topic]["title"],
                "score": score,
                "description": _TOPICS[topic]["content"].split("\n", 1)[0],
            }
            for score, topic in scored[:limit]
        ],
    }
