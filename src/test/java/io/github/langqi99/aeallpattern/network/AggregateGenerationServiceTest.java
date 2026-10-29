package io.github.langqi99.aeallpattern.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AggregateGenerationServiceTest {
    @Test
    void emptyViewerStartupScanMustNotErasePersistedRecipes() {
        assertTrue(AggregateGenerationService.shouldPreserveOnIncompleteRefresh(
                3, List.of("iron", "gold", "diamond"), List.of(), true));
        assertTrue(AggregateGenerationService.shouldPreserveOnIncompleteRefresh(
                1, List.of("only-recipe"), List.of(), true));
    }

    @Test
    void equalOrLargerCompleteViewerScanMayUpdateCatalog() {
        List<String> saved = List.of("iron", "gold", "diamond");
        assertFalse(AggregateGenerationService.shouldPreserveOnIncompleteRefresh(
                3, saved, saved, true));
        assertFalse(AggregateGenerationService.shouldPreserveOnIncompleteRefresh(
                3, saved, List.of("iron", "gold", "diamond", "new"), true));
    }

    @Test
    void shorterNonemptyStartupScanMustPreserveSavedCatalog() {
        var saved = java.util.stream.IntStream.range(0, 2_000)
                .mapToObj(index -> "recipe-" + index).toList();
        var incoming = saved.subList(0, 1_900);
        assertTrue(AggregateGenerationService.shouldPreserveOnIncompleteRefresh(
                2_000, saved, incoming, true));
    }

    @Test
    void equalSizeScanWithMissingSavedIdentityIsAlsoIncomplete() {
        List<String> saved = List.of("iron", "gold", "diamond");
        List<String> replaced = List.of("iron", "gold", "new-recipe");
        assertTrue(AggregateGenerationService.shouldPreserveOnIncompleteRefresh(
                3, saved, replaced, true));
    }

    @Test
    void allOriginalIdsCanBeReorderedOrSupplemented() {
        List<String> saved = List.of("iron", "gold", "diamond");
        assertFalse(AggregateGenerationService.shouldPreserveOnIncompleteRefresh(
                3, saved, List.of("diamond", "iron", "gold"), true));
        assertFalse(AggregateGenerationService.shouldPreserveOnIncompleteRefresh(
                3, saved, List.of("diamond", "iron", "new", "gold"), true));
    }

    @Test
    void missingSavedPagesCannotBeOverwrittenByAClientScan() {
        assertTrue(AggregateGenerationService.shouldPreserveOnIncompleteRefresh(
                3, List.of(), List.of("a", "b", "c"), false));
        assertTrue(AggregateGenerationService.shouldPreserveOnIncompleteRefresh(
                3, List.of("a", "b"), List.of("a", "b", "c"), true));
    }

    @Test
    void newOrLegitimatelyEmptyCatalogIsNotLockedBySafetyGuard() {
        assertFalse(AggregateGenerationService.shouldPreserveOnIncompleteRefresh(
                0, List.of(), List.of(), true));
        assertFalse(AggregateGenerationService.shouldPreserveOnIncompleteRefresh(
                0, List.of(), List.of("new"), true));
        assertTrue(AggregateGenerationService.shouldPreserveOnIncompleteRefresh(
                3, List.of("a", "b", "c"), List.of(), true));
    }

}
