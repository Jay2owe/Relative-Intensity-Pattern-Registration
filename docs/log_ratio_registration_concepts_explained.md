# How Relative-Intensity Pattern Registration Works

This is the detailed companion to [the plain-language explanation](log_ratio_registration_simple_explanation.md). Both use the same **19 steps** and three recipe names: **Moving cells**, **Bright/dim** and **Landmarks**. Equations describe the calculations; the linked source fixes rounding, tie order and failure handling.

## For a methods section

*Manuscript starting text for the experimental three-route Java workflow, not the installed plugin's older longitudinal option. Supply the actual input manifest, recipe assignments, motion declaration, source revision, embedded engine, native-library version and exporter. Retain only the selection option actually used. The Configuration tables form the accompanying reproducibility specification; this paragraph is not a complete standalone protocol. It contains no measured outcomes.*

Longitudinal microscope recordings contained whole-field displacement together with changing brightness, local intensity pulses and independently changing biological structures. Each channel was processed separately as a two-dimensional time series using Relative-Intensity Pattern Registration. A recipe was explicitly assigned or selected by the frozen same-channel example-based detector, which refused uncertain assignments. The Moving cells recipe used the checksum-pinned original image-and-motion Recommended implementation: unfiltered native-scale images were transformed as log2 of intensity plus one; frame pairs separated by 1, 2, 4, 8 and 16 intervals were matched using median-centred log differences, noise-qualified gradients in both images and Tukey weighting (Beaton and Tukey, 1974); and translations were refined by iteratively reweighted gradient fitting (Lucas and Kanade, 1981; Holland and Welsch, 1977). Pair translations were reconciled with equal weights and the first frame fixed, using redundant temporal constraints in the spirit of Wang et al. (2014); unsupported positions were interpolated without large-step rejection. The two tissue recipes first obtained the declared image-and-motion fixed-recipe preliminary alignment. Bright/dim used percentile-normalised, high-pass features, selected contrast-qualified bright and dim reference frames, and fitted both reference paths using enhanced correlation coefficient maximisation with in-plane rigid transforms (Evangelidis and Psarakis, 2008), initialised from phase correlation, the preliminary alignment and identity (Guizar-Sicairos et al., 2008). The better reference path was selected per frame. Landmarks combined tissue edges and dark structures at several spatial scales, built a masked median reference after preliminary alignment, and fitted translations by normalised area correlation with cubic B-spline sampling and enhanced correlation coefficient refinement (Unser et al., 1993; Evangelidis and Psarakis, 2008). Both tissue routes applied the fixed confidence, persistent-jump, returning-excursion and route-specific exceptional-jump rules specified below. Final transforms were applied once to original measurement images with the specified native-output interpolation; residual-displacement scores were retained only as review guides.

## Overview

Think of aligning transparent photographs while someone turns a dimmer switch and some objects move independently. The slice can move, illumination can change, and cells can genuinely move; these are not interchangeable explanations for a changed pixel. Registration needs evidence of common field movement without treating every biological change as an error.

Moving cells retains the original robust log-ratio Recommended recipe as its final estimator. Bright/dim and Landmarks are separate tissue recipes with preliminary fitting, different same-channel references and additional movement checks. Bright/dim covers fluorescence **and bioluminescence**; Landmarks covers brightfield and phase contrast. Moving cells describes the visible structures, not their imaging modality.

The experimental three-route entry point is separate from the main plugin. The existing “Automatic fixed recipe” translation path looks up settings from declared image and motion types; it does not discover the image type. The experimental video detector does inspect image content. The main plugin's older “Longitudinal maximum accuracy” path is another implementation again.

| Operation | Moving cells | Bright/dim | Landmarks |
|---|---|---|---|
| Isolated original Recommended engine | Final estimator | No | No |
| Fixed-recipe preliminary alignment | No separate preliminary stage | Yes; matcher depends on subtype | Yes; matcher depends on subtype |
| Shared-noise edges and Tukey log-ratio fit | Yes | Only if preliminary settings select them | Only if preliminary settings select them |
| Two actual bright/dim references | No | Yes | No |
| Masked median edge/dark reference | No | No | Yes |
| OpenCV enhanced correlation coefficient fitting | No | Main reference fit | No |
| Own area-correlation / enhanced correlation coefficient update | No | Possible preliminary matcher | Main reference fit |
| Confidence-based tissue movement cleanup | No | Yes | Yes |
| Bright-tissue centre endpoint check | No | Yes | No |
| Edge/dark features inside exceptional rigid-jump checks | No | Terminal-chain check | Throughout-recording check |
| Original-pixel export and labelled review | Yes | Yes | Yes |

## The analysis in order

1. Choose the guide image.
2. Choose the recipe.
3. Prepare temporary working copies.
4. Choose which frame pairs to compare.
5. Search from coarse to fine.
6. Separate overall brightness change from movement.
7. Give reliable, agreeing pixels more influence.
8. Use correlation instead where the recipe calls for it.
9. Optionally select informative regions and refit.
10. Combine pair movements and fill unsupported positions.
11. Build tissue features.
12. Build or choose tissue references.
13. Match tissue frames to their references.
14. Check for weak fits and returning excursions.
15. Check exceptional jumps.
16. Keep fit diagnostics separate from accuracy.
17. Move the original images.
18. Handle empty borders.
19. Make the review grid and its guide score.

Steps 6–7 and step 8 are alternative pair matchers. Step 9 wraps a pilot fit and a masked refit when enabled; Moving cells does not enable it. Moving cells skips steps 11–15. Those steps contain mutually exclusive Bright/dim and Landmarks branches. Independent frame preparation and pair fits can run concurrently. Step 19 is downstream review, never an input to selection.

## Step 1 - Choose the guide image

**All recipes.** The experimental entry accepts one channel as a two-dimensional time stack with at least two images. It rejects multiple channels and simultaneous depth-and-time dimensions. A plain ImageJ stack is treated as time. No other channel is substituted when an image becomes dim.

The general plugin adapter additionally permits an explicitly selected depth plane or a maximum projection through depth at each time. That is a depth operation within one channel. Fresh estimation copies leave the original measurement images available for output.

**In words:** each timepoint contributes the declared channel's image; only the general depth-projection option takes a maximum through depth.

~~~text
(1) E_t(x) = X_(t,c*,z*)(x)
    General depth-projection option only: E_t(x) = max_z X_(t,c*,z)(x)
~~~

$$
E_t(\mathbf x)=X_{t,c_*,z_*}(\mathbf x);
\qquad E_t(\mathbf x)=\max_z X_{t,c_*,z}(\mathbf x)
\quad\text{only for depth projection}.
\tag{1}
$$

*In the engine:* [StackFrames](../src/main/java/ripr/StackFrames.java), of/plane; [LongitudinalVideoRouting](../src/experimental/java/ripr/core/LongitudinalVideoRouting.java), checkImage.

## Step 2 - Choose the recipe

**All recipes; content arithmetic only for experimental automatic selection.** Direct selection bypasses the detector. Moving cells dispatches immediately to its isolated historical engine. Bright/dim and Landmarks dispatch to the tissue implementation, with a check on the declared image subtype. The caller still declares motion; biological execution pins its historical image-and-jumps recipe internally.

The detector measures up to nine evenly spaced reduced images. It collects 23 values, but the retained distance model uses only four spatial descriptions: middle intensity position, neighbour correlation, fine-detail energy share and positive-detail energy share. The four collected temporal diagnostics do not choose the recipe. Spatial similarity therefore does not prove biological cell movement.

Distances are scaled by training-feature interquartile spans. Examples from the same recording count as one group, and the score averages the three nearest groups. A choice also requires usable image evidence, at least three groups, a competing-recipe distance at least 1.3 times the best distance and features inside the chosen class's ranges expanded by 10%. Otherwise selection refuses. These exact gates are project-defined.

**In words:** compare the image with stored examples, count independent recordings rather than duplicate channels, and refuse an unsupported choice.

~~~text
(2) d_i = sqrt(mean_(j=1..4) [((f_j-e_ij)/s_j)^2])
    d_(k,g) = min_(i in recipe k, recording g) d_i
    S_k = mean(three smallest distinct-recording distances for recipe k)
    chosen = argmin_k S_k, only if confidence gates pass
~~~

$$
d_i=\sqrt{\frac14\sum_{j=1}^{4}\left(\frac{f_j-e_{ij}}{s_j}\right)^2},
\quad d_{k,g}=\min_{i\in(k,g)}d_i,\quad
S_k=\operatorname{mean}(\operatorname{smallest}_3\{d_{k,g}\}_g),
\quad k_*=\arg\min_k S_k\quad\text{subject to confidence gates}.
\tag{2}
$$

Classes with fewer than three groups can receive a provisional score but cannot pass. The main plugin's fixed translation selector does **not** run equation 2.

*In the engine:* [LongitudinalVideoDetector](../src/experimental/java/ripr/api/LongitudinalVideoDetector.java), measure/detect/Model; [LongitudinalVideoRouting](../src/experimental/java/ripr/core/LongitudinalVideoRouting.java), estimate/estimateAutomatic.

## Step 3 - Prepare temporary working copies

**All recipes, with distinct settings.** Moving cells uses native-scale unfiltered images, no percentile exclusions, no additive-background subtraction and a log offset of one. Tissue preliminary fitting instead resolves its declared fixed recipe. Dense emission currently selects median-filtered correlation; sparse emission retains its image-and-motion log-ratio preset. Other subtypes use their fixed rule.

These transformations affect estimation copies, not the measurement images. A measured zero remains valid with the positive offset. Nonfinite samples and values outside configured valid bands are excluded, not converted into false structure.

**In words:** prepare only the estimation copy and convert valid intensities to the representation the selected matcher uses.

~~~text
(3) P_t = resize_s(F_preset(E_t))
    L_t(x) = log2(P_t(x)-background_t+epsilon), on valid pixels
~~~

$$
P_t=\operatorname{resize}_{s_e}(F_{\mathrm{preset}}(E_t)),
\qquad L_t(\mathbf x)=\log_2(P_t(\mathbf x)-b_t+\epsilon)
\quad\text{on valid pixels}.
\tag{3}
$$

Moving cells has identity preparation, scale one, background subtraction zero and offset one. Log-plane storage does not imply a log-ratio objective: area correlation reconstructs linear values.

*In the engine:* [RelativeIntensityPatternRegistration](../src/main/java/ripr/api/RelativeIntensityPatternRegistration.java), resolveAutomaticSettings/estimateResolvedAtScale; [LogPlane](../src/main/java/ripr/core/LogPlane.java), of; [BiologicalRecommendedRegistration](../src/experimental/java/ripr/core/BiologicalRecommendedRegistration.java), estimate.

## Step 4 - Choose which frame pairs to compare

**Moving cells and tissue preliminary fitting.** The pair plan precedes the bound survey and full fitting. Moving cells compares every available pair at separations 1, 2, 4, 8 and 16 frames. Longer comparisons add constraints beyond a chain of neighbouring estimates. Redundant temporal constraints are established drift-correction practice (Wang et al., 2014); this project's pair matcher is not necessarily their cross-correlation matcher.

Consecutive, fixed-reference and rolling-template plans are alternatives, not additional passes on Moving cells. The current dense-emission preliminary recipe is consecutive. Tissue reference pairs are created later.

**In words:** compare every available pair at the frame separations selected by the recipe.

~~~text
(4) Edges={(t-lag,t): lag in Lags, lag<=t<T}
    Moving cells Lags={1,2,4,8,16}; consecutive Lags={1}
~~~

$$
\mathcal E=\{(t-\lambda,t):\lambda\in\Lambda,\lambda\le t<T\},
\quad\Lambda_{\mathrm{cells}}=\{1,2,4,8,16\},
\quad\Lambda_{\mathrm{consecutive}}=\{1\}.
\tag{4}
$$

*In the engine:* [Reconciler](../src/main/java/ripr/core/Reconciler.java), planPairs; [Registration](../src/main/java/ripr/core/Registration.java), pair planning; [AutomaticEmissionPolicy](../src/main/java/ripr/api/AutomaticEmissionPolicy.java), dense-emission policy.

## Step 5 - Search from coarse to fine

**Moving cells and tissue preliminary fitting.** A coarse survey evaluates the planned pairs and pads the largest usable displacement. Full fitting searches blurred, reduced images before refining at larger sizes. This uses the established multiscale principle (Burt and Adelson, 1983); the bound padding is project-defined.

For log-ratio pyramids, valid-weight-renormalised binomial smoothing precedes twofold reduction. Ordinary area correlation and its enhanced correlation coefficient refinement also use log-domain pyramids, reconstructing linear values at each level for scoring. Only the separate linear-pyramid correlation comparison option reduces intensities in the linear domain. Invalid borders stay invalid rather than becoming artificial landmarks.

**In words:** search beyond the coarse observed movement, then refine on progressively finer images.

~~~text
(5) M=max(M0,1.5*dmax+coarse_pixel_width)
    L_(level+1)=downsample2(valid_blur(L_level))
    blur kernel on each axis=[1,4,6,4,1]/16
~~~

$$
M=\max(M_0,1.5d_{\max}+h_c),
\quad L_{\ell+1}=\downarrow_2(B_{\mathrm{valid}}*L_\ell),
\quad B_{\mathrm{1D}}=[1,4,6,4,1]/16.
\tag{5}
$$

Moving cells uses a 30-pixel minimum bound. Survey and full-fit pyramid limits differ; the landmark reference search also has its own window.

*In the engine:* [Registration](../src/main/java/ripr/core/Registration.java), estimateShiftBound; [LogPlane](../src/main/java/ripr/core/LogPlane.java), pyramid; [PairAligner](../src/main/java/ripr/core/PairAligner.java), Options.levelsFor.

## Step 6 - Separate overall brightness change from movement

**Moving cells and any log-ratio preliminary fit.** For each trial movement, subtract one typical log-intensity difference before judging agreement. Like allowing one dimmer-switch adjustment across the whole photograph, this separates global gain from movement. Coarse search minimises mean absolute centred difference; local fitting uses the selected robust loss.

The original engine uses the upper median for an even sample count, not the average of the two middle values. Gain refers to intensities including the positive offset; it does not establish exact invariance to multiplying near-zero raw intensities or to arbitrary local waves. Ratio-uniformity registration predates this project (Woods et al., 1992); the current robust log-domain combination is not a claim to invent the general use of intensity ratios.

**In words:** remove one overall brightness difference for each proposed alignment, then score what remains.

~~~text
(6) d_p(x)=L_b(W_p(x))-L_a(x)
    c_p=upper_median(d_p); r_p=d_p-c_p
    coarse_cost(p)=mean(abs(r_p)); fitted_gain=2^c_p
~~~

$$
d_{\mathbf p}(\mathbf x)=L_b(W_{\mathbf p}\mathbf x)-L_a(\mathbf x),
\quad c_{\mathbf p}=\operatorname{umed}(d_{\mathbf p}),
\quad r_{\mathbf p}=d_{\mathbf p}-c_{\mathbf p},
\quad C_{\mathrm{coarse}}=\operatorname{mean}|r_{\mathbf p}|,
\quad g_{\mathbf p}=2^{c_{\mathbf p}}.
\tag{6}
$$

*In the engine:* [PairAligner](../src/main/java/ripr/core/PairAligner.java), coarse search/evaluate/report; biological execution uses the pinned original classes.

## Step 7 - Give reliable, agreeing pixels more influence

**Log-ratio fitting; this support rule is specifically Moving cells.** During local refinement, edges vote only when they exceed half the estimated raw noise in both mapped images. Noise uses the image minus its four-neighbour mean, with a robust scale divided by the square root of 1.25. Raw-gradient strength is reconstructed from log derivatives; original lookup and rounding are retained.

Tukey weights suppress large residuals (Beaton and Tukey, 1974). Repeated weighted gradient updates use the Lucas–Kanade and iteratively reweighted least-squares principles (Lucas and Kanade, 1981; Holland and Welsch, 1977). A bounded update is accepted only if it improves the loss, trying smaller steps when needed. Other log-ratio settings can use Huber weights, least squares or different pixel support; those are not the biological default.

**In words:** shared, noise-qualified structure determines small movement updates, while inconsistent pixels have less influence.

~~~text
(7) support=[G_a>=0.5*noise_a AND mapped G_b>=0.5*noise_b]
    m=upper_median(abs(r))
    if m<=0: m=ordered_abs_r[floor(0.9*(sample_count-1))]
    sigma=max(1e-4,1.4826*m); cutoff=4.685*sigma
    w(r)=(1-(r/cutoff)^2)^2 if abs(r)<cutoff, otherwise0
    H=sum(w*J*J^T); v=sum(w*J*r); update=-inverse(H)*v
~~~

$$
V=\mathbf1[G_a\ge .5n_a\land G_b\circ W_{\mathbf p}\ge .5n_b],
\quad m_r=\begin{cases}\operatorname{umed}|r|,&\operatorname{umed}|r|>0\\
|r|_{(\lfloor .9(N_r-1)\rfloor)},&\text{otherwise},\end{cases}
\quad \sigma=\max(10^{-4},1.4826m_r),\quad \kappa=4.685\sigma,
\quad w(r)=\begin{cases}(1-(r/\kappa)^2)^2,&|r|<\kappa\\0,&\text{otherwise},\end{cases}
\quad H=\sum_VwJJ^\top,\quad\mathbf v=\sum_VwJr,\quad\Delta=-H^{-1}\mathbf v.
\tag{7}
$$

A zero median absolute residual triggers an ordered 90th-percentile fallback before the scale floor. Moving cells uses at most 12 iterations per level, 0.001-pixel convergence and a 50,000-sample regular-grid budget. Shared-edge support does not restrict coarse search or the reported residual. This is not cell segmentation.

*In the engine:* [LogPlane](../src/main/java/ripr/core/LogPlane.java), noise/gradient evidence; [PairAligner](../src/main/java/ripr/core/PairAligner.java), local fit; [RobustNorm](../src/main/java/ripr/core/RobustNorm.java), weights/scale.

## Step 8 - Use correlation instead where the recipe calls for it

**Alternative preliminary matcher and basis of tissue refinement; not Moving cells.** Normalised correlation compares centred patterns after scaling out contrast. It replaces the log-ratio objective rather than being averaged with it. Grid, Newton and enhanced correlation coefficient refinements are distinct local-search implementations.

Enhanced correlation coefficient fitting is established work by Evangelidis and Psarakis (2008). The project's area matcher has its own translation update and grid fallback. Bright/dim later calls OpenCV's rigid implementation; Landmarks uses the project's translation implementation. Cubic B-spline sampling in the area matcher follows Unser et al. (1993).

**In words:** prefer movements that align the centred patterns after accounting for overall contrast.

~~~text
(8) a=A-mean(A); b=B_warped-mean(B_warped)
    correlation=dot(a,b)/sqrt(dot(a,a)*dot(b,b))
    ECC: Hc=G^T*G; p=G^T*a; q=G^T*b
    gain=(b^T*b-q^T*inverse(Hc)*q)/(a^T*b-p^T*inverse(Hc)*q)
    update=inverse(Hc)*(gain*p-q)
~~~

$$
a=A-\bar A,\quad b=B_W-\overline{B_W},\quad
\rho=\frac{a^\top b}{\sqrt{(a^\top a)(b^\top b)}},
\quad H_C=G^\top G,\quad p_C=G^\top a,\quad q_C=G^\top b,
\quad\gamma=\frac{b^\top b-q_C^\top H_C^{-1}q_C}{a^\top b-p_C^\top H_C^{-1}q_C},
\quad\Delta_C=H_C^{-1}(\gamma p_C-q_C).
\tag{8}
$$

This is the translation update, not a claim of a guaranteed global maximum. Degenerate variance and curvature are rejected. Native OpenCV has its own sampling and termination.

*In the engine:* [AreaCorrelation](../src/main/java/ripr/core/AreaCorrelation.java), align/eccRefine/Window; [LongitudinalReferenceArea](../src/experimental/java/ripr/core/LongitudinalReferenceArea.java), ecc.

## Step 9 - Optionally select informative regions and refit

**Only preliminary or manual recipes that enable pixel selection. Moving cells uses none.** A pilot fit supports a mask. The spatial-information option measures two-direction detail using log-image gradients and the smaller eigenvalue of their local mean matrix, following Shi and Tomasi's (1994) feature-quality principle. The mask is mapped into each source frame for a raw-image refit.

A configurable low-information fraction is removed. Other temporal scoring options exist but are not silently part of Moving cells. This pilot/refit mask is separate from the persistent Landmarks reference mask.

**In words:** if enabled, use a pilot fit to select informative regions, then fit original images with their mapped masks.

~~~text
(9) L_score=log2(scoring_image+epsilon)
    S(x)=mean_valid_neighbours(gradient(L_score)*gradient(L_score)^T)
    information(x)=smaller_eigenvalue(S(x))
    eligible=valid AND [gradient_magnitude>=0.5*upper_median(valid gradients)]
    order=eligible finite-score pixels sorted by (information,pixel_index)
    remove_count=round(remove_percent*count(order)/100)
    mask=true everywhere, then false at the first remove_count ordered pixels
~~~

$$
L_{\mathrm{score}}=\log_2(P_{\mathrm{score}}+\epsilon),\quad
S(\mathbf x)=\operatorname{mean}_{\mathbf u\in\mathcal N_{\mathrm{valid}}(\mathbf x)}
\nabla L_{\mathrm{score}}(\mathbf u)\nabla L_{\mathrm{score}}(\mathbf u)^\top,
\quad a_{\mathrm{info}}=\lambda_{\min}(S),
\quad\mathcal I=\operatorname{sort}_{(a_{\mathrm{info}},\mathrm{index})}
\{\mathbf x:\text{valid finite score},\
\|\nabla L_{\mathrm{score}}\|\ge .5\operatorname{umed}_{\mathrm{valid}}\|\nabla L_{\mathrm{score}}\|\},
\quad n_{\mathrm{remove}}=\operatorname{round}(q_r|\mathcal I|/100),
\quad M_{\mathrm{info}}(\mathbf x)=\mathbf1[\mathbf x\notin\mathcal I_{1:n_{\mathrm{remove}}}].
\tag{9}
$$

The rule removes the rounded requested count among eligible finite-score pixels, ordering ties by pixel index. Noneligible pixels are not automatically removed by this mask. Downstream validity and support still apply, so the fraction of all image pixels used can differ from the removal setting.

*In the engine:* [PixelSelectionEngine](../src/main/java/ripr/PixelSelectionEngine.java), pilot/refit; [LagPixelSelector](../src/main/java/ripr/LagPixelSelector.java), scoring/mask transport.

## Step 10 - Combine pair movements and fill unsupported positions

**Moving cells and tissue preliminary fitting.** Multi-lag fitting finds positions that agree with usable pair measurements, anchored at the first frame. Moving cells uses equal edge weights and a small numerical ridge. Optional uncertainty-weighted or robust graph modes are not active biological behavior.

Unsupported positions are interpolated between trusted neighbours or held at supported endpoints. Moving cells disables large-step outlier rejection, so a supported jump is not rejected merely for being rare. Its movements are now final; only tissue routes continue to reference fitting.

**In words:** find the journey most consistent with the pair measurements, then infer positions only where support is missing.

~~~text
(10) u_0=0
     u=argmin[sum_edges ||u_j-u_i-d_ij||^2+ridge*sum_(t>0)||u_t||^2]
     unsupported t between a,b: u_t=(1-f)*u_a+f*u_b; f=(t-a)/(b-a)
~~~

$$
\mathbf u_0=0,\quad
\mathbf u^*=\arg\min_{\mathbf u}\left[
\sum_{(i,j)\in\mathcal E}\|\mathbf u_j-\mathbf u_i-\boldsymbol\delta_{ij}\|^2
+\eta\sum_{t>0}\|\mathbf u_t\|^2\right],
\quad \mathbf u_t=(1-f)\mathbf u_a+f\mathbf u_b,\quad f=\frac{t-a}{b-a}.
\tag{10}
$$

This graph is translation-only, as in Moving cells. General rigid support instead rotates translation constraints by the observed pair angle. The ridge is the larger of the smallest normal double and 10^-9 times the mean normal-matrix diagonal.

*In the engine:* [Reconciler](../src/main/java/ripr/core/Reconciler.java), multiLag; [ChainRepair](../src/main/java/ripr/core/ChainRepair.java), unsupported/outlier repair.

## Step 11 - Build tissue features

**Bright/dim and Landmarks only.** Both use raw same-channel frames, normalised between the 1st and 99th intensity percentiles and clipped to zero–one. Gaussian filtering and median-based scales are established primitives; their combinations and constants here are project-defined.

Bright/dim subtracts broad glow, divides the **uncentred** high-pass image by its median-based scale and clips it. Landmarks separately scales edge magnitude, fine dark detail and broad dark detail, combines them with weights 0.65, 1 and 0.55, suppresses the border, scales again and adds six. These are not interchangeable “normalised images”. The landmark feature is also reused inside rare-jump checks for Bright/dim, without changing its main reference recipe.

**In words:** construct either glow-reduced detail or an edge-and-dark-region image, without replacing the measurement images.

~~~text
(11) U=clip((E-P1(E))/max(P99(E)-P1(E),1e-6),0,1)
     R(Z)=clip((Z-median(Z))/max(1e-5,1.4826*median(abs(Z-median(Z)))),-5,5)
     Bright/dim: H=U-Gaussian_broad(U); F=clip(H/robust_scale(H),-5,5)
     Landmarks: F=6+R(border_mask*(0.65*R(edges)+R(dark_fine)+0.55*R(dark_broad)))
~~~

$$
U=\operatorname{clip}_{[0,1]}\frac{E-Q_1(E)}{\max(Q_{99}(E)-Q_1(E),10^{-6})},
\quad R(Z)=\operatorname{clip}_{[-5,5]}
\frac{Z-\operatorname{med}Z}{\max(10^{-5},1.4826\operatorname{med}|Z-\operatorname{med}Z|)},
\quad H_{\mathrm{HP}}=U-G_{\sigma_b}*U,
\quad F_{\mathrm{BD}}=\operatorname{clip}_{[-5,5]}
\frac{H_{\mathrm{HP}}}{\max(10^{-5},1.4826\operatorname{med}|H_{\mathrm{HP}}-\operatorname{med}H_{\mathrm{HP}}|)},
\quad F_{\mathrm{LM}}=6+R(M_\partial[.65R(E_\nabla)+R(D_f)+.55R(D_b)]).
\tag{11}
$$

Tissue medians average the middle pair, unlike the original log-ratio upper median. Gaussian widths, dark-detail definitions, arithmetic precision and borders are in Configuration.

*In the engine:* [LongitudinalReferenceFeatures](../src/main/java/ripr/core/LongitudinalReferenceFeatures.java), normalise/emission/landmark/robustScale; [LongitudinalPreparedFrames](../src/experimental/java/ripr/core/LongitudinalPreparedFrames.java), prepare.

## Step 12 - Build or choose tissue references

**Bright/dim:** measure brightness by the 75th percentile and structure by the 95th-minus-5th percentile range in the raw central 12–88% region. Select the brightest and dimmest contrast-qualified frames. If fewer than four qualify, all become eligible. Ties select the first encountered frame.

**Landmarks:** align features using preliminary movements, take a finite-pixel median, and require at least 80% temporal support. Select landmarks above the 55th percentile of absolute median deviation from six, expand the mask locally and intersect it with persistent support. This yields one masked reference, not two brightness-state references.

**In words:** choose two informative brightness states for Bright/dim, or construct one persistent reference for Landmarks.

~~~text
(12) light_t=P75(central E_t); contrast_t=P95(central E_t)-P5(central E_t)
     eligible={t: contrast_t>=max(1e-6,0.35*median(contrast))}
     if fewer than4 eligible: use all; bright=argmax(light); dim=argmin(light)
     Tref(x)=median_t F_t(W_preliminary,t(x)); support=[valid_count/T>=0.80]
     mask=support AND dilate(support AND [abs(Tref-6)>=P55_supported(abs(Tref-6))])
~~~

$$
l_t=Q_{75}(E_t|_{\Omega_c}),\quad c_t=Q_{95}(E_t|_{\Omega_c})-Q_5(E_t|_{\Omega_c}),
\quad\mathcal A=\{t:c_t\ge\max(10^{-6},.35\operatorname{med}_t c_t)\},
\quad b_*=\arg\max_{\mathcal A}l_t,\quad d_*=\arg\min_{\mathcal A}l_t;
\quad T_{\mathrm{ref}}=\operatorname{med}_t F_t\circ W_{\mathrm{pre},t},
\quad S_{\mathrm{ref}}=\mathbf1[N_{\mathrm{valid}}/T\ge .8],
\quad M_{\mathrm{ref}}=S_{\mathrm{ref}}\cap\operatorname{dilate}
(S_{\mathrm{ref}}\cap\mathbf1[|T_{\mathrm{ref}}-6|\ge Q_{55,S_{\mathrm{ref}}}(|T_{\mathrm{ref}}-6|)]).
\tag{12}
$$

For Bright/dim, the eligible set is replaced by all frames when its count is below four. For Landmarks, outside-mask pixels become invalid; insufficient selected support is a failure.

*In the engine:* [OpenCvLongitudinalTrajectory](../src/experimental/java/ripr/core/OpenCvLongitudinalTrajectory.java), references; [LongitudinalReferenceLandmarks](../src/experimental/java/ripr/core/LongitudinalReferenceLandmarks.java), reference.

## Step 13 - Match tissue frames to their references

**Bright/dim:** fit the bright-to-dim bridge, then both reference-to-frame paths. Each pair tries a Fourier phase seed, relative preliminary movement and identity, removing duplicate seeds. The phase seed uses an unwindowed spectrum and local 0.1-pixel refinement, related to Guizar-Sicairos et al. (2008). OpenCV fits translation and in-plane angle by enhanced correlation coefficient maximisation, up to 100 iterations and 10^-7 termination tolerance. The best successful seed wins each pair.

Dim-path quality is the weaker of the frame and bridge scores. Each frame takes the better bright or chained dim path, with bright winning ties. **No blending occurs.** Movements are anchored to the first frame. Correlation strength and reference agreement determine confidence. If every seed fails for a required pair, fitting fails; it does not switch silently to Moving cells.

**Landmarks:** match each feature to the masked reference using a five-by-five integer neighbourhood at each pyramid level, then the project's translation-only enhanced correlation coefficient update. If no update is accepted, use grid refinement. Failed fits are filled, first-frame anchoring is applied and feature correlation becomes confidence. This branch does not call OpenCV's enhanced correlation coefficient fitter.

**In words:** choose the stronger path for Bright/dim or fit the single landmark reference, then anchor movements to the first frame.

~~~text
(13) A_dimpath,t=A_dim_to_t*A_bright_to_dim
     q_dimpath,t=min(q_dim_to_t,q_bridge)
     A_t=A_bright_to_t if q_bright,t>=q_dimpath,t, otherwise A_dimpath,t
     A_anchored,t=A_t*inverse(A_0)
     Bright/dim score=max(q_bright,t,q_dimpath,t)
     Landmarks score=correlation(anchored feature,masked reference)
     confidence=clip((score-floor)/max(median(score)-floor,0.05),0,1)
     floor_BD=clamp(median(score)-2.5*MADscale(score),0.18,0.50)
     floor_LM=clamp(median(score)-3*MADscale(score),0.15,0.45)
~~~

$$
A^d_t=A_{d_*\to t}A_{b_*\to d_*},\quad q^d_t=\min(q_{d_*\to t},q_{b_*\to d_*}),
\quad A_t=\begin{cases}A_{b_*\to t},&q^b_t\ge q^d_t\\A^d_t,&q^b_t<q^d_t,\end{cases}
\quad A'_t=A_tA_0^{-1},
\quad q_t=\begin{cases}
\max(q^b_t,q^d_t),&\mathrm{BD}\\
\operatorname{corr}(F_t\circ W'_t,T_{\mathrm{ref}}),&\mathrm{LM},
\end{cases}
\quad C_t=\operatorname{clip}_{[0,1]}\frac{q_t-f_q}{\max(\operatorname{med}q-f_q,.05)},
\quad f_q=\begin{cases}
\operatorname{clip}_{[.18,.50]}(\operatorname{med}q-2.5a_q),&\mathrm{BD}\\
\operatorname{clip}_{[.15,.45]}(\operatorname{med}q-3a_q),&\mathrm{LM}.
\end{cases}
\tag{13}
$$

Matrix path selection is Bright/dim only. Its confidence is zeroed when similarly scored paths disagree excessively. A defined terminal-event exception can restore minimal support or a preliminary pose. These rules are not calibrated probabilities.

*In the engine:* [OpenCvLongitudinalTrajectory](../src/experimental/java/ripr/core/OpenCvLongitudinalTrajectory.java), fit/estimate; [OpenCvLongitudinalOps](../src/experimental/java/ripr/core/OpenCvLongitudinalOps.java), phase/fit; [LongitudinalReferenceArea](../src/experimental/java/ripr/core/LongitudinalReferenceArea.java), align; [LongitudinalReferenceLandmarkTrajectory](../src/experimental/java/ripr/core/LongitudinalReferenceLandmarkTrajectory.java), assemble.

## Step 14 - Check for weak fits and returning excursions

**Both tissue recipes; never Moving cells.** Movement checks use horizontal shift, vertical shift and angle multiplied by half the image diagonal, so all three coordinates are pixel-equivalent. Supported persistent jumps are identified from neighbouring frame groups, and the movement record is split there.

Within each segment, weak positions are interpolated or held at supported endpoints. An entirely weak segment takes its median pose. Short out-and-back excursions are replaced by straight endpoint connections only when both their deviation and excess journey length exceed fixed thresholds. This is targeted cleanup, not smoothing every point or fitting new parameters per recording.

**In words:** preserve supported jumps, fill weak positions within each segment and remove only excursions satisfying the return rule.

~~~text
(14) pose_t=(dx_t,dy_t,radius*theta_t); threshold=max(2.5,0.006*diagonal)
     line_i=pose_a+(i-a)*(pose_b-pose_a)/(b-a)
     replace interior only if max_i ||pose_i-line_i||>threshold
       AND sum_i ||pose_i-pose_(i-1)||-||pose_b-pose_a||>2*threshold
~~~

$$
\mathbf v_t=(d_{x,t},d_{y,t},r_D\theta_t),\quad\tau=\max(2.5,.006D),
\quad\mathbf l_i=\mathbf v_a+\frac{i-a}{b-a}(\mathbf v_b-\mathbf v_a),
\quad \max_{a\le i\le b}\|\mathbf v_i-\mathbf l_i\|>\tau
\ \land\
\sum_{i=a+1}^{b}\|\mathbf v_i-\mathbf v_{i-1}\|-\|\mathbf v_b-\mathbf v_a\|>2\tau.
\tag{14}
$$

Intervals span 2–12 frame intervals, with at most three passes. Persistent-jump gates and traversal order are fixed below. No new image evidence enters this cleanup.

*In the engine:* [LongitudinalReferenceTrajectoryRepair](../src/experimental/java/ripr/core/LongitudinalReferenceTrajectoryRepair.java), persistentJumps/fillWeak/removeReturning.

## Step 15 - Check exceptional jumps

**Bright/dim:** a late-jump check measures the largest bright tissue component's centre in every frame, comparing raw and corrected displacements near the end. It needs enough frames and a valid component throughout. A separate terminal-chain check uses preliminary large steps, landmark features and adjacent-pair verification.

**Landmarks:** suspect boundaries throughout the recording are checked with an adjacent-frame fit and a fit between median image groups. Agreement in translation and angle is required. Both routes' rigid-event fits use edge/dark features, coarse angular search and local refinement. They do not provide arbitrary continuous-rotation support.

An accepted event replaces the estimated increment through ordered transform composition, preserving the remaining relative journey. It is not simply added again to all later shifts.

**In words:** replace a suspect jump only when its route-specific checks support a measured replacement increment.

~~~text
(15) tissue_centre_t=sum_(x in component_t)x/(component_size_t*resize_scale)
     predicted_increment=inverse(T_(b-1)).then(T_b)
     correction=inverse(predicted_increment).then(measured_increment)
     T_t=T_t.then(correction), for t>=b
~~~

$$
\mathbf c^{\,\mathrm{tissue}}_t=\frac{\sum_{\mathbf x\in K_t}\mathbf x}{|K_t|s_c},
\quad P_b=T_{b-1}^{-1}\operatorname{then}T_b,
\quad C_b=P_b^{-1}\operatorname{then}E_b,
\quad T_t\leftarrow T_t\operatorname{then}C_b\quad(t\ge b).
\tag{15}
$$

The centroid is Bright/dim only. “Then” means the engine's ordered composition, not elementwise addition. Neighbouring support is required; a last-frame-only jump is not guaranteed to be recovered. Coarse angle search spans at least ±15 degrees, not a hard ±10-degree limit.

*In the engine:* [LongitudinalReferenceEndpoint](../src/experimental/java/ripr/core/LongitudinalReferenceEndpoint.java), measure/repairMeasured; [LongitudinalReferenceRigid](../src/experimental/java/ripr/core/LongitudinalReferenceRigid.java), transmitted/terminal/apply; [Transform](../src/main/java/ripr/core/Transform.java), then.

## Step 16 - Keep fit diagnostics separate from accuracy

**All recipes, with different records.** Record the actual recipe and implementation, not just a menu option. Log-ratio fitting reports residual disagreement and support. Tissue fitting reports references, correlation-derived confidence and jump events. The biological wrapper exposes movements, support, pair count and engine identity, not every private-engine diagnostic.

The log-ratio residual uses valid sampled positions without the shared-edge restriction. It is not geometric error in pixels. A frame's diagnostic row follows its smallest-lag incoming edge; longer edges affect the trajectory but are not averaged into that row. Preliminary residuals do not become final-output accuracy after tissue refinement.

**In words:** report sampled intensity disagreement as a fit diagnostic, separately from movement accuracy.

~~~text
(16) log_residual(p)=mean_valid_samples(abs(L_b(W_p(x))-L_a(x)-c_p))
     fractional_reduction=1-log_residual_after/log_residual_before if before>0,
                          otherwise0
~~~

$$
E_{\log}(\mathbf p)=\operatorname{mean}_{\mathrm{valid\ samples}}
|L_b(W_{\mathbf p}\mathbf x)-L_a(\mathbf x)-c_{\mathbf p}|,
\qquad R_{\log}=\begin{cases}
1-E_{\log,\mathrm{after}}/E_{\log,\mathrm{before}},&E_{\log,\mathrm{before}}>0\\
0,&\text{otherwise}.
\end{cases}
\tag{16}
$$

When the before-residual is zero, the engine reports zero reduction as a convention, not a meaningful percentage improvement. A warning, refused fit, repaired position and measured image displacement are different records.

*In the engine:* [PairAligner](../src/main/java/ripr/core/PairAligner.java), report; [Registration](../src/main/java/ripr/core/Registration.java), diagnostics; [LongitudinalVideoRouting](../src/experimental/java/ripr/core/LongitudinalVideoRouting.java), Result.

## Step 17 - Move the original images

**All recipes, after final estimation.** The experimental route returns transforms; an exporter applies them to the original frames. Native review producers use bilinear sampling, not feature images. The main plugin can apply a guide transform to every original channel/depth, but these experiments register channels separately.

A stored transform maps reference coordinates to displaced content in the source frame. Output sampling visits that source position to align the image. Fractional sampling mixes neighbours: original values remain the inputs, but output values can change. Integer outputs are rounded and clipped.

**In words:** sample the original image using the final movement, combining four neighbours at a fractional position.

~~~text
(17) source_position=centre+R(theta)*(output_position-centre)+shift
     output=(1-beta)*[(1-alpha)*I(i,j)+alpha*I(i+1,j)]
            +beta*[(1-alpha)*I(i,j+1)+alpha*I(i+1,j+1)]
~~~

$$
\mathbf y=\mathbf c+R_{\theta_t}(\mathbf x-\mathbf c)+\mathbf d_t,\qquad
O_t(\mathbf x)=(1-\beta)[(1-\alpha)E_t(i,j)+\alpha E_t(i+1,j)]
+\beta[(1-\alpha)E_t(i,j+1)+\alpha E_t(i+1,j+1)].
\tag{17}
$$

The native helper copies whole-pixel shifts directly, fills outside positions with zero and supports unsigned 8-bit, unsigned 16-bit and 32-bit float. Generic nearest-neighbour, bicubic and Fourier options are separate output choices.

*In the engine:* [LongitudinalReferenceWarper](../src/experimental/java/ripr/core/LongitudinalReferenceWarper.java), bilinear; [StackWarper](../src/main/java/ripr/StackWarper.java), general output; [Warper](../src/main/java/ripr/core/Warper.java), interpolation.

## Step 18 - Handle empty borders

**All recipes, according to the exporter.** Movement can expose areas outside the original image. Those pixels are fill, not measured dark tissue. Optional common-field cropping removes them. Native comparison exports retain full frames, while the guide excludes invalid borders.

Cropping changes the field shown or scored, not estimated movement. It must not hide failure or silently give methods different amounts of tissue to compare.

**In words:** the common field contains only positions with valid source support in every relevant frame.

~~~text
(18) common_field=intersection_t {x: W_t(x) lies in valid source support}
     cropped_output_t=output_t restricted to chosen common-field rectangle
~~~

$$
\Omega_{\mathrm{common}}=\bigcap_t\{\mathbf x:W_t(\mathbf x)\in\Omega_{\mathrm{source,valid}}\},
\qquad O_t^{\mathrm{crop}}=O_t|_{\operatorname{rect}(\Omega_{\mathrm{common}})}.
\tag{18}
$$

Implementations use conservative valid rectangles and interpolation margins, not necessarily the largest possible intersection shape.

*In the engine:* [Warper](../src/main/java/ripr/core/Warper.java), validMargin; [StackWarper](../src/main/java/ripr/StackWarper.java), cropping; biological scorer, common_crop.

## Step 19 - Make the review grid and its guide score

**Review only; not recipe selection.** The reusable montage shows original images, the actual selected recipe and external methods, with labels, original registration times, guide scores when available and explicit failures. Montage contrast and size are for viewing; native registered TIFFs are the measurement outputs.

The historical biological guide compares corrected frames with the corrected first frame inside common valid support. It averages two-by-two blocks, standardises brightness and contrast, applies an edge-tapering Hann window and estimates subpixel phase-correlation displacement. The median summary excludes the reference frame. This equation describes that scorer specifically; other historical grids retain their own recorded metric provenance.

**In words:** estimate apparent movement left after correction, convert it to native pixels and label it as a guide, not ground truth.

~~~text
(19) Q_t=Hann*standardise(blockmean2(common_crop(O_t)))
     guide_t=2*sqrt(phase_dx_to_first^2+phase_dy_to_first^2)
     recording_guide=median_(t=1..T-1)(guide_t), first frame indexed0
~~~

$$
Q_t=H_{\mathrm{Hann}}\operatorname{standardise}(B_2(O_t|_{\Omega_{\mathrm{common}}})),
\quad e_t^{\mathrm{guide}}=2\|\boldsymbol\delta^{\,\mathrm{phase}}(Q_0,Q_t)\|,
\quad e_{\mathrm{video}}^{\mathrm{guide}}=\operatorname{med}_{t=1}^{T-1}e_t^{\mathrm{guide}}.
\tag{19}
$$

Standardisation subtracts the mean and divides by population standard deviation; phase refinement is tenfold. Missing common field, absent contrast and failed registration remain unavailable/failed, not zero error. Cached outputs retain their original provenance and timing.

*In the engine:* [biological native-output scorer](../library/benchmark/biological_foreground_motion_tuning/code/s33_score_native_outputs.py), crop/normalisation/residual scoring (read only, not executed here); [RegistrationReviewLabels](../src/experimental/java/ripr/core/RegistrationReviewLabels.java), forRecipe.

---
---

# Reference sections

## Where each step misleads

| Step | What goes wrong | What the step cannot establish |
|---|---|---|
| 1. Guide | Wrong channel or time/depth interpretation | Modality cannot safely be read from legacy filenames |
| 2. Recipe | Unfamiliar images resemble training examples | Biological identity, motion type or perfect recipe choice |
| 3. Copies | Filters remove structure; empty signal stays empty | Normalisation is not recovered information |
| 4. Pairs | Long-separated images change; chained errors accumulate | More pairs do not guarantee accuracy |
| 5. Search | Wrong coarse basin or bound | Padded search is not true maximum motion |
| 6. Gain | Local illumination differs | One gain cannot remove arbitrary waves |
| 7. Robust votes | Most visible structure changes together | Robust fitting is not cell tracking or pulse-proofing |
| 8. Correlation | False structures match strongly | High correlation is not true pixel accuracy |
| 9. Mask | Bad pilot or lost landmarks | This is not the Landmarks reference mask |
| 10. Trajectory | Wrong pair shifts agree; missing positions are filled | Consistency/interpolation is not new image evidence |
| 11. Features | Broad true landmarks lost; dim noise amplified | Feature intensities are not measurement outputs |
| 12. References | Wrong preliminary fit blurs template | A chosen reference need not be geometrically correct |
| 13. Tissue fit | False matches agree or all seeds fail | Confidence is not a calibrated probability |
| 14. Cleanup | Real out-and-back motion meets the rule | Removed excursions are not proven artefacts |
| 15. Jumps | Too few neighbours or disappearing tissue | Universal final-frame recovery or continuous rotation |
| 16. Diagnostics | Preliminary intensity residual read as final error | Correlations and intensity units are not pixel truth |
| 17. Warp | Different rounding/sampling changes pixels | Movement-table equality does not prove TIFF identity |
| 18. Borders | Fill scored as real tissue | Cropping does not validate alignment |
| 19. Review | Biology/pulses fool phase correlation | Guide score is not ground truth or a detector benchmark |

## Worked examples

All numbers below are **hypothetical arithmetic**, not project measurements.

**Step 1.** A depth column [2,7,4] projects to seven. Other channels do not contribute.

**Step 2.** Distances 0.10, 0.15 and 0.20 from three recordings average 0.15. A competing recipe at 0.18 fails the required separation of 0.195, so selection refuses.

**Step 3.** Raw zero plus offset one gives log2(1)=0. Missing data does not become this valid zero.

**Step 4.** Five frames give four lag-1, three lag-2 and one lag-4 pair: eight comparisons.

**Step 5.** A largest coarse shift of 40 native pixels and coarse spacing eight give a 68-pixel bound, above the minimum 30.

**Step 6.** Differences [1,1,1,5] have upper median one; residuals [0,0,0,4] give coarse mean absolute cost one.

**Step 7.** Scale one gives Tukey cutoff 4.685, so residual five gets zero weight. A 512-square image with 50,000 sample budget uses stride three, not exactly 50,000 pixels.

**Step 8.** [1,2,3] and [12,14,16] have correlation one despite different brightness/contrast. This does not establish the correctness of a spatial alignment.

**Step 9.** Four eligible information scores [0,1,2,3] with 25% removal discard exactly the first score. Tied scores are ordered by pixel index.

**Step 10.** Supported positions zero and four at frames zero and two give an inferred middle position two.

**Step 11.** Percentile bounds 10 and 110 map intensity 60 to 0.5. High-pass 0.02 divided by scale 0.01 gives two, without subtracting a median from that final numerator.

**Step 12.** Ten frames require eight valid contributions for persistent support. Only three contrast-qualified Bright/dim frames cause all frames to become eligible.

**Step 13.** Dim match 0.9 through bridge 0.6 gives path score 0.6. Bright score 0.7 wins; movements are not averaged.

**Step 14.** Diagonal 500 gives threshold three. Positions [0,5,0] deviate five and travel ten extra pixels, qualifying for replacement within one segment—even if that motion were real.

**Step 15.** Diagonal 1,000 gives a centroid-event size floor of at least 80 pixels. A 20-pixel corrected residual alone does not satisfy every gate.

**Step 16.** Residual 0.4 falling to 0.1 is a 75% intensity-residual reduction, not 75% fewer pixels of error.

**Step 17.** Equal mixing of neighbouring values 10 and 20 yields 15, even if no input pixel measured 15.

**Step 18.** Three invalid columns on one side and five on the other require excluding both margins, not only the final frame's margin.

**Step 19.** Reduced-pixel displacement (0.3,0.4) has length 0.5; twofold reduction makes the native guide one pixel.

## Interpretation guide

Supported:

- Relative-Intensity Pattern Registration is a combined method with explicit recipes, not one log-ratio objective throughout.
- Each experimental route uses only its supplied channel.
- Moving cells preserves the original Recommended numerical engine and bypasses tissue refinements.
- Bright/dim chooses between two reference paths; Landmarks uses a persistent edge/dark reference.
- Residuals, confidence, failures and review guides aid inspection and reproducibility.

Not supported:

- Perfect registration of every stack, guaranteed pulse invariance, cell tracking or recovery from absent signal.
- Invention of enhanced correlation coefficient fitting, correlation, robust fitting, Fourier alignment or interpolation.
- True pixel error inferred from intensity residual, correlation or uncalibrated confidence.
- Automatic-selector validation inferred from manually assigned or cached review panels.
- Pixel identity, speed improvement or installed-plugin parity inferred from this documentation task.

## Symbols

Indices/subscripts identify their frame, recipe or axis. Plain-text equations use spelled-out aliases. Ordinary tissue medians average the middle pair; the original log-ratio engine explicitly uses the upper median.

| Symbol | Meaning | Units |
|---|---|---|
| \(X,E,P,O,O^{crop}\) | Input hyperstack, selected guide, preliminary prepared image, registered/cropped image | Native intensity |
| \(t,T,c_*,z_*,z,i,j,a,b\) | Frame index/count, selected channel/depth, depth/sample/frame indices as indicated | Indices/counts |
| \(\mathbf x,\mathbf y,W,H,\mathbf c\) | Output/source coordinates, image dimensions and centre | Pixels |
| \(f_j,e_{ij},s_j,d_i,d_{k,g},S_k,k,k_*,g\) in (2) | Detector/example values, scales, distances, scores, recipe keys and recording group | Dimensionless/categories |
| \(F_{preset},s_e,b_t,\epsilon,L\) | Preparation, spatial scale, subtracted background, log offset and log image | Operator; ratio; native intensity; native intensity; log2 intensity |
| \(\mathcal E,\Lambda,\lambda\) | Pair set, lag set and lag | Frame pairs/intervals |
| \(M,M_0,d_{max},h_c,\ell,B_{valid},B_{1D},*\) | Search/minimum bound, coarse displacement/spacing, level, blur kernels, convolution | Pixels; index/operators |
| \(W_{\mathbf p},W_t,W_{pre,t},\mathbf p,\mathbf d_t,T_t,R_\theta,\theta\) | Sampling maps, trial/final shift, transform, rotation matrix and angle | Operators; pixels; transform; radians |
| \(d_{\mathbf p},c_{\mathbf p},r_{\mathbf p},C_{coarse},g_{\mathbf p}\) | Log difference, log gain, residual, coarse cost and linear gain | Log2 units, except dimensionless linear gain |
| \(V,G_a,G_b,n_a,n_b\) | Voting indicator, reconstructed gradients and raw noise | Binary; intensity/pyramid pixel; intensity |
| \(\sigma,\kappa,w,J,H,\mathbf v,\Delta\) in (7) | Robust scale/cutoff, weight, Jacobian, curvature, gradient vector, update | Log2 units; ratio; derivatives/products; pixels for update |
| \(m_r,N_r,|r|_{(i)}\) | Residual scale statistic after fallback, sample count and zero-based ordered absolute residual | Log2 units; count; log2 units |
| \(A,B_W,a,b,\rho,G,H_C,p_C,q_C,\gamma,\Delta_C\) in (8) | Arrays, centred arrays, correlation, centred gradient matrix, curvature/projections, gain/update | Feature units/derivatives; correlation/gain ratios; update pixels |
| \(S,\mathcal N,\mathbf u,a_{info},\lambda_{min},M_{info},q_r\) in (9) | Local gradient matrix/neighbourhood/coordinate, information, smaller eigenvalue, mask and removal percentile | Squared gradient units; pixel set/coordinate; binary; percent |
| \(L_{score},P_{score},\mathcal N_{valid},\mathcal I,n_{remove}\) | Log scoring image, preprocessed scoring image, valid local neighbourhood, ordered eligible pixels and removal count | Log2 units; native prepared intensity; pixel sets/list; count |
| \(Q_p,\operatorname{med},\operatorname{umed}\) | Interpolated percentile, ordinary median and upper median | Argument's units; percentile index in percent |
| \(\mathbf u_t,\boldsymbol\delta_{ij},\eta,f\) in (10) | Cumulative translation, pair shift, ridge and interpolation fraction | Pixels; dimensionless last two |
| \(U,R(Z),Z,H_{HP},F_{BD},F_{LM},E_\nabla,D_f,D_b,M_\partial,G_{\sigma_b}\) | Normalised image, robust scaling, generic array, high-pass, recipe features, edges/dark details, border mask and Gaussian | Dimensionless arrays/mask; operator |
| \(\sigma_b\) | Broad Gaussian width for the Bright/dim feature | Pixels |
| \(\Omega_c,l_t,c_t,\mathcal A,b_*,d_*,T_{ref},S_{ref},N_{valid},M_{ref}\) | Central region, brightness/contrast, eligible indices, references, median template, support, valid count and mask | Sets/indices; brightness/contrast native units; dimensionless feature/support |
| \(A_{r\to t},A_t,A'_t,A^d_t,q^b_t,q^d_t,q_t,C_t,f_q,a_q\) in (13) | Sampling matrices, selected/anchored/dim-path matrices, scores, confidence, floor and 1.4826-scaled score median absolute deviation | Matrices with pixel shifts/radian angle; dimensionless scores |
| \(F_t,W'_t,\operatorname{corr}\) in (13) | Selected tissue feature, first-frame-anchored sampling transform and centred normalised correlation | Dimensionless feature; transform/operator |
| \(D,r_D,\mathbf v_t,\tau,\mathbf l_i\) | Diagonal, half diagonal, pixel-equivalent pose, threshold and connecting line | Pixels |
| \(\mathbf c^{tissue},K_t,s_c,P_b,C_b,E_b,\operatorname{then}\) in (15) | Tissue centroid/component, resize scale, predicted/correction/measured events, ordered composition | Pixels/set; ratio; transforms/operator |
| \(E_{log},R_{log}\) | Sampled log-residual and fractional reduction | Log2 units; ratio |
| \(i,j,\alpha,\beta\) in (17) | Lower source-grid coordinates and fractional offsets | Pixel indices; ratios |
| \(\Omega_{common},\Omega_{source,valid},\operatorname{rect}\) | Common/source valid sets and conservative rectangle | Pixel sets/operator |
| \(Q_t,H_{Hann},B_2,\boldsymbol\delta^{phase},e_t^{guide},e_{video}^{guide}\) | Review features/window/block mean, reduced shift and frame/video guide | Dimensionless; reduced pixels; native pixels for scores |

## Technical reference

### Data flow

~~~text
LongitudinalVideoRouting.checkImage()                                  [1]
  -> explicit estimate(route, subtype, motion) OR                     [2]
     estimateAutomatic() -> LongitudinalVideoDetector.detect()
                            -> uncertain: refuse/request recipe
                            -> confident: dispatch one route
  |
  +-- BiologicalRecommendedRegistration.estimate()
  |     private ORIGINAL engine:
  |     preparation -> pairs -> bound/pyramids                        [3-5]
  |     log gain -> shared-edge robust fit                            [6-7]
  |     Reconciler -> ChainRepair -> FINAL movements                  [10]
  |
  +-- LongitudinalReferenceRegistration.estimate()
        current fixed-recipe preliminary estimate                     [3-10]
        |
        +-- Bright/dim:
        |   LongitudinalReferenceFeatures.emission()                  [11]
        |   OpenCvLongitudinalTrajectory.references()/fit()/estimate() [12-13]
        |
        +-- Landmarks:
            LongitudinalReferenceFeatures.landmark()                  [11]
            LongitudinalReferenceLandmarks.reference()                [12]
            LongitudinalReferenceArea.align()
            LongitudinalReferenceLandmarkTrajectory.assemble()        [13]
        |
        LongitudinalReferenceTrajectoryRepair.repair()                [14]
        +-- Bright/dim: Endpoint.repair() -> Rigid.terminal()/apply()  [15]
        +-- Landmarks: Rigid.transmitted()/apply()                     [15]
        |
        FINAL movements + actual recipe and diagnostics              [16]
  |
  exporter: original pixels -> sampling -> optional crop              [17-18]
  review: native/cached outputs -> recorded scorer -> labelled grid   [19]

Separate MAIN plugin path, not the three-route diagram:
RelativeIntensityPatternRegistration.estimateLongitudinal()
  -> fixed Automatic -> LongitudinalRegistration (older route).
~~~

### Files

All engine links in the numbered steps identify actual source locations. The following additional files establish settings and provenance.

| File | Role |
|---|---|
| [RelativeIntensityPatternParameters.java](../src/main/java/ripr/api/RelativeIntensityPatternParameters.java) | Literal defaults and preset application |
| [RelativeIntensityPatternRecommendations.java](../src/main/java/ripr/api/RelativeIntensityPatternRecommendations.java) | Image-and-motion settings |
| [AutomaticRegistrationSelectorModel.java](../src/main/java/ripr/api/AutomaticRegistrationSelectorModel.java) | Main fixed declared-class policy, not video detection |
| [LongitudinalReferenceRegistration.java](../src/experimental/java/ripr/core/LongitudinalReferenceRegistration.java) | Tissue-stage coordination |
| [LongitudinalContentMotion.java](../src/experimental/java/ripr/core/LongitudinalContentMotion.java) | Spatial-detail and collected temporal evidence |
| [LongitudinalExecutionPolicy.java](../src/experimental/java/ripr/core/LongitudinalExecutionPolicy.java) | Workers/reuse, not scientific recipes |
| [biological-recommended-arithmetic.md](decisions/biological-recommended-arithmetic.md) | Original-engine isolation and retained detector decision |
| [historical biological exporter](../library/benchmark/biological_foreground_motion_tuning/code/s32_render_native_outputs.py) | Cached native-output interpolation; inspected, not run |
| [historical biological scorer](../library/benchmark/biological_foreground_motion_tuning/code/s33_score_native_outputs.py) | Exact scope of equation 19; inspected, not run |

The compiled biological resource is separately pinned. Current working-tree classes are not a substitute for that engine's numerical identity.

### Configuration

**Entry point and detector**

| Setting | Fixed behavior/default | Changes result if altered? |
|---|---|---|
| Experimental entry | Opt-in three-route wrapper; explicit route or caller-supplied model | Yes |
| Main longitudinal | Older declared-image route, provenance <code>declared_image_type_r14</code> | Yes; not the same implementation |
| Standard build | Main/test sources; experimental wrapper/OpenCV require the separate experimental build | Availability |
| Wrapper input | One channel, at least two 2-D time images; no simultaneous depth/time | Yes |
| Main adapter defaults | Channel 1, maximum depth projection; not wrapper defaults | Yes |
| Motion declaration | Caller supplies tissue motion; detector does not infer it | Yes |
| Biological type | Historical dense-fluorescence lookup key, even on bioluminescence | Must not relabel the real acquisition modality |
| Detector sample | Scale min(1,max(160/long-side,32/short-side)); dimensions floored; min(9,T) evenly spaced rounded frame indices | Yes |
| Structure usability | Both neighbour correlations >0.10 in at least max(2,floor(sample count/3)) images; minimum side 32 | Yes |
| Selected feature 1 | Median of (P50−P1)/max(10^-9,P99−P1) | Yes |
| Selected feature 2 | Average of separately median-aggregated horizontal and vertical neighbour correlations | Yes |
| Selected features 3–4 | Population-standardise; broad Gaussian sigma short-side/24; fine energy/(fine+broad energy), positive fine energy/fine energy; denominator floors 10^-12; aggregate medians | Yes |
| Feature scales | Ordered 75%-index minus 25%-index, indices floored on count−1; floor 10^-6; not interpolated quartiles | Yes |
| Recipe score | Minimum example distance per recording; mean nearest three distinct recordings | Yes |
| Confidence | Usable; ≥3 groups; second score ≥1.3 max(10^-6,best); features within class min/max plus 10% span, minimum pad 10^-6 | Yes |
| Retained confidence rule | <code>feature_range</code>; broader recording-radius trial/tree compatibility are alternatives | Yes |
| Temporal diagnostics | Collected, but omitted from retained four-feature distances | No contribution to this model's choice |
| Refusal | Requires a route choice; no fallback to another recipe | Yes |
| Model availability | Supplied externally; decision record retains original <code>r25_a003_fit_train</code> fit, not broader later trial; old external file location unavailable during this audit | Yes; record full model identity |
| Filename/other channels | Never detector inputs | Would violate this workflow |

**Moving cells: final original Recommended**

| Setting | Frozen value | Changes result if altered? |
|---|---|---|
| Original engine | Resource <code>/ripr/biological/recommended-engine.jar</code>; SHA-256 <code>56dc20e6a4a6118c88d5c239214a465e6216616181df3de26c9ca84c5acb3d5d</code> | Yes |
| Isolation | Private registration classes, no parent fallback; missing/changed resource refused | Yes |
| Lookup / selection | Dense fluorescence + intermittent jumps; explicitly Recommended | Yes |
| Matcher / weights | Log-ratio; Tukey constant 4.685 | Yes |
| Support | Both reconstructed raw gradients ≥0.5× respective raw noise | Yes |
| Noise | Reconstructed value minus four-neighbour mean; robust scale floor 10^-6; divide by sqrt(1.25) | Yes |
| Preparation | None; scale 1; no percentile exclusion or background subtraction | Yes |
| Log offset | 1 native intensity unit | Yes |
| Pairs / graph | Lags 1,2,4,8,16; equal weights; frame 0 anchor | Yes |
| Bound survey | Same pairs, coarse side about 32, ≤9 levels, all valid support, translation only; minimum bound 30 | Yes |
| Main pyramid | Nominal 4 levels / coarse side 48; can add levels to reach radius ≤8, down to minimum side 8 | Yes |
| Iterations / convergence | 12 per level; 0.001 pixel-equivalent | Yes |
| Sampling | Budget 50,000; stride max(1,ceil(sqrt(width×height/budget))) | Yes |
| Minimum valid fraction | 0.10 | Yes |
| Robust scale | 1.4826×upper median absolute residual; ordered 90th-percentile fallback if zero; floor 10^-4 | Yes |
| Step acceptance | Strict robust-loss improvement; halved factors 1 through 1/32; clamp displacement bound | Yes |
| Rotation | Off | Yes |
| Outlier-step rejection | Off; unsupported-frame interpolation still active | Yes |
| Estimation-only crop/interpolation | false/NONE; does not prescribe native TIFF resampling | No transform effect here |
| Workers | 1–16 through execution policy | Intended identical arithmetic; test it |

**Tissue preliminary fitting and optional generic capabilities**

| Setting | Behavior | Changes result? |
|---|---|---|
| Tissue preliminary | Apply recommendation(image subtype,motion), then current fixed Automatic; crop false | Yes; not universally log-ratio |
| Dense-emission translation | Median 3×3, enhanced correlation coefficient area refinement, consecutive, ceiling P90, no outlier cleanup, 25 iterations/200,000 samples | Yes; log-ratio weight/support settings are inert for correlation |
| Sparse emission | Declared image-and-motion Recommended settings | Yes; record actual resolved recipe |
| Other fixed subtypes | Declared-class <code>image_type_rule</code> | Yes; record resolved matcher/preparation |
| Literal defaults before lookup | Log-ratio, Huber, all pixels, 25 iterations, 200,000 samples, valid fraction 0.10, offset 1, multi-lag | Yes; not biological overrides |
| Other robust norms | Huber: cutoff 1.345×scale, unit weight inside/cutoff over residual outside; least squares: unit weights | Yes when selected |
| Support alternatives | All; source-gradient threshold; mutual-noise gradient | Yes for log-ratio |
| Optional preparation | Gaussian, median, variance-stabilising or sharpening operation; exact resolved filter required | Yes; not hidden additions to tissue features |
| Optional intensity handling | Lower/upper exclusions; low-percentile background removal off by default, percentile 1 if enabled | Yes |
| Optional information mask | None unless selected; spatial option uses 5×5 local gradient matrix and default 25% removal; score preparation separate | Yes |
| Reference alternatives | Consecutive, fixed reference (UI default 1), rolling window (default 5), multi-lag | Yes |
| Step-outlier rule | Default 6 robust deviations; threshold at least six times median step; disabled for intermittent jumps | Yes when enabled |
| Graph alternatives | Uncertainty/robust weighting are separate from biological equal weights | Yes |
| Generic rotation | Continuous and event modes exist outside the frozen tissue contract; rolling has restrictions | Yes |
| Main export defaults | Nearest-neighbour/NONE; crop true | Pixels, not review-export defaults |

**Tissue features and references**

| Setting | Bright/dim | Landmarks |
|---|---|---|
| Intensity preparation | P1–P99, range floor 10^-6, clip [0,1]; float bounds/division | Same percentiles; double division then float |
| Gaussian | OpenCV float, reflect-101 border | Separable nearest-border, radius round(4 sigma), fixed accumulation order |
| Widths, short side s | Broad max(2,s/32) | Fine max(0.8,s/512), middle max(3,s/64), broad max(8,s/20), edge preblur max(1.5,s/256) |
| Dark features | Not main feature | Positive middle−fine and broad−middle |
| Edges | Not main feature | Opposite-neighbour difference and perpendicular [3,10,3] smoothing; magnitude |
| Robust scale | 1.4826×ordinary median absolute deviation, floor 10^-5, clip ±5 | Same per component and after combination |
| Final feature | Uncentred high-pass divided by scale, no +6 | Median-centred scaling; final +6 |
| Combination / border | High-pass only | 0.65 edge +1 fine-dark +0.55 broad-dark; suppress max(2,round(0.04 dimension)) at each border |
| Reference | Two actual images | Median of preliminarily aligned features |
| Selection | Central raw 12–88%; light P75; contrast P95−P5 ≥max(10^-6,0.35 median contrast); if <4 qualify, use all | ≥80% finite temporal support; salience ≥P55 of abs(median−6); square dilation side max(1,round(7s/512)) |
| Minimum selected pixels | Frame eligibility rule | max(16,round(width×height/1024)) |
| Failure | Required pair may fail | Missing/insufficient reference support fails construction |

**Tissue fitting and confidence**

| Setting | Bright/dim | Landmarks |
|---|---|---|
| Seeds | Unwindowed float Fourier phase, relative preliminary, identity; round six matrix entries×1000 to deduplicate | Preliminary translation into coarsest level |
| Phase seed | Cross-spectrum floor 100×float machine epsilon; integer magnitude peak; 15×15 local grid at 0.1 pixel | Not main initializer |
| Solver | Native OpenCV rigid in-plane fit, 100 iterations, epsilon 10^-7, no mask | Own translation area-correlation/ECC update |
| Native smoothing | max(1,round(5s/512)), made odd | Separate feature/pyramid filtering |
| Search | Greatest successful seed score | Nominal maximum 9 levels/coarse side 24; can add levels for coarse radius 8 down to side 8; log-domain pyramid; bound max(30,0.6 diagonal); 5×5 integer window per level |
| Fine update | Native rigid loop | ≤8 iterations, step ≤1 pixel, factors 1,1/2,1/4,1/8; convergence max(10^-4,1/256) |
| Fallback | Every seed failed -> pair fails | No accepted ECC update -> five-round 3×3 grid, quarter step on no improvement |
| Samples / overlap | Native full arrays | 200,000 sample setting; ≥max(8,0.1×possible) gradient samples; configured options 25 iterations/10^-4, actual fine limits above |
| Reference path | Bright vs dim chained through bridge; dim score=min(frame,bridge); bright wins ties | One masked reference |
| Confidence floor | clamp(median score−2.5 scaled MAD,0.18,0.50) | clamp(median score−3 scaled MAD,0.15,0.45) |
| Confidence scaling | clip((score−floor)/max(median−floor,0.05),0,1) | Same |
| Other confidence rule | Gap >max(1.5,0.003 diagonal) and score difference <0.05 -> zero | Fewer than 64 finite score samples -> zero score |
| Initial unsupported fits | All seeds failing aborts required pair | Prepend one supported identity anchor; interpolate unusable fits between supported transforms or hold nearest, then anchor to first actual frame |
| Terminal confidence exception | Last clamp(ceil(0.04T),2,8) frames; zero confidence, agreeing references and jump ≥max(2.5,0.006D); both scores ≥0.18 OR matching preliminary jump; restore ≥10^-6, optionally preliminary pose | Not used |

**Tissue movement checks**

| Rule | Fixed settings/order |
|---|---|
| Pose metric / support | Centre translation and angle×half diagonal; confidence strictly >0 is reliable |
| Persistent jump | Up to six frames each side; median separation ≥max(2.5,0.006D); instantaneous step ≥0.55 threshold |
| Persistence/scatter | Gentle scale max(1.5,0.004D); near/far post-jump medians within max(gentle,0.30 separation); median scatter each side ≤max(gentle,0.35 separation); supported frame within three each side |
| Boundary ordering | Descending separation+instantaneous step, later tie; accepted boundaries >2 frames apart |
| Weak fill | Within segments: linear between supported positions, hold endpoints, all-weak segment median |
| Returning excursions | Spans 2–12; ≤3 passes; deviation >threshold and excess path >2 threshold; ascending span/start |
| Bright-tissue mask | Short side ≤512; P20–P99.8, floor 10^-6, threshold >0.22; erosion then dilation, square max(3,round(9 scale)); largest four-connected component ≥0.003 area |
| Centre jump candidates | ≥6 total frames; valid component in every frame; tail clamp(ceil(0.04T),2,8); ≥2 frames each side; blocks ≤4 |
| Centre event acceptance | Raw jump ≥max(0.08D,8 max(ordinary step,1)); corrected residual ≥0.02D; block scatter ≤0.02D; area ratio 0.40–2.50; largest residual/later tie |
| Ordinary step | Median of steps at or below their 80th percentile |
| Rigid coarse fit | Landmark features, thumbnail long side 192; angles ±max(15,requested degrees); target step max(0.25 degrees,degrees(0.75/thumbnail radius)); actual increment maximum/ceil(maximum/target step) |
| Rigid phase | Double precision, Hann window, common finite support ≥max(64,round(0.10 pixels)), spectrum floor 10^-12, integer real peak; not the emission seed |
| Rigid local fit | 26 shift/angle neighbours; steps 2,1,0.5,0.25,0.125 pixel-equivalent; ≤4 repeats each |
| Landmark candidate | Adjacent 128-long-side phase response <min(0.20,0.35 median responses) |
| Landmark event acceptance | ≥3 frames each side, median blocks ≤8; adjacent/block shift difference ≤0.01D, angle ≤1 degree; size ≥0.005D |
| Bright/dim terminal chain | Preliminary large steps threshold max(0.04D,8 max(ordinary,1)); last clamp(ceil(0.04T),4,8) region; final contiguous group ≤3 large boundaries, ≥2 later frames and persistent displacement |
| Terminal verification | All remaining adjacent responses ≥0.15; large measured events ≥0.70 threshold, preliminary shift disagreement ≤0.04D, direction cosine ≥0.75; other steps below threshold; any failure rejects chain |
| Event output | Replace the boundary increment through ordered composition; apply to later transforms |

**Execution and export**

| Setting | Value/distinction | Effect |
|---|---|---|
| Execution baseline | One worker per stage, reuse switches off | Baseline timing |
| Implementation reuse | Workers 1–16, cached spectra, pair buffers, smoothing reuse, exact median selection | Intended same output; must verify equality |
| Biological isolation | Original arithmetic separate from tissue compatibility arithmetic | Necessary implementation identity |
| Native review sampling | Bilinear, no crop, original datatype, outside fill zero | Exported pixels |
| Integer conversion | floor(value+0.5), clip to datatype; direct copy for whole-pixel shift | Exported pixels |
| Other main exports | Nearest-neighbour, bicubic, Fourier | Different pixels; not extra registration recipes |
| Biological guide | Common crop, 2×2 means, population standardisation, Hann, phase normalisation/upsample 10, median later-frame displacement×2 | Approximate guide only |
| Missing guide / failed fit | Remain missing/failed | Must not appear as zero error |
| Review rendering | Reduced, contrast-adjusted tiles with actual recipe, source time and failure state | Display, not measurement values |
| Main multichannel output | Can apply one guide's transform to all original layers; these experiments separate channels | Different experimental scope |

### Naming and outputs

| Label/field | Meaning |
|---|---|
| <code>RIPR &#124; Moving cells</code> | Relative-Intensity Pattern Registration, original biological Recommended recipe |
| <code>RIPR &#124; Bright/dim</code> | Relative-Intensity Pattern Registration, two-reference tissue recipe |
| <code>RIPR &#124; Landmarks</code> | Relative-Intensity Pattern Registration, edge/dark-reference recipe |
| <code>biological_foreground_recommended__dense_jumps__mutual_noise_gradient__tukey__multilag</code> | Exact biological recipe identifier; internal keys do not relabel acquisition modality |
| <code>bright_dim_references...__subtype</code> | Bright/dim, any guard suffix, and declared image subtype |
| <code>edge_dark_landmarks...__subtype</code> | Landmarks, any guard suffix, and subtype |
| <code>guide_residual_from_image1_px</code>, <code>median_guide_residual_px</code> | Historical biological frame/video guides, not known error |
| <code>metric_status=guide_only</code> | Explicit score limitation |
| <code>[registered]</code>, <code>_registered.tif</code> | Generic main-plugin title/batch naming, not montage naming |
| <code>FAILED</code> | Method failure, visibly retained |

| Artefact | Contents/boundary |
|---|---|
| Experimental result in memory | Actual route/recipe, image-type key, transforms, detection if used, estimation time |
| Biological nested result | Transforms, support, pair count, engine checksum |
| Tissue nested result | Preliminary/final transforms, raw trajectory, confidence, reference indices, persistent/rigid jumps, endpoint, stage times |
| Pair journal | Pair, seed, status, score and six matrix values; failed seeds retained |
| Native registered TIFF | Resampled original measurements |
| Review TIFF grid | Synchronous labelled display-only comparison |
| Index/source manifest | Recipe, source execution, original time, metric provenance and failures |
| Detector model | Frozen examples, recording groups, confidence rule and identity |

### Measured on the existing review delivery

No registration, speed benchmark or pixel-identity test was run for this explanation. Older two-matcher selector performance tables are not reused as evidence for the experimental three-route detector.

The delivery README, visual audit and index read earlier in this audit described **35 grids**, each with original, one Relative-Intensity Pattern Registration recipe and 12 external-method panels. Eighteen retained Bright/dim, six retained Landmarks, and eleven reused accepted historical biological Recommended output and original times as Moving cells. This was not 35 fresh registrations or an automatic-detector accuracy test. The former sibling delivery folder was no longer present at final link verification; these are previously inspected provenance records, not currently verified output-file locations.

The [biological arithmetic decision](decisions/biological-recommended-arithmetic.md) records isolation and retained-model decisions. The former external tuning-folder location and original retained-model file were unavailable there during this audit; their full build environment and historical numerical files were not revalidated. Source settings were checked directly; historical results are attributed to their records.

### Verification before trusting a change

These are future acceptance checks, **not jobs run during this documentation task**. Experimental tests require their separate build/classpath; ordinary Maven does not include them automatically.

1. Verify the two companions' numbered headings, branch ownership, equations and source links.
2. Run the main Java test suite in the recorded environment. Core groups cover log planes, pair recovery/support, robust norms, reconciliation and unsupported-frame repair.
3. In the experimental build, verify biological resource checksum/private isolation, all cumulative transforms and native pixels against the accepted original engine. Parameter equality is insufficient.
4. Check tissue feature arrays, reference masks, movements, confidence, events and native pixels against accepted baselines. Source tests include LongitudinalReferenceLandmarkTest, LongitudinalReferenceFallbackTest, LongitudinalReferenceWarperTest and LongitudinalStripedGaussianTest.
5. For speed-only changes, prove identical intermediates, transforms, native pixels and failures before timing. Record awake/plugged-in conditions, frame count, dimensions, workers and native-library version.
6. Freeze detector examples and split by source recording, not channel. Report abstentions and wrong decisions. Test held-out recordings rather than the tuning gallery.
7. Preserve real-video native outputs and synchronous external review; guide scores remain approximate. Known-motion synthetic testing requires a genuinely common starting image or independently known motion, not unknown real motion plus a known added shift.
8. Cached remakes retain source execution/times and explicit failures. Verify actual recipe labels and complete ImageJ stack readability.

## References

References credit established ingredients, not publication of the exact project recipe. Attribution was checked in primary papers, author-hosted records or official documentation; none was inferred from a filename. Abstract/bibliographic access establishes attribution, not line-by-line implementation identity.

1. Burt PJ, Adelson EH (1983). The Laplacian pyramid as a compact image code. *IEEE Transactions on Communications* 31(4):532–540. [Paper](https://www.rctn.org/bruno/public/papers/Laplacian-pyramid-Burt%2BAdelson1983.pdf). Cited for: multiscale smoothing/reduction, step 5, equation 5; not bound padding.
2. Lucas BD, Kanade T (1981). An iterative image registration technique with an application to stereo vision. *Proceedings of IJCAI*:674–679. [Institution-hosted paper](https://publications.ri.cmu.edu/storage/publications/pub_files/pub3/lucas_bruce_d_1981_1/lucas_bruce_d_1981_1.pdf). Cited for: gradient-based refinement, step 7, equation 7.
3. Beaton AE, Tukey JW (1974). The fitting of power series, meaning polynomials, illustrated on band-spectroscopic data. *Technometrics* 16(2):147–185. [Publisher record](https://www.tandfonline.com/doi/abs/10.1080/00401706.1974.10489171). Cited for: biweight fitting, step 7, equation 7; not biological support thresholds.
4. Holland PW, Welsch RE (1977). Robust regression using iteratively reweighted least-squares. *Communications in Statistics—Theory and Methods* 6(9):813–827. [Publisher record](https://www.tandfonline.com/doi/abs/10.1080/03610927708827533). Cited for: repeated residual-based weighting, step 7, equation 7.
5. Huber PJ (1964). Robust estimation of a location parameter. *Annals of Mathematical Statistics* 35(1):73–101. [DOI](https://doi.org/10.1214/aoms/1177703732). Cited for: optional Huber fitting, step 7/Configuration, not biological Tukey. Publisher full text was not readable in this audit; the primary robust-regression source above also supports attribution of this established family.
6. Evangelidis GD, Psarakis EZ (2008). Parametric image alignment using enhanced correlation coefficient maximization. *IEEE Transactions on Pattern Analysis and Machine Intelligence* 30(10):1858–1865. [Author-posted paper](https://www.researchgate.net/profile/Emmanouil-Psarakis/publication/23171525_Parametric_Image_Alignment_Using_Enhanced_Correlation_Coefficient_Maximization/links/09e41512d0d8ea67c8000000/Parametric-Image-Alignment-Using-Enhanced-Correlation-Coefficient-Maximization.pdf). DOI 10.1109/TPAMI.2008.113. Cited for: enhanced correlation coefficient fitting, steps 8 and 13, equations 8 and 13.
7. OpenCV contributors (documentation accessed 2026-09-09). [Object tracking: findTransformECC](https://docs.opencv.org/4.13.0/dc/d6b/group__video__track.html). Cited for: native fitting, motion models and termination, step 13. Documentation version does not establish which native binary an experiment used.
8. Guizar-Sicairos M, Thurman ST, Fienup JR (2008). Efficient subpixel image registration algorithms. *Optics Letters* 33(2):156–158. [Publisher record](https://opg.optica.org/ol/abstract.cfm?URI=ol-33-2-156). Cited for: local Fourier refinement and subpixel review alignment, steps 13 and 19; not seed ordering or confidence rules.
9. Unser M, Aldroubi A, Eden M (1993). B-spline signal processing: Part I—Theory. *IEEE Transactions on Signal Processing* 41(2):821–833. [Author-hosted record](https://bigwww.epfl.ch/publications/unser9301.html). Cited for: B-spline sampling, steps 8 and 13, equation 8.
10. Shi J, Tomasi C (1994). Good features to track. *Proceedings of CVPR*:593–600. [Institution-hosted record](https://publications.ri.cmu.edu/good-features-to-track). Cited for: two-direction information, optional step 9, equation 9; not the Landmarks feature composition.
11. Wang Y, Schnitzbauer J, Hu Z, Li X, Cheng Y, Huang Z-L, Huang B (2014). Localization events-based sample drift correction for localization microscopy with redundant cross-correlation algorithm. *Optics Express* 22(13):15982–15991. [Full paper](https://pmc.ncbi.nlm.nih.gov/articles/PMC4162368/). Cited for: redundant temporal constraints, steps 4 and 10, equations 4 and 10; not log-ratio matching.
12. NIST/SEMATECH (accessed 2026-09-09). Measures of scale. *e-Handbook of Statistical Methods*. [Official handbook](https://www.itl.nist.gov/div898/handbook/eda/section3/eda356.htm). Cited for: medians, median absolute deviation and interquartile range, steps 2, 7 and 11–14; not project floors and gates.
13. OpenCV contributors (accessed 2026-09-09). [Image filtering](https://docs.opencv.org/4.13.0/d4/d86/group__imgproc__filter.html). Cited for: standard Gaussian/median filtering, derivatives, erosion and dilation, steps 3, 11, 12 and 15; not a claim every implementation calls OpenCV.
14. OpenCV contributors (accessed 2026-09-09). [Geometric image transformations](https://docs.opencv.org/4.13.0/da/d54/group__imgproc__transform.html). Cited for: standard resampling/interpolation, steps 1–3 and 17–18; exact rounding comes from Java source.
15. OpenCV contributors (accessed 2026-09-09). [Template matching](https://docs.opencv.org/4.13.0/df/dfb/group__imgproc__object.html). Cited for: centred normalised-correlation arithmetic, steps 8 and 13, equation 8; not invention of correlation.
16. SciPy contributors (accessed 2026-09-09). [Hann window](https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.windows.hann.html). Cited for: the established edge-tapering window, steps 15 and 19, equation 19; reading these definitions did not execute Python.
17. Woods RP, Cherry SR, Mazziotta JC (1992). Rapid automated algorithm for aligning and reslicing PET images. *Journal of Computer Assisted Tomography* 16(4):620–633. [Primary abstract](https://pubmed.ncbi.nlm.nih.gov/1629424/). Cited for: historical ratio-uniformity registration, step 6; not the exact median-centred robust log-domain recipe.

**Methods with no published source — original to this project's implementation:** exact three-route composition; grouped-example confidence policy; original-engine isolation; padded search-bound rule; median-gain log-ratio combination with mutual-noise-gradient support; optional mask/refit composition; feature weights/reference-selection rules; two-reference path choice and confidence exceptions; persistent-jump/returning-excursion cleanup; centroid/rigid event acceptance; review-guide aggregation/provenance.

“Original to this project” identifies where these particular rules were defined, **not** a literature-search finding of fundamental novelty. Standard percentiles, medians, logarithms, gradients, least squares, morphology, connected components, centroids, Fourier transforms, windows and interpolation are not claimed as inventions. Optional manual capabilities are not silently added to the manuscript's active recipes.
