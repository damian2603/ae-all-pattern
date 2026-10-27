package io.github.langqi99.aeallpattern.gametest;

import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import io.github.langqi99.aeallpattern.AeAllPattern;
import io.github.langqi99.aeallpattern.aggregate.AggregatePatternKind;
import io.github.langqi99.aeallpattern.aggregate.AggregateCatalogMutationService;
import io.github.langqi99.aeallpattern.aggregate.AggregatePatternLibrary;
import io.github.langqi99.aeallpattern.aggregate.AggregatePatternSelection;
import io.github.langqi99.aeallpattern.aggregate.AggregateProviderRefreshService;
import io.github.langqi99.aeallpattern.aggregate.AggregateRecipe;
import io.github.langqi99.aeallpattern.registry.ModDataComponents;
import io.github.langqi99.aeallpattern.registry.ModItems;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.IItemHandlerModifiable;

/** Optional real PackagedAuto provider regression: a catalog update requires no slot reinsertion. */
@GameTestHolder(AeAllPattern.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PackagedAggregateRecoveryGameTests {
    private PackagedAggregateRecoveryGameTests() {}

    private static ResourceLocation id(String name) { return new ResourceLocation(name); }

    private static ItemStack aggregate(GameTestHelper helper, boolean disableSecond) {
        var server = helper.getLevel().getServer();
        List<AggregateRecipe> recipes = List.of(
                new AggregateRecipe("packaged-regression-first",
                        id("aeallpattern:packaged_regression_first"), AggregatePatternKind.PROCESSING,
                        List.of(new GenericStack(AEItemKey.of(Items.REDSTONE), 1)),
                        List.of(new GenericStack(AEItemKey.of(Items.DIAMOND), 1)), 20),
                new AggregateRecipe("packaged-regression-second",
                        id("aeallpattern:packaged_regression_second"), AggregatePatternKind.PROCESSING,
                        List.of(new GenericStack(AEItemKey.of(Items.COAL), 1)),
                        List.of(new GenericStack(AEItemKey.of(Items.EMERALD), 1)), 20));
        var ref = AggregatePatternLibrary.get(server).put(server,
                id("aeallpattern:packaged_recovery_fixture_"
                        + java.util.UUID.randomUUID().toString().replace("-", "")),
                "aeallpattern.packaged_test", recipes);
        ItemStack stack = new ItemStack(ModItems.AGGREGATE_PATTERN.get());
        ModDataComponents.setAggregatePattern(stack, ref);
        if (disableSecond) {
            ModDataComponents.setAggregatePatternSelection(stack,
                    new AggregatePatternSelection(false, List.of("packaged-regression-second")));
        }
        return stack;
    }

    @GameTest(batch = "provider_compat", template = "empty", timeoutTicks = 140)
    public static void packagedProviderRepublishesAfterCatalogRefresh(GameTestHelper helper) {
        var block = BuiltInRegistries.BLOCK.getOptional(id("packagedauto:packaging_provider"));
        if (block.isEmpty()) { helper.succeed(); return; }
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, block.get());
        helper.setBlock(pos.east(), appeng.core.definitions.AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.runAfterDelay(12, () -> {
            try {
                BlockEntity entity = helper.getLevel().getBlockEntity(helper.absolutePos(pos));
                helper.assertTrue(entity instanceof ICraftingProvider, "PackagedAuto AE provider did not load");
                Object handler = entity.getClass().getMethod("getItemHandler").invoke(entity);
                helper.assertTrue(handler instanceof IItemHandlerModifiable, "Missing PackagedAuto pattern inventory");
                ((IItemHandlerModifiable) handler).setStackInSlot(0, aggregate(helper, false));
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) entity.getClass().getField("recipeList").get(entity);
                helper.assertTrue(list.size() == 2, "Aggregate was not installed in packaging provider");
                // Reproduce a reload where the host has cleared its cached recipes, but
                // the physical Aggregate Pattern in the slot remains untouched.
                list.clear();
                AggregateProviderRefreshService.requestRefresh(helper.getLevel().getServer());
                helper.runAfterDelay(20, () -> {
                    helper.assertTrue(list.size() == 2,
                            "PackagedAuto lost child recipes after catalog refresh without reinsertion");
                    helper.succeed();
                });
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
        });
    }

    @GameTest(batch = "provider_compat", template = "empty", timeoutTicks = 160)
    public static void packagedProviderRefreshesOnlyChangedCatalog(GameTestHelper helper) {
        var block = BuiltInRegistries.BLOCK.getOptional(id("packagedauto:packaging_provider"));
        if (block.isEmpty()) { helper.succeed(); return; }
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, block.get());
        helper.setBlock(pos.east(), appeng.core.definitions.AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.runAfterDelay(12, () -> {
            try {
                var level = helper.getLevel();
                BlockEntity entity = level.getBlockEntity(helper.absolutePos(pos));
                helper.assertTrue(entity instanceof ICraftingProvider, "Missing ME Packaging Provider");
                Object handler = entity.getClass().getMethod("getItemHandler").invoke(entity);
                ItemStack item = aggregate(helper, false);
                ((IItemHandlerModifiable) handler).setStackInSlot(0, item);
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) entity.getClass().getField("recipeList").get(entity);
                helper.assertTrue(list.size() == 2, "Initial PackagedAuto catalog did not load");

                var ref = ModDataComponents.getAggregatePattern(item);
                helper.assertTrue(ref != null, "Missing catalog UUID on fixture pattern");
                AggregateCatalogMutationService.replace(
                        level.getServer(), ref.libraryId(), ref.catalystId(),
                        "aeallpattern.packaged_test",
                        List.of(new AggregateRecipe("packaged-regression-first",
                                id("aeallpattern:packaged_regression_first"),
                                AggregatePatternKind.PROCESSING,
                                List.of(new GenericStack(AEItemKey.of(Items.COAL), 1)),
                                List.of(new GenericStack(AEItemKey.of(Items.GOLD_INGOT), 1)), 20)));
                // Keep the same child pattern ID while changing its output. This
                // catches stale aggregate-package: details in the child cache.
                // Physical pattern must not be reinserted.
                helper.runAfterDelay(20, () -> {
                    helper.assertTrue(list.size() == 1,
                            "UUID-scoped catalog update did not republish PackagedAuto recipes");
                    try {
                        @SuppressWarnings("unchecked")
                        List<ItemStack> outputs = (List<ItemStack>) list.get(0).getClass()
                                .getMethod("getOutputs").invoke(list.get(0));
                        helper.assertTrue(!outputs.isEmpty() && outputs.get(0).is(Items.GOLD_INGOT),
                                "PackagedAuto still publishes the old Aggregate Pattern output");
                    } catch (ReflectiveOperationException error) {
                        throw new IllegalStateException(error);
                    }
                    helper.assertTrue(((IItemHandlerModifiable) handler).getStackInSlot(0).is(
                            ModItems.AGGREGATE_PATTERN.get()), "Physical Aggregate Pattern was removed");
                    helper.succeed();
                });
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
        });
    }

    @GameTest(batch = "provider_compat", template = "empty", timeoutTicks = 140)
    public static void packagedProviderRespectsDisabledAggregateChildren(GameTestHelper helper) {
        var block = BuiltInRegistries.BLOCK.getOptional(id("packagedauto:packaging_provider"));
        if (block.isEmpty()) { helper.succeed(); return; }
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, block.get());
        helper.setBlock(pos.east(), appeng.core.definitions.AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.runAfterDelay(12, () -> {
            try {
                BlockEntity entity = helper.getLevel().getBlockEntity(helper.absolutePos(pos));
                Object handler = entity.getClass().getMethod("getItemHandler").invoke(entity);
                ((IItemHandlerModifiable) handler).setStackInSlot(0, aggregate(helper, true));
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) entity.getClass().getField("recipeList").get(entity);
                helper.assertTrue(list.size() == 1,
                        "PackagedAuto ignored the Aggregate Pattern child selection: " + list.size());
                helper.succeed();
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
        });
    }
}
