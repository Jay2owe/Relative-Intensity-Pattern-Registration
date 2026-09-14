/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import ij.ImagePlus;
import ripr.StackFrames;
import ripr.api.ImageType;
import ripr.api.MotionType;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.SelectionMode;
import ripr.api.LongitudinalVideoDetector;

/** Opt-in three-route entry point. Existing longitudinal entry points are unchanged. */
public final class LongitudinalVideoRouting {
    private LongitudinalVideoRouting() { }

    public enum Route {
        BRIGHT_DIM_TISSUE("Bright/dim tissue", "RIPR | Bright/dim"),
        TISSUE_LANDMARKS("Tissue landmarks", "RIPR | Landmarks"),
        BIOLOGICAL_MOVING_CELLS("Biological moving cells (Recommended)", "RIPR | Moving cells");
        public final String label, reviewLabel;
        Route(String label,String reviewLabel){this.label=label;this.reviewLabel=reviewLabel;}
    }

    public static final class Result {
        public final Route route;
        public final String recipeId;
        public final String reviewLabel;
        public final ImageType imageType;
        public final Transform[] transforms;
        public final BiologicalRecommendedRegistration.Result biological;
        public final LongitudinalReferenceRegistration.Outcome longitudinal;
        public final LongitudinalVideoDetector.Decision detection;
        public final double estimationSeconds;
        Result(Route route,ImageType type,BiologicalRecommendedRegistration.Result biological,
               LongitudinalReferenceRegistration.Outcome longitudinal,
               LongitudinalVideoDetector.Decision detection,double seconds){
            this.route=route;imageType=type;this.biological=biological;this.longitudinal=longitudinal;
            this.detection=detection;estimationSeconds=seconds;
            transforms=(biological!=null?biological.cumulative:longitudinal.transforms).clone();
            recipeId=biological!=null?"biological_foreground_recommended__dense_jumps__mutual_noise_gradient__tukey__multilag"
                    :longitudinal.route+"__"+type.name().toLowerCase(java.util.Locale.ROOT);
            reviewLabel=RegistrationReviewLabels.forRecipe(recipeId);
        }
    }

    /** Explicit override: bypasses detection entirely. No later refinement of Recommended. */
    public static Result estimate(ImagePlus image,Route route,ImageType type,MotionType motion,
                                  LongitudinalExecutionPolicy execution){
        return estimate(image,route,type,motion,execution,null);
    }

    /** Uncertain detection refuses to silently choose a recipe. Use estimate for a manual override. */
    public static Result estimateAutomatic(ImagePlus image,MotionType motion,
            LongitudinalExecutionPolicy execution,LongitudinalVideoDetector.Model model){
        checkImage(image);
        LongitudinalVideoDetector.Decision decision=LongitudinalVideoDetector.detect(StackFrames.of(image,1,1),model);
        if(!decision.confident)throw new IllegalArgumentException("Video recipe uncertain: "+decision.reason+"; choose a route explicitly");
        return estimate(image,routeFor(decision.recipeKey),typeFor(decision.recipeKey),motion,execution,decision);
    }

    private static Result estimate(ImagePlus image,Route route,ImageType type,MotionType motion,
            LongitudinalExecutionPolicy execution,LongitudinalVideoDetector.Decision detection){
        checkImage(image);
        if(route==null||type==null||motion==null||execution==null)throw new IllegalArgumentException("Route, image type, motion and execution policy are required");
        boolean transmitted=type==ImageType.PHASE_CONTRAST||type==ImageType.BRIGHTFIELD_DIC;
        if(route!=Route.BIOLOGICAL_MOVING_CELLS && (route==Route.TISSUE_LANDMARKS)!=transmitted)
            throw new IllegalArgumentException("The supplied image subtype does not match the chosen route");
        long start=System.nanoTime();
        if(route==Route.BIOLOGICAL_MOVING_CELLS){
            BiologicalRecommendedRegistration.Result result=BiologicalRecommendedRegistration.estimate(image,execution.preliminaryWorkers);
            return new Result(route,ImageType.DENSE_FLUORESCENCE,result,null,detection,(System.nanoTime()-start)/1e9);
        }
        LongitudinalReferenceRegistration.Outcome result=LongitudinalReferenceRegistration.estimate(image,type,motion,execution);
        return new Result(route,type,null,result,detection,(System.nanoTime()-start)/1e9);
    }

    /**
     * Declared settings for inspection and diagnostics, not the biological execution path.
     * Passing these to the longitudinal compatibility engine reproduces the old mismatch:
     * biological execution must use BiologicalRecommendedRegistration's isolated engine.
     */
    public static RelativeIntensityPatternParameters biologicalParameters(int workers){
        return RelativeIntensityPatternParameters.builder()
                .recommendation(ImageType.DENSE_FLUORESCENCE,MotionType.INTERMITTENT_JUMPS)
                .selectionMode(SelectionMode.RECOMMENDED).rotationMode(RotationMode.OFF)
                .crop(false).interpolation(Warper.Interpolation.NONE).threads(workers).build();
    }
    public static Route routeFor(String key){
        if(key.equals("BIOLOGICAL_MOVING_CELLS"))return Route.BIOLOGICAL_MOVING_CELLS;
        ImageType type=ImageType.valueOf(key);
        return type==ImageType.PHASE_CONTRAST||type==ImageType.BRIGHTFIELD_DIC?Route.TISSUE_LANDMARKS:Route.BRIGHT_DIM_TISSUE;
    }
    public static ImageType typeFor(String key){return key.equals("BIOLOGICAL_MOVING_CELLS")?ImageType.DENSE_FLUORESCENCE:ImageType.valueOf(key);}
    private static void checkImage(ImagePlus image){
        if(image==null||image.getNChannels()!=1||image.getStackSize()<2||image.getNSlices()>1&&image.getNFrames()>1)
            throw new IllegalArgumentException("A single-channel two-dimensional time stack is required");
    }
}
