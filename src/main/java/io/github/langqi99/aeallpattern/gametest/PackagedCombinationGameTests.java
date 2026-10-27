package io.github.langqi99.aeallpattern.gametest;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;
import io.github.langqi99.aeallpattern.AeAllPattern;
import io.github.langqi99.aeallpattern.aggregate.AggregatePatternLibrary;
import io.github.langqi99.aeallpattern.aggregate.AggregateRecipe;
import io.github.langqi99.aeallpattern.machine.MachineAdapterRegistry;
import io.github.langqi99.aeallpattern.recipe.RecipeIndexService;
import io.github.langqi99.aeallpattern.recipe.RecipeSnapshot;
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

/** Real 1.20.1 CombinationRecipe: core ingredient is already element zero. */
@GameTestHolder(AeAllPattern.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PackagedCombinationGameTests {
    private static final ResourceLocation PROBE =
            new ResourceLocation("aap_packaged_test", "combination_probe");
    private static final ResourceLocation CRAFTER =
            new ResourceLocation("packagedexcrafting", "combination_crafter");

    private PackagedCombinationGameTests() {}

    @GameTest(batch = "provider_compat", template = "empty", timeoutTicks = 160)
    public static void combinationGeneratorAndPackagingProvider(GameTestHelper helper) {
        if (!Boolean.parseBoolean(System.getProperty("aeallpattern.combinationFixture", "false"))) {
            helper.succeed();
            return;
        }

        var level = helper.getLevel();
        var sourceRecipe = level.getRecipeManager().byKey(PROBE).orElse(null);
        helper.assertTrue(sourceRecipe != null,
                "Combination fixture datapack did not load the extendedcrafting:combination recipe");
        helper.assertTrue(sourceRecipe.getIngredients().size() == 3,
                "Combination fixture must have exactly 1 core and 2 pedestal ingredients");
        helper.assertTrue(sourceRecipe.getIngredients().get(0).test(new ItemStack(Items.DIAMOND)),
                "Expected the core to be the first element of getIngredients()");

        var crafterBlock = BuiltInRegistries.BLOCK.getOptional(CRAFTER);
        var providerBlock = BuiltInRegistries.BLOCK.getOptional(
                new ResourceLocation("packagedauto", "packaging_provider"));
        var pedestalBlock = BuiltInRegistries.BLOCK.getOptional(
                new ResourceLocation("packagedexcrafting", "marked_pedestal"));
        helper.assertTrue(crafterBlock.isPresent() && providerBlock.isPresent()
                        && pedestalBlock.isPresent(),
                "Combination Crafter, Marked Pedestal or Packaging Provider is not registered");

        BlockPos crafterPos = new BlockPos(2, 1, 2);
        BlockPos providerPos = new BlockPos(2, 1, 3);
        helper.setBlock(crafterPos, crafterBlock.orElseThrow());
        helper.setBlock(new BlockPos(0, 1, 2), pedestalBlock.orElseThrow());
        helper.setBlock(new BlockPos(2, 1, 0), pedestalBlock.orElseThrow());
        helper.setBlock(providerPos, providerBlock.orElseThrow());
        helper.setBlock(new BlockPos(2, 1, 4),
                appeng.core.definitions.AEBlocks.CREATIVE_ENERGY_CELL.block());

        BlockEntity machine = level.getBlockEntity(helper.absolutePos(crafterPos));
        helper.assertTrue(machine != null, "Missing Combination Crafter block entity");
        var adapter = MachineAdapterRegistry.find(level, machine).orElse(null);
        helper.assertTrue(adapter != null, "All Pattern did not register the PackagedExCrafting adapter");

        var catalog = RecipeIndexService.catalog(level, machine, adapter);
        RecipeSnapshot snapshot = catalog.recipes().stream()
                .filter(recipe -> PROBE.equals(recipe.recipeId()))
                .findFirst().orElse(null);
        helper.assertTrue(snapshot != null,
                "Generator rejected all combination recipes (former getInput() reflection bug)");
        helper.assertTrue(snapshot.inputs().size() == 3,
                "Missing a core or pedestal input in the generated combination pattern");
        helper.assertTrue(snapshot.inputs().get(0).is(Items.DIAMOND),
                "Generator did not preserve the combination's core as its first input");
        helper.assertTrue(snapshot.output().is(Items.EMERALD),
                "Combination fixture output was not captured");

        var server = level.getServer();
        var ref = AggregatePatternLibrary.get(server).put(server, CRAFTER,
                "block.packagedexcrafting.combination_crafter",
                List.of(AggregateRecipe.from(snapshot)));
        ItemStack aggregate = new ItemStack(ModItems.AGGREGATE_PATTERN.get());
        ModDataComponents.setAggregatePattern(aggregate, ref);

        helper.runAfterDelay(20, () -> {
            try {
                BlockEntity provider = level.getBlockEntity(helper.absolutePos(providerPos));
                helper.assertTrue(provider != null, "Missing ME Packaging Provider");
                Object handler = provider.getClass().getMethod("getItemHandler").invoke(provider);
                handler.getClass()
                        .getMethod("setStackInSlot", int.class, ItemStack.class)
                        .invoke(handler, 0, aggregate);

                @SuppressWarnings("unchecked")
                List<Object> infos = (List<Object>) provider.getClass()
                        .getField("recipeList").get(provider);
                helper.assertTrue(infos.size() == 1,
                        "Packaging Provider did not expose the native combination recipe");
                Object info = infos.get(0);
                helper.assertTrue(info.getClass().getName()
                                .equals("thelm.packagedexcrafting.recipe.CombinationPackageRecipeInfo"),
                        "Combination aggregate became a generic package rather than a native combination package");
                helper.assertTrue((boolean) info.getClass().getMethod("isValid").invoke(info),
                        "Native CombinationPackageRecipeInfo was not valid");
                ItemStack core = (ItemStack) info.getClass().getMethod("getCoreInput").invoke(info);
                @SuppressWarnings("unchecked")
                List<ItemStack> pedestals = (List<ItemStack>) info.getClass()
                        .getMethod("getPedestalInputs").invoke(info);
                helper.assertTrue(core.is(Items.DIAMOND) && pedestals.size() == 2,
                        "Native combination package lost the core or pedestal inputs");

                @SuppressWarnings("unchecked")
                List<IPatternDetails> patterns = (List<IPatternDetails>) provider.getClass()
                        .getMethod("getAvailablePatterns").invoke(provider);
                IPatternDetails unpackaging = patterns.stream()
                        .filter(pattern -> pattern.getClass().getName()
                                .endsWith("RecipeCraftingPatternDetails"))
                        .findFirst().orElse(null);
                helper.assertTrue(unpackaging != null,
                        "PackagedAuto did not expose a crafting pattern for the combination package");

                boolean accepted = (boolean) provider.getClass()
                        .getMethod("pushPattern", IPatternDetails.class, KeyCounter[].class)
                        .invoke(provider, unpackaging, new KeyCounter[0]);
                helper.assertTrue(accepted,
                        "ME Packaging Provider could not send the combination recipe to Combination Crafter");
                helper.assertTrue((boolean) machine.getClass().getMethod("isBusy").invoke(machine),
                        "Combination Crafter did not accept the package with marked pedestals");
                helper.succeed();
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Failed to test native combination package delivery", error);
            }
        });
    }
}
