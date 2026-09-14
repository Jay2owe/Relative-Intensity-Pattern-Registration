# Single-channel fluorescence rescue: Round 3

Automatic uses the declared image and motion types only. Dense and low-light fluorescence now compare
every frame with image 1 using Enhanced Correlation Coefficient, without filtering or outlier repair.
The Image-and-motion preset is unchanged. Registration reads one channel only.

The fixed route completed all 24 real 40-frame stacks. It exactly reproduced the accepted candidate
transforms, passed 0.75 and 1.50 resize checks and an added brightness-wave check, and had no failures or
cross-channel reads. Median runtime was 2.96 seconds per stack.

The tissue-outline number is a screening guide, not ground truth. It fell from a 260-pixel worst case
for the previously benchmarked Automatic route to 50 pixels for this route. Final judgment remains the
scrolling ImageJ comparison TIFFs because biological intensity waves can mislead any image-only score.

The remaining flagged case is `mf_a2`; it stays in the review set for the next tuning round.
