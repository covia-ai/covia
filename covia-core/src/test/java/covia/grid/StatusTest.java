package covia.grid;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import covia.api.Fields;

/**
 * Test class for Status functionality
 */
public class StatusTest {

    @Test
    public void testNewStatusConstants() {
        // Test that new status constants are defined
        assertNotNull(Status.REJECTED);
        assertNotNull(Status.INPUT_REQUIRED);
        assertNotNull(Status.AUTH_REQUIRED);
        assertNotNull(Status.PAUSED);
        
        assertEquals("REJECTED", Status.REJECTED.toString());
        assertEquals("INPUT_REQUIRED", Status.INPUT_REQUIRED.toString());
        assertEquals("AUTH_REQUIRED", Status.AUTH_REQUIRED.toString());
        assertEquals("PAUSED", Status.PAUSED.toString());
    }
    
    @Test
    public void testRejectedIsFinished() {
        // Test that REJECTED is considered a finished status
        AMap<AString, ACell> jobData = Maps.of(
            Fields.JOB_STATUS_FIELD, Status.REJECTED
        );
        
        assertTrue(Job.isFinished(jobData), "REJECTED should be considered finished");
    }
    
    @Test
    public void testInputRequiredIsNotFinished() {
        // Test that INPUT_REQUIRED is NOT considered finished
        AMap<AString, ACell> jobData = Maps.of(
            Fields.JOB_STATUS_FIELD, Status.INPUT_REQUIRED
        );
        
        assertFalse(Job.isFinished(jobData), "INPUT_REQUIRED should NOT be considered finished");
    }
    
    @Test
    public void testAuthRequiredIsNotFinished() {
        // Test that AUTH_REQUIRED is NOT considered finished
        AMap<AString, ACell> jobData = Maps.of(
            Fields.JOB_STATUS_FIELD, Status.AUTH_REQUIRED
        );
        
        assertFalse(Job.isFinished(jobData), "AUTH_REQUIRED should NOT be considered finished");
    }
    
    @Test
    public void testPausedIsNotFinished() {
        // Test that PAUSED is NOT considered finished
        AMap<AString, ACell> jobData = Maps.of(
            Fields.JOB_STATUS_FIELD, Status.PAUSED
        );
        
        assertFalse(Job.isFinished(jobData), "PAUSED should NOT be considered finished");
    }
    
    @Test
    public void testIsPausedMethod() {
        // Test that isPaused correctly identifies paused statuses
        Job pausedJob = Job.paused("Temporarily paused");
        assertTrue(pausedJob.isPaused(), "PAUSED job should be considered paused");
        
        Job inputRequiredJob = Job.create(Maps.of(Fields.JOB_STATUS_FIELD, Status.INPUT_REQUIRED));
        assertTrue(inputRequiredJob.isPaused(), "INPUT_REQUIRED job should be considered paused");
        
        Job authRequiredJob = Job.create(Maps.of(Fields.JOB_STATUS_FIELD, Status.AUTH_REQUIRED));
        assertTrue(authRequiredJob.isPaused(), "AUTH_REQUIRED job should be considered paused");
        
        Job completeJob = Job.create(Maps.of(Fields.JOB_STATUS_FIELD, Status.COMPLETE));
        assertFalse(completeJob.isPaused(), "COMPLETE job should NOT be considered paused");
        
        Job failedJob = Job.failure("Test failure");
        assertFalse(failedJob.isPaused(), "FAILED job should NOT be considered paused");
    }
    
    @Test
    public void testRejectedJobErrorMessage() {
        // Test that rejected jobs return error messages
        Job rejectedJob = Job.rejected("Access denied");
        
        assertTrue(rejectedJob.isFinished());
        assertFalse(rejectedJob.isComplete());
        assertEquals("Access denied", rejectedJob.getErrorMessage());
    }
    
    @Test
    public void testStatusHelperMethods() {
        // Test the new helper methods for creating status responses
        AMap<AString, ACell> rejected = Status.rejected("Request rejected");
        assertEquals(Status.REJECTED, rejected.get(Fields.STATUS));
        assertEquals("Request rejected", rejected.get(Fields.MESSAGE).toString());
        
        AMap<AString, ACell> inputRequired = Status.inputRequired("Please provide input");
        assertEquals(Status.INPUT_REQUIRED, inputRequired.get(Fields.STATUS));
        assertEquals("Please provide input", inputRequired.get(Fields.MESSAGE).toString());
        
        AMap<AString, ACell> authRequired = Status.authRequired("Authentication required");
        assertEquals(Status.AUTH_REQUIRED, authRequired.get(Fields.STATUS));
        assertEquals("Authentication required", authRequired.get(Fields.MESSAGE).toString());
        
        AMap<AString, ACell> paused = Status.paused("Job paused for maintenance");
        assertEquals(Status.PAUSED, paused.get(Fields.STATUS));
        assertEquals("Job paused for maintenance", paused.get(Fields.MESSAGE).toString());
    }
}
