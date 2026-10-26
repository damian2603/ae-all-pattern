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
}
