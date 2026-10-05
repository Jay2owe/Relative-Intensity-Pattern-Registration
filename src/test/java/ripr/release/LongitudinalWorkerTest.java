package ripr.release;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class LongitudinalWorkerTest {
    @Test public void restoresOriginalBenchmarkWorkersOutsideBrightDim() {
        assertEquals(16,LongitudinalWorker.executionWorkers("moving_cells"));
        assertEquals(16,LongitudinalWorker.executionWorkers("landmarks"));
    }
    @Test public void onlyBrightDimUsesAcceptedHardwareCap() {
        assertEquals(Math.max(1,Math.min(16,Runtime.getRuntime().availableProcessors())),
                LongitudinalWorker.executionWorkers("bright_dim"));
    }
}
