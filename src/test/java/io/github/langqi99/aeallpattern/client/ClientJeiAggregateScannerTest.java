package io.github.langqi99.aeallpattern.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClientJeiAggregateScannerTest {
    @Test
    void logoutResetClearsQueuedUploads() {
        // The queue is private; this test only guards the public reset contract used by
        // ClientEvents on logout. It must be safe to call repeatedly and leave no busy state.
        ClientJeiAggregateScanner.reset();
        ClientJeiAggregateScanner.reset();
        assertEquals(0, ClientJeiAggregateScanner.pendingUploadCount());
        assertEquals(false, ClientJeiAggregateScanner.isBusy());
    }
}
