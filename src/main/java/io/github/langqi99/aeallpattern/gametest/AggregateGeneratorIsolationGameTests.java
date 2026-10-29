package io.github.langqi99.aeallpattern.gametest;

import appeng.api.stacks.AEItemKey;
import appeng.api.crafting.IPatternDetails;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IManagedGridNode;
import appeng.api.stacks.GenericStack;
import io.github.langqi99.aeallpattern.AeAllPattern;
import io.github.langqi99.aeallpattern.aggregate.AggregateCatalogMutationService;
import io.github.langqi99.aeallpattern.aggregate.AggregatePatternKind;
import io.github.langqi99.aeallpattern.aggregate.AggregateProviderRefreshService;
import io.github.langqi99.aeallpattern.aggregate.AggregateRecipe;
import io.github.langqi99.aeallpattern.registry.ModItems;
import io.github.langqi99.aeallpattern.registry.ModDataComponents;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** A new generator scan must not silently unpublish unrelated AE2 patterns. */
@GameTestHolder(AeAllPattern.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AggregateGeneratorIsolationGameTests {
    private AggregateGeneratorIsolationGameTests() {
    }

    private static ResourceLocation uniqueMachine() {
        return new ResourceLocation("aeallpattern",
                "generator_isolation_" + UUID.randomUUID().toString().replace("-", ""));
    }

    private static List<AggregateRecipe> testRecipes(String name) {
        return List.of(new AggregateRecipe(name,
                new ResourceLocation("minecraft", "oak_planks"),
                AggregatePatternKind.CRAFTING,
                List.of(new GenericStack(AEItemKey.of(Items.OAK_LOG), 1)),
                List.of(new GenericStack(AEItemKey.of(Items.OAK_PLANKS), 4)), 1));
    }

    @GameTest(batch = "provider_compat", template = "empty", timeoutTicks = 180)
    public static void vanillaProviderKeepsRecipesDuringUnrelatedGeneratorScan(GameTestHelper helper) {
        assertRealProviderUnaffected(helper, new ResourceLocation("ae2", "pattern_provider"));
    }

    @GameTest(batch = "provider_compat", template = "empty", timeoutTicks = 180)
    public static void advancedProviderKeepsRecipesDuringUnrelatedGeneratorScan(GameTestHelper helper) {
        assertRealProviderUnaffected(helper, new ResourceLocation("advanced_ae", "adv_pattern_provider"));
    }

    private static void assertRealProviderUnaffected(GameTestHelper helper, ResourceLocation providerId) {
        var block = BuiltInRegistries.BLOCK.getOptional(providerId);
        if (block.isEmpty()) {
            helper.succeed();
            return;
        }
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, block.orElseThrow());
        helper.setBlock(pos.east(), appeng.core.definitions.AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.runAfterDelay(20, () -> {
            try {
                var level = helper.getLevel();
                var server = level.getServer();
                var entity = level.getBlockEntity(helper.absolutePos(pos));
                helper.assertTrue(entity != null, "ME provider block entity missing");
                Object logic = entity.getClass().getMethod("getLogic").invoke(entity);
                InternalInventory inv = (InternalInventory) logic.getClass()
                        .getMethod("getPatternInv").invoke(logic);
                var initial = AggregateCatalogMutationService.put(server, uniqueMachine(),
                        "block.minecraft.crafting_table", testRecipes("existing-machine"));
                ItemStack physical = new ItemStack(ModItems.AGGREGATE_PATTERN.get());
                ModDataComponents.setAggregatePattern(physical, initial);
                inv.setItemDirect(0, physical);
                helper.runAfterDelay(40, () -> {
                    try {
                        var node = (IManagedGridNode) entity.getClass()
                                .getMethod("getMainNode").invoke(entity);
                        @SuppressWarnings("unchecked")
                        var patterns = (List<IPatternDetails>) logic.getClass()
                                .getMethod("getAvailablePatterns").invoke(logic);
                        helper.assertTrue(node.isActive() && node.getGrid() != null && !patterns.isEmpty(),
                                "Provider did not publish the first aggregate");
                        IPatternDetails before = patterns.get(0);
                        helper.assertTrue(node.getGrid().getCraftingService()
                                        .getCraftingFor(before.getPrimaryOutput().what()).contains(before),
                                "Initial provider was not indexed");
                        AggregateCatalogMutationService.put(server, uniqueMachine(),
                                "block.minecraft.furnace", testRecipes("new-unrelated-machine"));
                        helper.runAfterDelay(5, () -> {
                            try {
                                @SuppressWarnings("unchecked")
                                var after = (List<IPatternDetails>) logic.getClass()
                                        .getMethod("getAvailablePatterns").invoke(logic);
                                helper.assertTrue(after.contains(before),
                                        "Unrelated generator cleared existing virtual pattern list");
                                helper.assertTrue(node.getGrid().getCraftingService()
                                                .getCraftingFor(before.getPrimaryOutput().what()).contains(before),
                                        "Unrelated generator removed existing ME crafting recipe");
                                helper.succeed();
                            } catch (ReflectiveOperationException error) {
                                throw new IllegalStateException(error);
                            }
                        });
                    } catch (ReflectiveOperationException error) {
                        throw new IllegalStateException(error);
                    }
                });
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
        });
    }

    @GameTest(batch = "provider_compat", template = "empty", timeoutTicks = 110)
    public static void generatingDifferentMachineDoesNotRefreshAnyExistingProvider(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        // Other GameTests in the same batch may have queued a global datapack
        // refresh. Drain it before tracking the fixture, then test atomically
        // on the server thread so concurrent tests cannot add a false positive.
        AggregateProviderRefreshService.tickServer(server);
        Object existingProvider = new Object();
        AtomicInteger refreshes = new AtomicInteger();
        AggregateProviderRefreshService.track(server, existingProvider,
                owner -> refreshes.incrementAndGet(),
                null, (owner, libraryId) -> true);
        // Both the JEI upload and the server-side generator call this method.
        AggregateCatalogMutationService.put(
                server, uniqueMachine(), "block.minecraft.crafting_table", testRecipes("new-machine"));

        AggregateProviderRefreshService.tickServer(server);
        helper.assertTrue(existingProvider != null, "Test owner was lost");
        helper.assertTrue(refreshes.get() == 0,
                "Generating an unrelated Aggregate Pattern reindexed another provider");
        helper.succeed();
    }

    @GameTest(batch = "provider_compat", template = "empty", timeoutTicks = 110)
    public static void legacyTrackedProviderWithoutPredicateStillRefreshesOnCatalogChange(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        AggregateProviderRefreshService.tickServer(server);
        Object legacyProvider = new Object();
        AtomicInteger refreshes = new AtomicInteger();
        // Older/addon integrations may only supply the 3-arg track overload. They must not
        // silently miss targeted catalog updates just because they lack a library predicate.
        AggregateProviderRefreshService.track(server, legacyProvider,
                owner -> refreshes.incrementAndGet());
        ResourceLocation machine = uniqueMachine();
        AggregateCatalogMutationService.put(
                server, machine, "block.minecraft.crafting_table", testRecipes("legacy-initial"));
        AggregateProviderRefreshService.tickServer(server);
        int baseline = refreshes.get();
        // A brand-new catalog cannot be referenced by an existing provider, so the mutation
        // service intentionally does not request a targeted refresh. Regenerating an existing
        // catalog is the path that must reach legacy hosts without a library predicate.
        AggregateCatalogMutationService.put(
                server, machine, "block.minecraft.crafting_table", testRecipes("legacy-updated"));
        AggregateProviderRefreshService.tickServer(server);
        helper.assertTrue(legacyProvider != null, "Legacy provider fixture was lost");
        helper.assertTrue(refreshes.get() == baseline + 1,
                "Legacy provider without containsLibrary missed a catalog mutation");
        helper.succeed();
    }

    @GameTest(batch = "provider_compat", template = "empty", timeoutTicks = 110)
    public static void regeneratingExistingMachineRefreshesOnlyReferencingProvider(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ResourceLocation machine = uniqueMachine();
        UUID libraryId = AggregateCatalogMutationService.put(
                server, machine, "block.minecraft.crafting_table", testRecipes("initial"))
                .libraryId();
        AggregateProviderRefreshService.tickServer(server);
        Object matchingProvider = new Object();
        Object unrelatedProvider = new Object();
        AtomicInteger matchingRefreshes = new AtomicInteger();
        AtomicInteger unrelatedRefreshes = new AtomicInteger();
        AggregateProviderRefreshService.track(server, matchingProvider,
                owner -> matchingRefreshes.incrementAndGet(),
                null, (owner, changed) -> changed.equals(libraryId));
        AggregateProviderRefreshService.track(server, unrelatedProvider,
                owner -> unrelatedRefreshes.incrementAndGet(),
                null, (owner, changed) -> false);

        var regenerated = AggregateCatalogMutationService.put(
                server, machine, "block.minecraft.crafting_table", testRecipes("updated"));
        helper.assertTrue(regenerated.libraryId().equals(libraryId),
                "Regeneration failed to preserve UUID used by physical Aggregate Patterns");
        AggregateProviderRefreshService.tickServer(server);
        helper.assertTrue(matchingProvider != null && unrelatedProvider != null,
                "Tracked provider fixture was lost");
        helper.assertTrue(matchingRefreshes.get() == 1,
                "Existing library was not re-indexed in the provider holding it");
        helper.assertTrue(unrelatedRefreshes.get() == 0,
                "Existing library refreshed unrelated provider");
        helper.succeed();
    }
}
