# Publishing Audit — Log-Ratio Registration

Generated: 2026-08-21T21:59:50+01:00  
Project folder: `C:\Users\Owner\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Experiments\Log-Ratio Registration`  
Detected type: Fiji/ImageJ plugin; Java/Maven project; generic GitHub release  
Git remote: `https://github.com/Jay2owe/Log-Ratio-Registration.git`  
Current checkout version: `0.1.0-SNAPSHOT`  
Prepared local release version: `0.1.0` on branch `public-main`  
Intended release target: GitHub Release first; Fiji update site later

This is the requested general audit. Because the project is a Fiji/ImageJ plugin, run
`plugin-publish-audit` before update-site or ImageJ wiki publication.

## Summary

| Area | Status | Blocking issues |
|------|--------|------------------|
| 01 Release Intent And Scope | PARTIAL | A clean local `public-main` candidate exists, but remote `main` is still the initial private/minimal commit and the working tree contains active research changes. |
| 02 Metadata And Naming | FAIL | The live checkout is a snapshot and its POM uses stale non-hyphenated GitHub URLs; release metadata exists only on `public-main`. |
| 03 Repository Hygiene | FAIL | Live `main` tracks 47 documentation files, 99 test files, 15 local scripts, handoffs, previews and 12 files containing private paths. |
| 04 Documentation | FAIL | The live README has strong usage/API material but no installation, licence, citation, support or release instructions, and still presents sparse/low-light benchmark claims outside the new four-class scope. |
| 05 License, IP, And Attribution | FAIL | No root `LICENSE` or `CITATION.cff` exists on live `main`; publication permission from the institution cannot be proven from the repository. |
| 06 Build, Tests, And Installability | PARTIAL | Live tests pass 329/329 and packaging succeeds, but `mvn` is not on PATH, no wrapper is present on live `main`, and the output is a snapshot. |
| 07 Artifact Audit | FAIL | The runtime JAR is clean of tests/private paths but is `0.1.0-SNAPSHOT` and lacks embedded licence and citation metadata. |
| 08 Registry And Release Surface | FAIL | Anonymous GitHub and ImageJ update-site URLs both return HTTP 404; remote `main` is commit `95f7157` and no tag is published. |
| 09 Post-Release Verification Plan | PASS | Exact anonymous-clone, build, Fiji smoke, metadata and rollback checks are defined below. |

**Overall publish-readiness**: do not publish the live `main` checkout. The local `public-main`
candidate is the correct release base, but it must be reconciled with the current code/results,
re-audited under the strict public-surface rule, and then published and smoke-tested.

## Detailed Findings

### 01 Release Intent And Scope

- [PARTIAL] The POM and remote identify GitHub as the source and issue surface; the plugin manifest
  declares two Fiji menu entries.
- [PASS] Local branch `public-main` is a one-commit release candidate at
  `d76af928e4a504d587928499176f8989baf33a5f`, with local tag `v0.1.0` and 98 files.
- [FAIL] Remote `main` remains `95f715747e44de9775c83297f60568f7af497ea2`; the local release commit and
  tag are not on the remote.
- [FAIL] The active checkout has nine modified tracked files and numerous untracked benchmark-version-3,
  bug-log and audit files. These are research work, not a release source.
- [PARTIAL] The intended release version appears to be `0.1.0`, but the active checkout still builds
  `0.1.0-SNAPSHOT`.

### 02 Metadata And Naming

- [PASS] The live POM has group ID, artifact ID, name, description, organisation, developer, licence
  declaration, source-control metadata, issue management and a single runtime dependency.
- [FAIL] Live POM URLs use `Jay2owe/LogRatioRegistration`, while the configured remote is
  `Jay2owe/Log-Ratio-Registration`.
- [FAIL] The current artifact and manifest identify `0.1.0-SNAPSHOT`; release assets must not use this
  version.
- [PASS] `public-main` contains consistent version `0.1.0`, hyphenated repository URLs, a Maven wrapper,
  changelog, citation metadata and release build metadata.
- [PARTIAL] The local `v0.1.0` tag points to `public-main`, but is absent from `git ls-remote`.

### 03 Repository Hygiene

- [FAIL] Live `main` tracks 212 files: 47 under `docs/`, 99 under `src/test/`, 15 under `scripts/`,
  and six generated preview images.
- [FAIL] Twelve tracked files contain private absolute paths. These include three handoff documents,
  benchmark scripts, `scripts/preview-cp.txt` and test-side research utilities.
- [FAIL] Three tracked handoff documents are agent/context material and must not enter the public branch.
- [PASS] No tracked raw microscopy formats were found.
- [PASS] The filename-only secret scan found no plausible credential-bearing tracked file.
- [PARTIAL] `public-main` contains no private-path matches and has the intended legal/build files, but
  still includes 37 tests and one README screenshot. Under the strict minimal-surface rule these require
  explicit maintainer opt-in or removal.
- [FAIL] `PUBLISHING_AUDIT.md` and `docs/publication/` are not ignored; neither should be committed by
  accident.

### 04 Documentation

- [PASS] The live README explains interactive registration, batch operation, parameter sweeps, ImageJ
  macros, the no-dialog Java API and Maven commands.
- [FAIL] It does not provide release-user installation instructions, supported Fiji/Java versions,
  licence text/link, citation, acknowledgements, issue/support route or current release download URL.
- [FAIL] It still states sparse/low-light performance and recommendation claims even though the active
  external benchmark was narrowed to four classes. Public wording must match the approved claim and its
  post-result exclusion qualifier.
- [FAIL] Documented `mvn test` and `mvn package` fail in a fresh shell because Maven is not on PATH and
  the live branch has no wrapper. `public-main` fixes this with `mvnw.cmd`.
- [PASS] No TODO/FIXME marker was found in README, POM or runtime source.

### 05 License, IP, And Attribution

- [FAIL] The live checkout has no root `LICENSE` and no `CITATION.cff`, although its POM declares the
  BSD 3-Clause licence.
- [FAIL] The live runtime JAR does not embed `META-INF/LICENSE` or `META-INF/CITATION.cff`.
- [PASS] Both files and embedded equivalents are designed into `public-main`.
- [UNKNOWN] Institutional permission to publish the code, benchmark data and paper cannot be proven from
  repository contents; maintainer/PI confirmation is required.
- [PARTIAL] Developer, organisation and ORCID metadata are present, but the public README still needs
  lab, institution and funder acknowledgement.

### 06 Build, Tests, And Installability

- [PASS] Apache Maven 3.9.9 with OpenJDK 21 completed the live test suite on 2026-08-21:
  329 tests, zero failures, zero errors, zero skipped.
- [PASS] `mvn -DskipTests package` completed and produced the runtime, source and tests JARs.
- [PASS] Compiled runtime bytecode is Java 8 compatible (class major version 52).
- [PARTIAL] The build requires a Maven executable outside the repository on live `main`; use the wrapper
  already present on `public-main` for a fresh-clone proof.
- [PARTIAL] A physical clean-Fiji installation smoke test was not run in this audit.

### 07 Artifact Audit

- [PASS] `target/LogRatioRegistration-0.1.0-SNAPSHOT.jar` contains 142 entries and 131 classes, includes
  `plugins.config`, and contains no tests, sources, benchmark files, private paths or plausible secrets.
- [PASS] Artifact SHA-256 is
  `51F8EAE62B14A6B20601E5E7043AA20C4F07BE9F51A791BE3050255BB768A35C`.
- [FAIL] Manifest and Maven metadata identify a snapshot; `Implementation-Build` is blank and the build
  date is not a fixed release timestamp.
- [FAIL] Licence and citation metadata are absent from the runtime JAR.
- [PARTIAL] The tests JAR is produced automatically and must not be attached to the default public release.

### 08 Registry And Release Surface

- [FAIL] Anonymous request to `https://github.com/Jay2owe/Log-Ratio-Registration` returned HTTP 404 on
  2026-08-21. Do not claim the repository is public.
- [FAIL] `git ls-remote` shows only remote `main` at the initial commit and no remote tag.
- [FAIL] `https://sites.imagej.net/Log-Ratio-Registration/db.xml.gz` returned HTTP 404 on 2026-08-21.
- [UNKNOWN] No public GitHub Release, Actions run, Zenodo archive, journal submission or bioRxiv record
  can be verified.
- [PARTIAL] Local `public-main` contains GitHub Actions and release metadata, but external publication
  and anonymous verification remain undone.

### 09 Post-Release Verification Plan

- [PASS] After publishing, open `https://github.com/Jay2owe/Log-Ratio-Registration` anonymously and
  verify README, licence, citation, Issues, Actions, tag and release assets.
- [PASS] Fresh-clone proof:
  `git clone https://github.com/Jay2owe/Log-Ratio-Registration.git LogRatioRegistration-clean`, then
  `mvnw.cmd -q test` and `mvnw.cmd -q package`.
- [PASS] Check `target/LogRatioRegistration-0.1.0.jar`, run `jar tf`, inspect its manifest, and compare
  the published SHA-256 checksum.
- [PASS] Install only the runtime JAR into a disposable fresh Fiji, restart Fiji, verify both menu entries,
  run one interactive synthetic stack and one two-file batch, then exercise the documented macro.
- [PASS] If a release asset is wrong after publication, remove/withdraw the release and issue a new patch
  version; do not replace an immutable version silently.

## Prioritized Next-Step Plan

### Immediate Release Blockers

1. Reconcile the current runtime source and approved four-class benchmark wording into local
   `public-main`; do not release from dirty research `main`.
2. Decide whether the 37 curated tests and one screenshot are intentionally public. Remove them if the
   strict minimal source surface is preferred.
3. Rebuild the release candidate with its wrapper, rerun the full suite, audit the non-snapshot JAR and
   perform a fresh-Fiji physical smoke test.
4. Rerun `push-guard`, publish `public-main` and tag `v0.1.0`, then verify the repository and release
   anonymously.

### Before Publishing The Paper

5. Align the public README and manuscript with the four-class claim: 32 locked recordings, zero tuned
   failures, 0.0211 px versus 0.0507 px overall, and the disclosed sparse/low-light exclusion.
6. Deposit or provide a reproducible public benchmark subset, parameter freeze, comparator versions,
   analysis script and exact table used by the claim.
7. Confirm authorship, institutional publication permission, data licences, funding acknowledgements
   and the target preprint/journal route.

### After Publishing

8. Run the anonymous clone/build/install checks above and archive their hashes and outputs.
9. Run `plugin-publish-audit` before provisioning an ImageJ update site or submitting its wiki/listing.
10. Create a versioned archive/DOI only after the public release is stable and cite the exact software
    version used for the paper.

## Confirmations Needed From Maintainer

- [ ] Confirm `public-main` is the branch to reconcile and publish, rather than publishing research `main`.
- [ ] Confirm whether curated tests and the GUI screenshot are intentionally part of the public source.
- [ ] Confirm release version `0.1.0` remains correct after incorporating the four-class result.
- [ ] Confirm institutional/PI permission and the required lab/funder acknowledgement wording.
- [ ] Confirm whether `docs/publication/` should remain local-only via `.gitignore`.

## Recommended Skills / Commands

- Use `push-guard` before any public Git action.
- Use `force-public` only after approving the exact clean branch and remote replacement.
- Use `plugin-publish-audit` for the full Fiji/ImageJ publication pathway.
- Use `imagej-update-site-release` only after the update site exists and the plugin audit passes.
