# Sources

Everything this figure was measured from. The recording itself is not copied:
at 570 MB it is identified by SHA256 in
`data/src/recording_provenance.json` instead.

| File | What it is | Bytes | SHA256 |
|---|---|---:|---|
| `20260710_Tmem_Cry_BSL_leaktest_1713_Multichannel Time Lapse_20260721_1424.ome.tif` | the recording: 240 timepoints, 4 channels, 512 by 512 | 569,737,134 | `c4ab6179cf578e71...` |
| `ripr/core.py` | the engine module the steps were captured from | 108,936 | `dcf06060386f25b9...` |
| `ripr/types.py` | the engine module the steps were captured from | 10,157 | `4f4a6c839e243cb0...` |
| `ripr/preprocessing.py` | the engine module the steps were captured from | 4,120 | `f784a282b83b5327...` |
| `ripr/registration.py` | the engine module the steps were captured from | 18,533 | `bf907afe1d5a2226...` |
| `ripr/parameters.py` | the engine module the steps were captured from | 15,918 | `2b40451da456b19b...` |
| `ripr/diagnostics.py` | the engine module the steps were captured from | 2,855 | `d8b44b3bd7016ea1...` |
| `ripr/__init__.py` | the engine module the steps were captured from | 2,161 | `1c1680e2cef4cbde...` |
| `run_registration_1424.py` | registers the whole recording; produces the trajectory | 6,437 | `7956953f0bc9100b...` |
| `capture_steps.py` | captures every intermediate array, in execution order | 27,776 | `39915c16fff8d25f...` |
