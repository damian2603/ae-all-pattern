package io.github.langqi99.aeallpattern.aggregate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import io.github.langqi99.aeallpattern.TestMinecraftBootstrap;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Preserve server-side AE terminal search semantics after tokenization optimization. */
class AggregatePatternSearchTest {
    @BeforeAll
    static void bootstrap() {
        TestMinecraftBootstrap.initialize();
    }

    private static AggregateRecipe recipe(
            String id, net.minecraft.world.item.Item input, net.minecraft.world.item.Item output) {
        return new AggregateRecipe(id, new ResourceLocation("aeallpattern", id),
                AggregatePatternKind.PROCESSING,
                List.of(new GenericStack(AEItemKey.of(input), 1)),
                List.of(new GenericStack(AEItemKey.of(output), 1)), 20);
    }

    @Test
    void repeatedTokensAndNegatedRegistryIdsKeepExistingMeaning() {
        var diamond = AEItemKey.of(Items.DIAMOND);
        assertTrue(AggregatePatternSearch.matchesStack(diamond, "@minecraft =diamond"));
        assertTrue(AggregatePatternSearch.matchesStack(diamond, "=diamond !=emerald"));
        assertFalse(AggregatePatternSearch.matchesStack(diamond, "=diamond !=diamond"));
        assertTrue(AggregatePatternSearch.matchesStack(diamond, "#minecraft:diamond"));
    }

    @Test
    void filteredCatalogPreservesInputOutputScopeAndLimit() {
        var diamond = recipe("gem", Items.OAK_LOG, Items.DIAMOND);
        var emerald = recipe("green", Items.DIRT, Items.EMERALD);
        var recipes = List.of(diamond, emerald);
        assertEquals(List.of(diamond),
                AggregatePatternSearch.filterRecipes(recipes, "=diamond", AggregateSearchMode.OUTPUTS, 8));
        assertEquals(List.of(diamond),
                AggregatePatternSearch.filterRecipes(recipes, "=oak_log", AggregateSearchMode.INPUTS, 8));
        assertEquals(List.of(),
                AggregatePatternSearch.filterRecipes(recipes, "=diamond", AggregateSearchMode.INPUTS, 8));
        assertEquals(List.of(diamond),
                AggregatePatternSearch.filterRecipes(recipes, "@minecraft =diamond",
                        AggregateSearchMode.ALL, 1));
        assertEquals(List.of(),
                AggregatePatternSearch.filterRecipes(recipes, "=diamond", AggregateSearchMode.ALL, 0));
    }
    @Test
    void boundedSearchPagePreservesTotalsAndPageOrder() {
        var first = recipe("first", Items.OAK_LOG, Items.DIAMOND);
        var other = recipe("other", Items.DIRT, Items.EMERALD);
        var last = recipe("last", Items.BIRCH_LOG, Items.DIAMOND);
        var all = List.of(first, other, last);

        var firstPage = AggregatePatternSearch.searchPage(
                all, "=diamond", AggregateSearchMode.OUTPUTS, 0, 1,
                id -> !id.equals("first"));
        assertEquals(List.of(first), firstPage.recipes());
        assertEquals(2, firstPage.totalMatches());
        assertEquals(1, firstPage.selectedMatches());
        assertEquals(0, firstPage.pageIndex());

        var secondPage = AggregatePatternSearch.searchPage(
                all, "=diamond", AggregateSearchMode.OUTPUTS, 1, 1,
                id -> !id.equals("first"));
        assertEquals(List.of(last), secondPage.recipes());
        assertEquals(1, secondPage.pageIndex());

        // When the user changes the query while on page 99, clamp to the
        // last matching page without losing the total or enabled counts.
        var clamped = AggregatePatternSearch.searchPage(
                all, "=diamond", AggregateSearchMode.OUTPUTS, 99, 1,
                id -> !id.equals("first"));
        assertEquals(List.of(last), clamped.recipes());
        assertEquals(1, clamped.pageIndex());
        assertEquals(2, clamped.totalMatches());
        assertEquals(1, clamped.selectedMatches());
    }

    @Test
    void blankSearchPreservesFullCatalogOrderAndEmptyResults() {
        var a = recipe("a", Items.OAK_LOG, Items.DIAMOND);
        var b = recipe("b", Items.DIRT, Items.EMERALD);
        var all = List.of(a, b);
        var page = AggregatePatternSearch.searchPage(
                all, " ", AggregateSearchMode.ALL, 1, 1, id -> true);
        assertEquals(List.of(b), page.recipes());
        assertEquals(2, page.totalMatches());
        assertEquals(2, page.selectedMatches());

        var none = AggregatePatternSearch.searchPage(
                all, "=nether_star", AggregateSearchMode.OUTPUTS, 100, 1, id -> true);
        assertEquals(List.of(), none.recipes());
        assertEquals(0, none.totalMatches());
        assertEquals(0, none.pageIndex());
    }

}
