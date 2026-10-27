package io.github.langqi99.aeallpattern.gametest;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import io.github.langqi99.aeallpattern.AeAllPattern;
import io.github.langqi99.aeallpattern.aggregate.AggregatePatternKind;
import io.github.langqi99.aeallpattern.aggregate.AggregatePatternLibrary;
import io.github.langqi99.aeallpattern.aggregate.AggregateRecipe;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Per-world recipe-read cache: immutable lists reused until a matching write. */
@GameTestHolder(AeAllPattern.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AggregateLibraryCacheGameTests {
    private AggregateLibraryCacheGameTests() {}

    private static AggregateRecipe recipe(net.minecraft.world.item.Item output) {
        return new AggregateRecipe("read-cache-recipe",
                new ResourceLocation("minecraft", "oak_planks"),
                AggregatePatternKind.CRAFTING,
                List.of(new GenericStack(AEItemKey.of(Items.OAK_LOG), 1)),
                List.of(new GenericStack(AEItemKey.of(output), 1)), 1);
    }

    @GameTest(batch = "provider_compat", template = "empty", timeoutTicks = 100)
    public static void cachedReadsAreInvalidatedOnPutAndReplace(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var library = AggregatePatternLibrary.get(server);
        ResourceLocation catalyst = new ResourceLocation("aeallpattern",
                "cache_probe_" + UUID.randomUUID().toString().replace("-", ""));
        var ref = library.put(server, catalyst, "block.minecraft.crafting_table",
                List.of(recipe(Items.DIAMOND)));
        var first = library.recipes(server, ref.libraryId()).orElseThrow();
        var repeated = library.recipes(server, ref.libraryId()).orElseThrow();
        helper.assertTrue(first == repeated, "An unchanged Aggregate catalog was reconstructed on every read");

        library.put(server, catalyst, "block.minecraft.crafting_table",
                List.of(recipe(Items.EMERALD)));
        var changed = library.recipes(server, ref.libraryId()).orElseThrow();
        helper.assertTrue(changed != first, "put() did not invalidate the cached immutable catalog");
        helper.assertTrue(changed.get(0).outputs().get(0).what()
                        .equals(AEItemKey.of(Items.EMERALD)), "put() returned stale recipe output");

        library.replace(server, ref.libraryId(), catalyst, "block.minecraft.crafting_table",
                List.of(recipe(Items.GOLD_INGOT)));
        var replaced = library.recipes(server, ref.libraryId()).orElseThrow();
        helper.assertTrue(replaced != changed, "replace() did not invalidate the cached catalog");
        helper.assertTrue(replaced.get(0).outputs().get(0).what()
                        .equals(AEItemKey.of(Items.GOLD_INGOT)), "replace() returned stale output");
        helper.succeed();
    }
}
