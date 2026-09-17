# Contributing

Bug reports and focused pull requests are welcome.

## Reporting problems

Open a [GitHub issue](https://github.com/Jay2owe/Log-Ratio-Registration/issues) and include:

- Log-Ratio Registration, Fiji/ImageJ, operating-system, and Java versions;
- the input image dimensions, channels, Z slices, and timepoints;
- the complete settings/provenance line from the ImageJ log;
- the expected and observed behaviour; and
- a minimal shareable test image when its licence and data-governance terms permit sharing.

Do not upload patient-identifiable, confidential, or otherwise restricted microscopy data.

## Development

Create a branch from `main`, make a focused change, add or update tests, and run:

```sh
./mvnw clean verify -Denforcer.skip=true
```

Pull requests should explain the scientific or user-facing reason for the change and note any output or API compatibility impact.
