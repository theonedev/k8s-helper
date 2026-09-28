package io.onedev.k8shelper;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class JobHelperTest {
    @Test
    void markerContainsOnlyStepEventData() {
        var marker = JobHelper.buildStepEndMessage(java.util.List.of(2, 1), JobHelper.StepEventKind.FAILED);
        var entry = JobHelper.parseStepEventMessage(marker);
        assertEquals("step-2-1", entry.step());
        assertEquals("##onedev-step:FAILED:step-2-1", marker);
        assertEquals(JobHelper.StepEventKind.FAILED, entry.kind());
        assertNull(JobHelper.parseStepEventMessage("Step failed"));
        for (var invalid : java.util.List.of("FAILED:!", "FAILED:step-00", "FAILED:step-99999999999999",
                "START:../log", "SKIP:initialization", "SKIP:finalization", "FINISH:step-0", "SUCCESSFUL:initialization", "FAILED:finalization", "CANCELLED:initialization",
                "FAILED:step-0:FAILED", "END:step-0", "START"))
            assertNull(JobHelper.parseStepEventMessage(JobHelper.STEP_EVENT_PREFIX + invalid), invalid);
        assertNull(JobHelper.parseStepEventMessage(JobHelper.STEP_EVENT_PREFIX + "FINISH"));
    }
}
