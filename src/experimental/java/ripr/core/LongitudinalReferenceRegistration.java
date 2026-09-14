/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr.core;

import ij.ImagePlus;
import java.util.List;
import ripr.api.RelativeIntensityPatternRegistration;
import ripr.api.RelativeIntensityPatternParameters;
import ripr.api.ImageType;
import ripr.api.MotionType;
import ripr.api.SelectionMode;

/** Complete experimental single-channel route. Not used by the installed plugin. */
public final class LongitudinalReferenceRegistration {
    private LongitudinalReferenceRegistration() { }
    public static final class Outcome {
        public final Transform[] preliminary,transforms;
        public final double[][] rawTrajectory;
        public final double[] confidence;
        public final int bright,dim,endpoint;
        public final int[] persistentJumps,rigidJumps;
        public final String route;
        public final double preliminarySeconds,refinementSeconds;
        Outcome(Transform[] preliminary,Transform[] transforms,double[][] raw,double[] confidence,
                int bright,int dim,int endpoint,int[] jumps,int[] rigid,String route,double seconds,double refineSeconds) {
            this.preliminary=preliminary;this.transforms=transforms;this.rawTrajectory=raw;this.confidence=confidence;
            this.bright=bright;this.dim=dim;this.endpoint=endpoint;this.persistentJumps=jumps;this.rigidJumps=rigid;
            this.route=route;this.preliminarySeconds=seconds;this.refinementSeconds=refineSeconds;
        }
    }

    public static Outcome estimate(ImagePlus image,ImageType imageType,MotionType motion) {
        return estimate(image,imageType,motion,LongitudinalExecutionPolicy.BASELINE);
    }

    public static Outcome estimate(ImagePlus image,ImageType imageType,MotionType motion,LongitudinalExecutionPolicy execution) {
        if(execution==null)throw new IllegalArgumentException("Execution policy is required");
        if(image==null || image.getNChannels()!=1 || image.getStackSize()<2)
            throw new IllegalArgumentException("A single-channel time stack is required");
        RelativeIntensityPatternParameters p=RelativeIntensityPatternParameters.builder()
            .recommendation(imageType,motion).selectionMode(SelectionMode.AUTOMATIC).threads(execution.preliminaryWorkers).crop(false).build();
        // This frozen recipe is translation-led; it does not support continuous rotation.
        if(p.fitRotation)throw new IllegalArgumentException("Continuous rotation is outside this longitudinal route");
        long start=System.nanoTime();
        Registration.Result baseline=RelativeIntensityPatternRegistration.estimate(image,p,PairScheduler.Progress.NONE,PairScheduler.Cancellation.NEVER);
        double seconds=(System.nanoTime()-start)/1e9;start=System.nanoTime();
        int width=image.getWidth(),height=image.getHeight(),count=image.getStackSize();
        float[][] frames=new float[count][];
        for(int i=0;i<count;i++)frames[i]=(float[])image.getStack().getProcessor(i+1).convertToFloatProcessor().getPixels();
        boolean transmitted=imageType==ImageType.PHASE_CONTRAST || imageType==ImageType.BRIGHTFIELD_DIC;
        double[][] trajectory;double[] confidence;float[][] features=null;int bright=-1,dim=-1;
        String route;
        if(transmitted) {
            features=LongitudinalPreparedFrames.prepare(frames,width,height,false,execution.featureWorkers,execution.selectMedian);
            float[] reference=LongitudinalReferenceLandmarks.reference(features,baseline.cumulative,width,height);
            PairAligner.Options options=LongitudinalReferenceArea.options(width,height);int levels=options.levelsFor(width,height);
            LogPlane[] template=LogPlane.of(reference,width,height,1).pyramid(levels);
            Transform[] fits=new Transform[count];boolean[] usable=new boolean[count];
            final float[][] fitFeatures=features;
            LongitudinalOrderedFrames.run(count,execution.frameWorkers,false,i -> {
                PairAligner.Fit fit=LongitudinalReferenceArea.align(template,
                    LogPlane.of(fitFeatures[i],width,height,1).pyramid(levels),baseline.cumulative[i],options);
                fits[i]=fit.transform;usable[i]=fit.usable();
            });
            LongitudinalReferenceLandmarkTrajectory.Outcome fitted=LongitudinalReferenceLandmarkTrajectory.assemble(features,reference,fits,usable,width,height);
            trajectory=fitted.trajectory;confidence=fitted.confidence;route="edge_dark_landmarks";
        } else {
            double[][] initial=new double[count][3];
            for(int i=0;i<count;i++) {initial[i][0]=baseline.cumulative[i].dx;initial[i][1]=baseline.cumulative[i].dy;initial[i][2]=baseline.cumulative[i].theta;}
            OpenCvLongitudinalTrajectory.Outcome fitted=OpenCvLongitudinalTrajectory.estimate(frames,width,height,initial,execution);
            trajectory=fitted.trajectory;confidence=fitted.confidence;bright=fitted.bright;dim=fitted.dim;route="bright_dim_references";
        }
        double diagonal=Math.hypot(width,height),radius=.5*diagonal;
        LongitudinalReferenceTrajectoryRepair.Outcome repaired=LongitudinalReferenceTrajectoryRepair.repair(trajectory,confidence,diagonal);
        Transform[] transforms=new Transform[count];
        for(int i=0;i<count;i++)transforms[i]=new Transform(repaired.trajectory[i][0],repaired.trajectory[i][1],repaired.trajectory[i][2]/radius);
        List<LongitudinalReferenceRigid.Event> events;int endpoint=-1;
        if(transmitted) {
            events=LongitudinalReferenceRigid.transmitted(frames,features,width,height,10);
            transforms=LongitudinalReferenceRigid.apply(transforms,events);
            if(!events.isEmpty())route="edge_dark_landmarks_guarded_rigid_jump";
        } else {
            LongitudinalReferenceEndpoint.Result adjusted=LongitudinalReferenceEndpoint.repair(frames,transforms,width,height,execution.endpointWorkers);
            transforms=adjusted.transforms;endpoint=adjusted.boundary;
            events=LongitudinalReferenceRigid.terminal(frames,baseline.cumulative,width,height,10);
            transforms=LongitudinalReferenceRigid.apply(transforms,events);
            if(!events.isEmpty()) {route="bright_dim_references_guarded_terminal_rigid_chain";endpoint=events.get(0).boundary;}
        }
        int[] rigid=new int[events.size()];for(int i=0;i<rigid.length;i++)rigid[i]=events.get(i).boundary;
        return new Outcome(baseline.cumulative,transforms,trajectory,confidence,bright,dim,endpoint,repaired.jumps,rigid,route,seconds,(System.nanoTime()-start)/1e9);
    }
}
