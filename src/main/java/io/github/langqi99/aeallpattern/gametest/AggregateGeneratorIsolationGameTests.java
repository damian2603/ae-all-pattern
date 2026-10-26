package io.github.langqi99.aeallpattern.gametest;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import io.github.langqi99.aeallpattern.AeAllPattern;
import io.github.langqi99.aeallpattern.aggregate.AggregateCatalogMutationService;
import io.github.langqi99.aeallpattern.aggregate.AggregatePatternKind;
import io.github.langqi99.aeallpattern.aggregate.AggregateProviderRefreshService;
import io.github.langqi99.aeallpattern.aggregate.AggregateRecipe;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.GameTest;
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

    @GameTest(batch = "provider_compat", template = "empty", timeoutTicks = 110)
    public static void generatingDifferentMachineDoesNotRefreshAnyExistingProvider(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        Object existingProvider = new Object();
        AtomicInteger refreshes = new AtomicInteger();
        AggregateProviderRefreshService.track(server, existingProvider,
                owner -> refreshes.incrementAndGet(),
                null, (owner, libraryId) -> true);
        // Both the JEI upload and the server-side generator call this method.
        AggregateCatalogMutationService.put(
                server, uniqueMachine(), "block.minecraft.crafting_table", testRecipes("new-machine"));

        helper.runAfterDelay(3, () -> {
            helper.assertTrue(refreshes.get() == 0,
                    "Generating an unrelated Aggregate Pattern reindexed another provider");
            helper.succeed();
        });
    }

    @GameTest(batch = "provider_compat", template = "empty", timeoutTicks = 110)
    public static void regeneratingExistingMachineRefreshesOnlyReferencingProvider(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ResourceLocation machine = uniqueMachine();
        UUID libraryId = AggregateCatalogMutationService.put(
                server, machine, "block.minecraft.crafting_table", testRecipes("initial"))
                .libraryId();
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
        helper.runAfterDelay(3, () -> {
            helper.assertTrue(matchingRefreshes.get() == 1,
                    "Existing library was not re-indexed in the provider holding it");
            helper.assertTrue(unrelatedRefreshes.get() == 0,
                    "Existing library refreshed unrelated provider");
            helper.succeed();
        });
    }
}
