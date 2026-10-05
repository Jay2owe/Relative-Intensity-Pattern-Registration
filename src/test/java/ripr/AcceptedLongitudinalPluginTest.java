package ripr;
import org.junit.Test;
import static org.junit.Assert.*;
import ripr.api.*;
import ripr.core.*;

public class AcceptedLongitudinalPluginTest {
    @Test public void explicitModesDoNotSelectAutomatic() {
        assertEquals(SelectionMode.ACCEPTED_LONGITUDINAL,AcceptedLongitudinalPlugin.parameters("bright_dim",1,true).selectionMode);
        assertEquals(SelectionMode.ACCEPTED_LONGITUDINAL,AcceptedLongitudinalPlugin.parameters("landmarks",1,true).selectionMode);
        assertEquals(SelectionMode.ACCEPTED_MOVING_CELLS,AcceptedLongitudinalPlugin.parameters("moving_cells",2,false).selectionMode);
        assertEquals(ImageType.DENSE_FLUORESCENCE,AcceptedLongitudinalPlugin.parameters("bright_dim",1,true).imageType);
        assertEquals(ImageType.BRIGHTFIELD_DIC,AcceptedLongitudinalPlugin.parameters("landmarks",1,true).imageType);
        assertFalse(AcceptedLongitudinalRegistration.selected(SelectionMode.AUTOMATIC));
    }
    @Test(expected=IllegalArgumentException.class) public void noGuessedRecipe() {
        AcceptedLongitudinalPlugin.parameters("automatic",1,true);
    }
    @Test public void macroTokensRoundTrip() {
        assertEquals(SelectionMode.ACCEPTED_LONGITUDINAL,SelectionMode.from("accepted_longitudinal"));
        assertEquals(SelectionMode.ACCEPTED_MOVING_CELLS,SelectionMode.from("accepted_moving_cells"));
    }
}
