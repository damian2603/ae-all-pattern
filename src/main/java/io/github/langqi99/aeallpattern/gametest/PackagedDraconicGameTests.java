package io.github.langqi99.aeallpattern.gametest;

import appeng.api.stacks.AEItemKey;
import io.github.langqi99.aeallpattern.AeAllPattern;
import io.github.langqi99.aeallpattern.aggregate.AggregateRecipe;
import io.github.langqi99.aeallpattern.machine.MachineAdapterRegistry;
import io.github.langqi99.aeallpattern.recipe.RecipeIndexService;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Version-pinned optional integration coverage (runs when PackagedDraconic is loaded). */
@GameTestHolder(AeAllPattern.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PackagedDraconicGameTests {
    private PackagedDraconicGameTests() {}

    @GameTest(batch = "provider_compat", template = "empty", timeoutTicks = 160)
    public static void fusionPackageCrafterProducesNativeCatalog(GameTestHelper helper) {
        var block = BuiltInRegistries.BLOCK.getOptional(
                new ResourceLocation("packageddraconic", "fusion_crafter"));
        if (block.isEmpty()) {
            helper.succeed();
            return;
        }
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, block.orElseThrow());
        helper.runAfterDelay(10, () -> {
            try {
                var level = helper.getLevel();
                var machine = level.getBlockEntity(helper.absolutePos(pos));
                helper.assertTrue(machine != null, "Fusion Package Crafter block entity did not load");
                var adapter = MachineAdapterRegistry.find(level, machine).orElse(null);
                helper.assertTrue(adapter != null, "PackagedDraconic is missing its native machine adapter");
                var catalog = RecipeIndexService.catalog(level, machine, adapter);
                helper.assertFalse(catalog.recipes().isEmpty(), "No Draconic fusion recipes were captured");
                var recipe = catalog.recipes().get(0);
                var infoClass = Class.forName("thelm.packageddraconic.recipe.FusionPackageRecipeInfo");
                Method create = Class.forName(
                                "io.github.langqi99.aeallpattern.machine.PackagedDraconicAdapter")
                        .getDeclaredMethod("recipeInfo", ResourceLocation.class, java.util.List.class);
                create.setAccessible(true);
                Object info = create.invoke(null, recipe.recipeId(), recipe.inputs());
                helper.assertTrue(infoClass.isInstance(info), "Fusion recipe produced the wrong package type");
                var valid = infoClass.getMethod("isValid").invoke(info);
                helper.assertTrue(Boolean.TRUE.equals(valid), "Fusion package cannot recover native recipe");
                @SuppressWarnings("unchecked")
                var injectors = (java.util.List<ItemStack>) infoClass
                        .getMethod("getInjectorInputs").invoke(info);
                // A bare NBT ListTag used to load every injector at index 0.
                // The resulting package looked valid but fusion could never start.
                helper.assertTrue(injectors.size() == recipe.inputs().size() - 1,
                        "Fusion injector count lost after NBT load: got " + injectors.size()
                                + ", expected " + (recipe.inputs().size() - 1));
                for (int index = 0; index < injectors.size(); index++) {
                    helper.assertTrue(ItemStack.matches(injectors.get(index), recipe.inputs().get(index + 1)),
                            "Fusion injector " + index + " changed during serialization");
                }
                @SuppressWarnings("unchecked")
                var packagePatterns = (java.util.List<Object>) infoClass.getMethod("getPatterns").invoke(info);
                helper.assertFalse(packagePatterns.isEmpty(), "Fusion recipe emitted no package items");
                Object firstPattern = packagePatterns.get(0);
                ItemStack packageStack = (ItemStack) firstPattern.getClass().getMethod("getOutput")
                        .invoke(firstPattern);
                Object restoredRecipe = Class.forName("thelm.packagedauto.item.PackageItem")
                        .getMethod("getRecipeInfo", ItemStack.class)
                        .invoke(packageStack.getItem(), packageStack);
                helper.assertTrue(infoClass.isInstance(restoredRecipe),
                        "The generated package decodes as generic processing instead of fusion");
                @SuppressWarnings("unchecked")
                var unpackedInjectors = (java.util.List<ItemStack>) infoClass
                        .getMethod("getInjectorInputs").invoke(restoredRecipe);
                helper.assertTrue(unpackedInjectors.size() == injectors.size(),
                        "Real PackageItem roundtrip silently dropped fusion injectors");
                var output = (ItemStack) infoClass.getMethod("getOutput").invoke(info);
                helper.assertTrue(ItemStack.isSameItemSameTags(output, recipe.output())
                                && output.getCount() == recipe.output().getCount(),
                        "Rebuilt fusion output does not match native recipe");
                var aggregate = AggregateRecipe.from(recipe);
                helper.assertTrue(!aggregate.inputs().isEmpty()
                                && aggregate.inputs().get(0).what() instanceof AEItemKey,
                        "Draconic core catalyst was not retained in Aggregate Pattern");
                helper.succeed();
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Could not test optional Draconic Fusion recipe bridge", error);
            }
        });
    }

    /**
     * Check the actual ME Packaging Provider -> Fusion Package Crafter dispatch
     * route. Without Marked Injectors the crafter correctly refuses the craft,
     * but only a real native FusionPackageRecipeInfo reaches the tier counter.
     */
    @GameTest(batch = "provider_compat", template = "empty", timeoutTicks = 180)
    public static void packagingProviderDispatchesFusionRecipe(GameTestHelper helper) {
        var fusionBlock = BuiltInRegistries.BLOCK.getOptional(
                new ResourceLocation("packageddraconic", "fusion_crafter"));
        var providerBlock = BuiltInRegistries.BLOCK.getOptional(
                new ResourceLocation("packagedauto", "packaging_provider"));
        if (fusionBlock.isEmpty() || providerBlock.isEmpty()) {
            helper.succeed();
            return;
        }
        var fusionPos = new BlockPos(1, 1, 1);
        var providerPos = new BlockPos(2, 1, 1);
        helper.setBlock(fusionPos, fusionBlock.get());
        helper.setBlock(providerPos, providerBlock.get());
        helper.setBlock(providerPos.above(),
                appeng.core.definitions.AEBlocks.CREATIVE_ENERGY_CELL.block());

        helper.runAfterDelay(20, () -> {
            try {
                var level = helper.getLevel();
                var fusion = level.getBlockEntity(helper.absolutePos(fusionPos));
                var provider = level.getBlockEntity(helper.absolutePos(providerPos));
                helper.assertTrue(fusion != null && provider != null, "Fusion crafter or provider missing");
                var adapter = MachineAdapterRegistry.find(level, fusion).orElse(null);
                helper.assertTrue(adapter != null, "PackagedDraconic adapter missing");
                var catalog = RecipeIndexService.catalog(level, fusion, adapter);
                var snapshot = catalog.recipes().stream().filter(recipe -> recipe.inputs().size() > 1)
                        .findFirst().orElse(null);
                helper.assertTrue(snapshot != null, "No eligible fusion recipe for dispatch");
                var ref = io.github.langqi99.aeallpattern.aggregate.AggregatePatternLibrary
                        .get(level.getServer()).put(level.getServer(),
                                new ResourceLocation("packageddraconic", "fusion_crafter"),
                                "block.packageddraconic.fusion_crafter",
                                java.util.List.of(AggregateRecipe.from(snapshot)));
                ItemStack pattern = new ItemStack(
                        io.github.langqi99.aeallpattern.registry.ModItems.AGGREGATE_PATTERN.get());
                io.github.langqi99.aeallpattern.registry.ModDataComponents
                        .setAggregatePattern(pattern, ref);
                var handler = (net.minecraftforge.items.IItemHandlerModifiable)
                        provider.getClass().getMethod("getItemHandler").invoke(provider);
                handler.setStackInSlot(0, pattern);

                helper.runAfterDelay(20, () -> {
                    try {
                        var mainNode = (appeng.api.networking.IManagedGridNode)
                                provider.getClass().getMethod("getMainNode").invoke(provider);
                        helper.assertTrue(mainNode.isActive(), "ME Packaging Provider not powered");
                        // Exercise the fusion recipe's unpackaging dispatch path explicitly;
                        // Direct is tested separately and is the provider's normal default.
                        provider.getClass().getField("provideDirect").setBoolean(provider, false);
                        provider.getClass().getField("providePackaging").setBoolean(provider, false);
                        provider.getClass().getField("provideUnpackaging").setBoolean(provider, true);
                        provider.getClass().getMethod("postPatternChange").invoke(provider);
                        @SuppressWarnings("unchecked")
                        var patterns = (java.util.List<appeng.api.crafting.IPatternDetails>)
                                provider.getClass().getMethod("getAvailablePatterns").invoke(provider);
                        var recipeDetails = patterns.stream().filter(details ->
                                details.getClass().getName().equals(
                                        "thelm.packagedauto.integration.appeng.recipe.RecipeCraftingPatternDetails"))
                                .findFirst().orElse(null);
                        helper.assertTrue(recipeDetails != null,
                                "Packaging provider did not publish a fusion unpackaging pattern");

                        boolean accepted = (boolean) provider.getClass().getMethod("pushPattern",
                                appeng.api.crafting.IPatternDetails.class,
                                appeng.api.stacks.KeyCounter[].class)
                                .invoke(provider, recipeDetails,
                                        new appeng.api.stacks.KeyCounter[0]);
                        // No Marked Injectors have been placed; rejection is the
                        // safe outcome, and requiredInjectors proves dispatch occurred.
                        helper.assertTrue(!accepted, "Fusion unexpectedly started without marked injectors");
                        int[] required = (int[]) fusion.getClass().getField("requiredInjectors").get(fusion);
                        int reached = java.util.Arrays.stream(required).max().orElse(0);
                        helper.assertTrue(reached >= snapshot.inputs().size() - 1,
                                "ME provider did not send native fusion recipe to adjacent crafter");
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
