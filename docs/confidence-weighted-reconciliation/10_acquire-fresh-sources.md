# Stage 10 - Acquire and freeze fresh sources

## Objective

Download twenty previously unused, openly licensed microscopy sources: ten validation and ten final, balanced two-per-category in each split.

## Rules

- Prefer repository-hosted TIFF/OME-TIFF data with stable record identifiers and explicit reuse terms.
- Use different original acquisitions for validation and final. Two fields from one physical acquisition count once.
- Record the publisher/repository page and direct file URL. Preserve downloaded bytes unchanged under the ignored benchmark library.
- Verify every SHA-256 after download and after Dropbox synchronization.
- Inspect only file readability, dimensions, channel layout and category suitability before freezing. Do not run registration or view candidate outcomes.

## Exit gate

Every frozen row opens through ImageJ, is at least 128 by 128 pixels after a declared centre crop, has a stable hash and belongs to one and only one split.
