# Relative-Intensity Pattern Registration agent context

This is the portable, read-only orientation guide for the Relative-Intensity Pattern Registration (RIPR) Python package.

Package version: `0.2.3`

Use this file when the agent cannot import the package or run its local command-line interface (CLI). The structured companion is [`ripr_context.json`](ripr_context.json).

## Public context interface

Python clients can read the same guidance with:

```python
from ripr import context
context.read(format='json')
context.read('simple_registration', format='json')
context.search('backend fallback')
```

Non-CLI clients should use the topic records below or the structured JSON bundle. Read the relevant topic before choosing a workflow.

## Topic index

- `overview` — RIPR overview
- `simple_registration` — Simple registration
- `advanced_settings` — Advanced settings
- `folder_batches` — Folder batches
- `backend_troubleshooting` — Backend and troubleshooting

## Topics

### RIPR overview

Topic key: `overview`

Related topics: `simple_registration`, `backend_troubleshooting`

```text
Relative-Intensity Pattern Registration (RIPR) registers microscopy time series while preserving intensity ratios. The normal workflow is choose a recipe, channel, and longitudinal mode, then call ripr.register. Java is the default backend; an unavailable Java backend emits a clear BackendFallbackWarning before Python is used.
```

### Simple registration

Topic key: `simple_registration`

Prerequisites: `overview`

Related topics: `advanced_settings`, `folder_batches`

```text
Choose recipe (landmarks, bright_dim, or moving_cells), one-based channel, and longitudinal mode. Defaults are landmarks, channel 1, and longitudinal=True. A minimal array example is:

import numpy as np
from ripr import register
stack = np.random.default_rng(4).random((3, 64, 64)).astype('float32')
result = register(stack, recipe='landmarks', channel=1, longitudinal=True)
corrected = result.corrected

For a TIFF, pass its path and output_path='registered.tif'. The result contains corrected pixels, transforms, residual diagnostics, the resolved recipe, and axes. Use longitudinal=False for the automatic fixed-recipe route; moving_cells requires that mode.
```

### Advanced settings

Topic key: `advanced_settings`

Prerequisites: `simple_registration`

Related topics: `backend_troubleshooting`

```text
Expert LogRatioParameters fields can be passed as keywords to register or estimate, such as max_shift (pixels), interpolation, reference, estimator, norm, preprocessing, crop, rotation_mode, and max_iterations. Unknown names raise ValueError. These settings can make the request differ from the Java preset and therefore produce a visible BackendFallbackWarning. Use backend='java' to require the reference engine, or backend='python' to choose Python explicitly.
```

### Folder batches

Topic key: `folder_batches`

Prerequisites: `simple_registration`

Related topics: `backend_troubleshooting`

```text
Call ripr.register_batch(input_directory, output_directory, recipe='landmarks', channel=1, longitudinal=True). TIFF files are isolated on failure; existing outputs are skipped unless overwrite=True. The action runner additionally requires explicit confirm_overwrite=true before overwriting. The returned BatchResult includes completed, skipped, and error counts plus log_ratio_batch_report.csv.
```

### Backend and troubleshooting

Topic key: `backend_troubleshooting`

Prerequisites: `simple_registration`

Related topics: `advanced_settings`

```text
Java is preferred by default and explicit backend='java' is strict. If Java is missing or cannot represent an advanced setting, the default/auto route emits BackendFallbackWarning and uses Python. Install/configure RIPR_JAVA and RIPR_JAR, or select backend='python' deliberately. Longitudinal maximum accuracy is defined by Java; Python is available explicitly with a compatibility warning. If a run appears slow, inspect the structured warnings and backend field rather than assuming Java ran.
```

## Machine-readable metadata

The JSON bundle contains the package version, schema version, and every structured topic record. It is read-only orientation data; it does not contain image data, user files, credentials, or execution results.
