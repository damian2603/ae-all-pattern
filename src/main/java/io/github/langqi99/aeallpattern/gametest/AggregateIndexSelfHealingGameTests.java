package io.github.langqi99.aeallpattern.gametest;

import appeng.api.crafting.IPatternDetails;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IManagedGridNode;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.me.service.CraftingService;
import appeng.me.service.helpers.NetworkCraftingProviders;
import io.github.langqi99.aeallpattern.AeAllPattern;
import io.github.langqi99.aeallpattern.aggregate.AggregatePatternKind;
import io.github.langqi99.aeallpattern.aggregate.AggregatePatternLibrary;
import io.github.langqi99.aeallpattern.aggregate.AggregateRecipe;
import io.github.langqi99.aeallpattern.registry.ModDataComponents;
import io.github.langqi99.aeallpattern.registry.ModItems;
import io.github.langqi99.aeallpattern.util.Reflect;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Reproduce a real long-session failure: the aggregate remains in the provider
 * inventory, but AE2 loses its crafting-provider state. The health probe should
 * restore it without removing the item or executing /aeallpattern refresh.
 */
@GameTestHolder(AeAllPattern.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AggregateIndexSelfHealingGameTests {
    private AggregateIndexSelfHealingGameTests() {}

    @GameTest(batch = "provider_compat", template = "empty", timeoutTicks = 600)
    public static void vanillaProviderRecoversLostAeIndex(GameTestHelper helper) {
        exercise(helper, new ResourceLocation("ae2", "pattern_provider"));
    }

    @GameTest(batch = "provider_compat", template = "empty", timeoutTicks = 600)
    public static void advancedProviderRecoversLostAeIndex(GameTestHelper helper) {
        exercise(helper, new ResourceLocation("advanced_ae", "adv_pattern_provider"));
    }

    @GameTest(batch = "provider_compat", template = "empty", timeoutTicks = 600)
    public static void advancedProviderRecoversEmptyLocalPatterns(GameTestHelper helper) {
        var blockId = new ResourceLocation("advanced_ae", "adv_pattern_provider");
        var block = BuiltInRegistries.BLOCK.getOptional(blockId);
        if (block.isEmpty()) {
            helper.succeed();
            return;
        }
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, block.orElseThrow());
        helper.setBlock(pos.east(), appeng.core.definitions.AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.runAfterDelay(20, () -> {
            try {
                var entity = helper.getLevel().getBlockEntity(helper.absolutePos(pos));
                helper.assertTrue(entity != null, "AdvancedAE Pattern Provider missing");
                Object logic = entity.getClass().getMethod("getLogic").invoke(entity);
                var inventory = (InternalInventory) logic.getClass().getMethod("getPatternInv").invoke(logic);
                inventory.setItemDirect(0, makeAggregate(helper, blockId));
                helper.runAfterDelay(45, () -> {
                    @SuppressWarnings("unchecked")
                    List<IPatternDetails> local = (List<IPatternDetails>) Reflect.field(logic, "patterns");
                    helper.assertTrue(local != null && local.size() > 0,
                            "AdvancedAE did not decode the initial aggregate");
                    local.clear();
                    helper.assertTrue(local.isEmpty(), "Failed to reproduce loss of local child recipes");
                    helper.succeedWhen(() -> {
                        helper.assertTrue(!local.isEmpty(),
                                "Automatic health probe did not rebuild the emptied AdvancedAE local list");
                        helper.assertTrue(inventory.getStackInSlot(0).is(ModItems.AGGREGATE_PATTERN.get()),
                                "Physical Aggregate Pattern was lost while repairing AdvancedAE");
                    });
                });
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
        });
    }

    @GameTest(batch = "provider_compat", template = "empty", timeoutTicks = 240)
    public static void fullyFilteredCatalogIsHealthyWithoutRepairLoop(GameTestHelper helper) {
        var blockId = new ResourceLocation("ae2", "pattern_provider");
        var block = BuiltInRegistries.BLOCK.getOptional(blockId);
        if (block.isEmpty()) {
            helper.succeed();
            return;
        }
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, block.orElseThrow());
        helper.setBlock(pos.east(), appeng.core.definitions.AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.runAfterDelay(20, () -> {
            try {
                var entity = helper.getLevel().getBlockEntity(helper.absolutePos(pos));
                helper.assertTrue(entity != null, "Pattern Provider missing");
                Object logic = entity.getClass().getMethod("getLogic").invoke(entity);
                var inventory = (InternalInventory) logic.getClass().getMethod("getPatternInv").invoke(logic);
                ItemStack physical = makeAggregate(helper, blockId);
                // Default options skip probabilistic main outputs. Mark the only recipe's
                // output as probabilistic so the completed expansion is legitimately empty.
                var server = helper.getLevel().getServer();
                var recipe = new AggregateRecipe("filtered-empty",
                        new ResourceLocation("minecraft", "oak_planks"), AggregatePatternKind.CRAFTING,
                        List.of(new GenericStack(AEItemKey.of(Items.OAK_LOG), 1)), List.of(),
                        List.of(new GenericStack(AEItemKey.of(Items.OAK_PLANKS), 4)), 1, 1);
                var ref = AggregatePatternLibrary.get(server).put(server,
                        new ResourceLocation("aeallpattern", "filtered_empty_" + System.nanoTime()),
                        "block.minecraft.crafting_table", List.of(recipe));
                ModDataComponents.setAggregatePattern(physical, ref);
                inventory.setItemDirect(0, physical);
                helper.runAfterDelay(80, () -> {
                    helper.assertTrue(!io.github.langqi99.aeallpattern.aggregate.AggregateProviderExpansion
                                    .expectsPublishedChildren(inventory, helper.getLevel()),
                            "Fully filtered catalog still expects published children");
                    @SuppressWarnings("unchecked")
                    List<IPatternDetails> local = (List<IPatternDetails>) Reflect.field(logic, "patterns");
                    helper.assertTrue(local == null || local.stream().noneMatch(
                                    p -> p instanceof io.github.langqi99.aeallpattern.aggregate.AggregatePatternDetails
                                            || p instanceof io.github.langqi99.aeallpattern.aggregate.AggregateAssemblerPatternDetails),
                            "Filtered catalog unexpectedly published aggregate children");
                    helper.assertTrue(inventory.getStackInSlot(0).is(ModItems.AGGREGATE_PATTERN.get()),
                            "Health probe removed the physical aggregate");
                    helper.succeed();
                });
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
        });
    }

    private static ItemStack makeAggregate(GameTestHelper helper, ResourceLocation providerId) {
        var server = helper.getLevel().getServer();
        var recipe = new AggregateRecipe("self-heal-" + providerId,
                new ResourceLocation("minecraft", "oak_planks"),
                AggregatePatternKind.CRAFTING,
                List.of(new GenericStack(AEItemKey.of(Items.OAK_LOG), 1)),
                List.of(new GenericStack(AEItemKey.of(Items.OAK_PLANKS), 4)), 1);
        var library = AggregatePatternLibrary.get(server);
        var ref = library.put(server,
                new ResourceLocation("aeallpattern",
                        "self_heal_" + providerId.getPath()),
                "block.minecraft.crafting_table", List.of(recipe));
        ItemStack result = new ItemStack(ModItems.AGGREGATE_PATTERN.get());
        ModDataComponents.setAggregatePattern(result, ref);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void exercise(GameTestHelper helper, ResourceLocation blockId) {
        var block = BuiltInRegistries.BLOCK.getOptional(blockId);
        if (block.isEmpty()) {
            helper.succeed();
            return;
        }
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, block.orElseThrow());
        helper.setBlock(pos.east(), appeng.core.definitions.AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.runAfterDelay(20, () -> {
            try {
                var entity = helper.getLevel().getBlockEntity(helper.absolutePos(pos));
                helper.assertTrue(entity != null, "Pattern Provider did not load: " + blockId);
                Object logic = entity.getClass().getMethod("getLogic").invoke(entity);
                var inventory = (InternalInventory) logic.getClass().getMethod("getPatternInv").invoke(logic);
                ItemStack physical = makeAggregate(helper, blockId);
                inventory.setItemDirect(0, physical);
                helper.runAfterDelay(45, () -> {
                    try {
                        var node = (IManagedGridNode) entity.getClass().getMethod("getMainNode").invoke(entity);
                        helper.assertTrue(node != null && node.isActive() && node.getGrid() != null,
                                "Pattern provider never connected to ME grid");
                        var craftingService = node.getGrid().getCraftingService();
                        List<IPatternDetails> patterns = (List<IPatternDetails>) logic.getClass()
                                .getMethod("getAvailablePatterns").invoke(logic);
                        helper.assertTrue(!patterns.isEmpty(), "Aggregate was not decoded in addon");
                        IPatternDetails own = patterns.stream()
                                .filter(p -> p instanceof io.github.langqi99.aeallpattern.aggregate.AggregatePatternDetails
                                        || p instanceof io.github.langqi99.aeallpattern.aggregate.AggregateAssemblerPatternDetails)
                                .findFirst().orElse(null);
                        helper.assertTrue(own != null, "Provider has no virtual aggregate children");
                        helper.assertTrue(craftingService.getCraftingFor(own.getPrimaryOutput().what()).contains(own),
                                "Aggregate was never published to the ME index");
                        // Simulate a dropped crafting provider while keeping physical
                        // inventory, grid node, and already-expanded child list intact.
                        var methods = Reflect.field(craftingService, "craftingProviders",
                                NetworkCraftingProviders.class);
                        helper.assertTrue(methods != null, "Cannot inspect AE2 crafting provider registry");
                        methods.removeProvider(node.getNode());
                        helper.assertTrue(!craftingService.getCraftingFor(own.getPrimaryOutput().what()).contains(own),
                                "AE2 crafting registry was not actually emptied");
                        helper.succeedWhen(() -> {
                            helper.assertTrue(inventory.getStackInSlot(0).is(ModItems.AGGREGATE_PATTERN.get()),
                                    "Provider lost the physical pattern during recovery");
                            helper.assertTrue(craftingService.getCraftingFor(own.getPrimaryOutput().what()).contains(own),
                                    "Automatic AE2 index health probe did not restore the pattern");
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
}
