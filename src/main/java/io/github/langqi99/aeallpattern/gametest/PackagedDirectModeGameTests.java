package io.github.langqi99.aeallpattern.gametest;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;
import io.github.langqi99.aeallpattern.AeAllPattern;
import io.github.langqi99.aeallpattern.aggregate.AggregatePatternLibrary;
import io.github.langqi99.aeallpattern.aggregate.AggregateRecipe;
import io.github.langqi99.aeallpattern.machine.MachineAdapterRegistry;
import io.github.langqi99.aeallpattern.recipe.RecipeIndexService;
import io.github.langqi99.aeallpattern.registry.ModDataComponents;
import io.github.langqi99.aeallpattern.registry.ModItems;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.IItemHandlerModifiable;

/** Direct-mode Aggregate Patterns must still dispatch through PackagedAuto's native machine path. */
@GameTestHolder(AeAllPattern.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PackagedDirectModeGameTests {
    private PackagedDirectModeGameTests() {}

    private static ResourceLocation id(String name) {
        return new ResourceLocation(name);
    }

    @GameTest(batch = "provider_compat", template = "empty", timeoutTicks = 200)
    public static void packagingProviderDirectModeDispatchesToPackagedCrafter(GameTestHelper helper) {
        var crafterBlock = BuiltInRegistries.BLOCK.getOptional(id("packagedexcrafting:ender_crafter"));
        var providerBlock = BuiltInRegistries.BLOCK.getOptional(id("packagedauto:packaging_provider"));
        if (crafterBlock.isEmpty() || providerBlock.isEmpty()) {
            helper.succeed();
            return;
        }
        var crafterPos = new BlockPos(1, 1, 1);
        var providerPos = new BlockPos(0, 1, 1);
        helper.setBlock(crafterPos, crafterBlock.get());
        helper.setBlock(providerPos, providerBlock.get());
        helper.setBlock(providerPos.above(), appeng.core.definitions.AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.runAfterDelay(20, () -> {
            try {
                var level = helper.getLevel();
                BlockEntity machine = level.getBlockEntity(helper.absolutePos(crafterPos));
                BlockEntity provider = level.getBlockEntity(helper.absolutePos(providerPos));
                helper.assertTrue(machine != null && provider != null, "Missing packaged crafter or ME provider");
                var adapter = MachineAdapterRegistry.find(level, machine).orElseThrow();
                var snapshot = RecipeIndexService.catalog(level, machine, adapter).recipes().get(0);
                var server = level.getServer();
                var ref = AggregatePatternLibrary.get(server).put(server,
                        id("extendedcrafting:ender_crafter"),
                        "block.extendedcrafting.ender_crafter",
                        List.of(AggregateRecipe.from(snapshot)));
                ItemStack aggregate = new ItemStack(ModItems.AGGREGATE_PATTERN.get());
                ModDataComponents.setAggregatePattern(aggregate, ref);
                provider.getClass().getField("provideDirect").setBoolean(provider, true);
                provider.getClass().getField("providePackaging").setBoolean(provider, false);
                provider.getClass().getField("provideUnpackaging").setBoolean(provider, false);
                provider.getClass().getMethod("postPatternChange").invoke(provider);
                var handler = (IItemHandlerModifiable) provider.getClass().getMethod("getItemHandler").invoke(provider);
                handler.setStackInSlot(0, aggregate);
                helper.runAfterDelay(12, () -> {
                    try {
                        @SuppressWarnings("unchecked")
                        List<IPatternDetails> patterns = (List<IPatternDetails>) provider.getClass()
                                .getMethod("getAvailablePatterns").invoke(provider);
                        helper.assertTrue(patterns.size() == 1,
                                "Direct mode did not expose exactly one native packaged recipe");
                        IPatternDetails direct = patterns.get(0);
                        helper.assertTrue(direct.getClass().getName().endsWith("DirectCraftingPatternDetails"),
                                "Direct mode did not use PackagedAuto's direct pattern details");
                        boolean accepted = (boolean) provider.getClass()
                                .getMethod("pushPattern", IPatternDetails.class, KeyCounter[].class)
                                .invoke(provider, direct, new KeyCounter[0]);
                        helper.assertTrue(accepted,
                                "ME Packaging Provider could not dispatch the direct aggregate recipe");
                        helper.assertTrue((boolean) machine.getClass().getMethod("isBusy").invoke(machine),
                                "Packaged crafter did not accept the direct-mode recipe");
                        helper.succeed();
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
