# A movie of the log-ratio fit, made out of the fit

`06_knock_log_ratio_fit.mp4` is about a minute of the pairwise estimator
measuring a real knock, in seven acts. Nothing in it is drawn a second time.
The engine is imported and called exactly as `ValidationRun` calls it, and a
return tracer copies its own local variables out as each function returns, so
every mask on screen is an array the code held and every number in a caption
was computed by the method rather than typed here.

    python docs/figures/method-movie/make_method_movie.py

Rebuilding takes about seven minutes, nearly all of it the whole-recording run.
`--entry` and `--pair` point it at a different library entry and a different
frame pair; the pair has to be one the plan actually measures, and the script
refuses if it is not.

## What it claims, and what checks the claim

Two gates run before a frame is drawn, both inside the build script:

- The whole-recording trace is compared against the `shifts.csv` this library
  entry ships, which the Java plugin wrote. Different implementation, so a
  tolerance rather than equality — it agreed to **0.000293 px** — and the
  measured figure goes on the closing card.
- The pair the movie follows in depth is compared against the same pair inside
  the whole-recording run, and must be identical to the last bit. Otherwise the
  movie would be splicing two different runs together.

## The subject

`library/06_knock` (VID52_B6_1, phase contrast, 48 frames half an hour apart).
Something knocked the stage between frames 46 and 47, moving it 35.1 px in one
step. The movie follows that pair through the log-ratio field, the contrast
threshold, the four-level pyramid, the 176-candidate coarse search and the
robust solve, and then widens out to the 209 pair fits and the reconciliation.

The last act is the one that is worth knowing about. Nine pairs straddle the
knock, each fitted on its own, and all nine measure it: −33.4 to −35.6 px. The
outlier guard in `_repair` compares step sizes against the recording's own
median step of 1.42 px, arrives at a limit of 10.4 px, and refuses the 35.2 px
step — so frame 47's position is replaced by the midpoint of its neighbours.
The guard sees step sizes, not the pair fits behind them. That is the shipped
behaviour, and it is now something anybody can watch happen.

## Files

| | |
|---|---|
| `make_method_movie.py` | the build: watch, gate, storyboard, render, record |
| `frames.py` | drawing only, vendored from the method-movie skill; the package does not import it |
| `06_knock_log_ratio_fit.mp4` | the movie |
| `06_knock_log_ratio_fit.json` | input hash, the call, the parity figures, encoder settings |

Display only. Nothing here is used by the package, the tests or the benchmark.
