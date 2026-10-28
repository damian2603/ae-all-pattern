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
}
