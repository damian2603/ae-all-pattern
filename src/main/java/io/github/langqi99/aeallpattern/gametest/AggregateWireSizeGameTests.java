package io.github.langqi99.aeallpattern.gametest;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import io.github.langqi99.aeallpattern.AeAllPattern;
import io.github.langqi99.aeallpattern.aggregate.AggregatePatternKind;
import io.github.langqi99.aeallpattern.aggregate.AggregateRecipe;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Wire-format sizing requires the live AE2 key-type registry. Plain JUnit's
 * vanilla registry bootstrap is not sufficient for GenericStack.writeBuffer.
 */
@GameTestHolder(AeAllPattern.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AggregateWireSizeGameTests {
    private AggregateWireSizeGameTests() {
    }

    @GameTest(batch = "provider_compat", template = "empty", timeoutTicks = 60)
    public static void recipeWireSizeIncludesLargeItemNbt(GameTestHelper helper) {
        checkRecipeSize(helper, 5_000);
        checkRecipeSize(helper, 25_000);
        helper.succeed();
    }

    private static void checkRecipeSize(GameTestHelper helper, int metadataBytes) {
        ItemStack tagged = new ItemStack(Items.DIAMOND);
        tagged.getOrCreateTag().putString("LargeModdedData", "x".repeat(metadataBytes));
        AggregateRecipe recipe = new AggregateRecipe("wire-nbt-" + metadataBytes,
                new ResourceLocation("aeallpattern", "wire_nbt"),
                AggregatePatternKind.PROCESSING,
                List.of(new GenericStack(AEItemKey.of(Items.OAK_LOG), 1)),
                List.of(GenericStack.fromItemStack(tagged)), 20);

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            AggregateRecipe.STREAM_CODEC.encode(buffer, recipe);
            int actual = buffer.readableBytes();
            helper.assertTrue(recipe.encodedSizeEstimate() == actual,
                    "Recipe size differs from actual AE2 wire bytes: " + actual);
            helper.assertTrue(actual > metadataBytes,
                    "Recipe upload did not account for large ItemStack NBT");

            AggregateRecipe restored = AggregateRecipe.STREAM_CODEC.decode(buffer);
            helper.assertTrue(restored.outputs().equals(recipe.outputs()),
                    "AE2 key components were lost during upload round-trip");
        } finally {
            buffer.release();
        }
    }
}
