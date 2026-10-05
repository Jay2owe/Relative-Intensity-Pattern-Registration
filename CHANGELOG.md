# Changes

## 0.3.0 — 2026-10-05

- Publish the accepted explicit longitudinal Java builds: Bright/dim A001 phase-seed rescue, Moving cells A004 guarded Bright/dim fallback, and the A013 Bright/dim execution policy (at most 16 available hardware workers).
- Add the `RIPR Longitudinal (accepted recipes)...` Fiji command, the `ripr.register_accepted` Python helper, and explicit accepted selection modes available through the existing API/action registry.
- Keep existing Automatic and legacy longitudinal behavior unchanged. No longitudinal automatic router is enabled.
- Preserve accepted scientific bytecode and native registration binaries in checksum-verified, isolated resources rather than substituting later experimental recompilations. The accepted native routes require 64-bit Windows, Java 25 or newer, and the separately installed Microsoft Visual C++ runtime. Proprietary Microsoft DLLs are omitted from the bundle. Other routes retain their existing platform requirements.
- Report executed recipe IDs, actual recovery use and weak-evidence warnings. Unexported pair diagnostics remain unavailable, not fabricated accuracy scores.
- Bundle the Java plugin and ImageJ runtime in Python distributions.
- Make existing public tests treat unset NaN parameters semantically and skip personal agent-bridge tests when their intentionally excluded local files are absent.

This release does not establish geometric ground truth for the real recordings or guarantee unattended correctness. Synthetic failures and real-data exclusions remain part of the evidence; no rejected tuning candidate is promoted.
