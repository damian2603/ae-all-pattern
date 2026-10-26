package io.github.langqi99.aeallpattern.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AggregateGenerationServiceTest {
    @Test
    void emptyViewerStartupScanMustNotErasePersistedPackagedRecipes() {
        assertTrue(AggregateGenerationService.shouldPreserveOnEmptyRefresh(true, 152, 0));
        assertTrue(AggregateGenerationService.shouldPreserveOnEmptyRefresh(true, 1, 0));
    }

    @Test
    void validCatalogReplacementRemainsPermitted() {
        assertFalse(AggregateGenerationService.shouldPreserveOnEmptyRefresh(true, 152, 150));
        assertFalse(AggregateGenerationService.shouldPreserveOnEmptyRefresh(false, 152, 0));
        assertFalse(AggregateGenerationService.shouldPreserveOnEmptyRefresh(true, 0, 0));
    }
}
