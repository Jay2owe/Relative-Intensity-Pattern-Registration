/* Copyright (c) 2026 Jamie Malcolm. Released under the BSD 3-Clause License. */
package ripr;

import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import ripr.api.*;
import ripr.core.*;
import java.util.Locale;
import java.util.concurrent.CancellationException;

/** Small explicit-recipe dialog for the accepted native longitudinal builds. */
public final class AcceptedLongitudinalPlugin implements PlugIn {
    public static final String COMMAND="RIPR Longitudinal (accepted recipes)...";
    public void run(String arg) {
        ImagePlus image=WindowManager.getCurrentImage();
        if(image==null){IJ.noImage();return;}
        try {
            String recipe="bright_dim";int channel=Math.max(1,image.getC());boolean crop=true;
            String options=Macro.getOptions();
            if(options!=null) {
                recipe=Macro.getValue(options,"recipe",recipe).toLowerCase(Locale.ROOT);
                channel=Integer.parseInt(Macro.getValue(options,"channel",Integer.toString(channel)));
                crop=Boolean.parseBoolean(Macro.getValue(options,"crop","true"));
            } else {
                GenericDialog dialog=new GenericDialog("RIPR Longitudinal: accepted recipes");
                dialog.addChoice("Recipe",new String[]{"Bright/dim", "Landmarks", "Moving cells"},"Bright/dim");
                String[] channels=new String[Math.max(1,image.getNChannels())];for(int i=0;i<channels.length;i++)channels[i]=Integer.toString(i+1);
                dialog.addChoice("Channel used to estimate movement",channels,Integer.toString(channel));
                dialog.addCheckbox("Crop to common real pixels",true);
                dialog.addMessage("Explicit recipes; no automatic longitudinal router.\n"
                        +"64-bit Windows and Java 25+ are required.\n"
                        +"Weak evidence or recovery is logged; review before analysis.");
                dialog.showDialog();if(dialog.wasCanceled())return;
                String[] recipes={"bright_dim","landmarks","moving_cells"};recipe=recipes[dialog.getNextChoiceIndex()];
                channel=dialog.getNextChoiceIndex()+1;crop=dialog.getNextBoolean();
                if(Recorder.record){Recorder.setCommand(COMMAND);Recorder.recordOption("recipe",recipe);
                    Recorder.recordOption("channel",Integer.toString(channel));Recorder.recordOption("crop",Boolean.toString(crop));Recorder.saveCommand();}
            }
            RelativeIntensityPatternParameters p=parameters(recipe,channel,crop);
            IJ.resetEscape();IJ.showStatus("RIPR: accepted "+recipe+" Java estimation...");
            RelativeIntensityPatternResult result=RelativeIntensityPatternRegistration.register(image,p,
                    (done,total)->IJ.showProgress(done,total),()->IJ.escapePressed());
            IJ.log("RIPR executed: "+result.parameters().recipeProvenance);
            for(Registration.Warning warning:result.registration().warnings)IJ.log("RIPR WARNING: "+warning.message);
            result.correctedImage().show();IJ.showProgress(1.0);
        } catch(CancellationException cancelled) {IJ.showStatus("RIPR longitudinal registration cancelled");}
        catch(Exception failure) {IJ.error("RIPR longitudinal registration FAILED",failure.getMessage());}
    }
    static RelativeIntensityPatternParameters parameters(String recipe,int channel,boolean crop) {
        if(!recipe.equals("bright_dim")&&!recipe.equals("landmarks")&&!recipe.equals("moving_cells"))throw new IllegalArgumentException("Choose bright_dim, landmarks or moving_cells explicitly");
        return RelativeIntensityPatternParameters.builder().recommendation(recipe.equals("landmarks")?ImageType.BRIGHTFIELD_DIC:ImageType.DENSE_FLUORESCENCE,
                MotionType.INTERMITTENT_JUMPS).selectionMode(recipe.equals("moving_cells")?SelectionMode.ACCEPTED_MOVING_CELLS:SelectionMode.ACCEPTED_LONGITUDINAL)
                .channel(channel).slice(0).crop(crop).interpolation(Warper.Interpolation.NONE).rotationMode(RotationMode.OFF).build();
    }
}
