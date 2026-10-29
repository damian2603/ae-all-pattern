package io.github.langqi99.aeallpattern.machine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PackagedCraftingAdapterTest {
    @Test
    void tieredCrafterSupportsOnlyItsOwnTierRecipes() {
        assertTrue(PackagedCraftingAdapter.supportsTier(1, 1));
        assertTrue(PackagedCraftingAdapter.supportsTier(2, 2));
        assertTrue(PackagedCraftingAdapter.supportsTier(3, 3));
        assertTrue(PackagedCraftingAdapter.supportsTier(4, 4));

        assertFalse(PackagedCraftingAdapter.supportsTier(4, 1));
        assertFalse(PackagedCraftingAdapter.supportsTier(4, 2));
        assertFalse(PackagedCraftingAdapter.supportsTier(4, 3));
        assertFalse(PackagedCraftingAdapter.supportsTier(3, 4));
    }
}
