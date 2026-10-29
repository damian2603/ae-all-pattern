package io.github.langqi99.aeallpattern.gametest;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import io.github.langqi99.aeallpattern.AeAllPattern;
import io.github.langqi99.aeallpattern.aggregate.AggregatePatternKind;
import io.github.langqi99.aeallpattern.aggregate.AggregateRecipe;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
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
            byte[] originalWireBytes = new byte[actual];
            buffer.getBytes(buffer.readerIndex(), originalWireBytes);
            helper.assertTrue(recipe.encodedSizeEstimate() == actual,
                    "Recipe size differs from actual AE2 wire bytes: " + actual);
            helper.assertTrue(actual > metadataBytes,
                    "Recipe upload did not account for large ItemStack NBT");

            // The next network encode must reuse the measured bytes, not serialize the NBT again.
            FriendlyByteBuf cachedEncoding = new FriendlyByteBuf(Unpooled.buffer());
            try {
                AggregateRecipe.STREAM_CODEC.encode(cachedEncoding, recipe);
                byte[] reusedWireBytes = new byte[cachedEncoding.readableBytes()];
                cachedEncoding.getBytes(cachedEncoding.readerIndex(), reusedWireBytes);
                helper.assertTrue(Arrays.equals(originalWireBytes, reusedWireBytes),
                        "Cached recipe bytes differed from actual wire encoding");
            } finally {
                cachedEncoding.release();
            }

            AggregateRecipe restored = AggregateRecipe.STREAM_CODEC.decode(buffer);
            helper.assertTrue(restored.consumeEncodedSize() == actual,
                    "Decoder did not preserve the exact number of recipe wire bytes");
            helper.assertTrue(restored.outputs().size() == 1
                            && restored.outputs().get(0).amount() == recipe.outputs().get(0).amount()
                            && restored.outputs().get(0).what().equals(recipe.outputs().get(0).what()),
                    "AE2 key components or stack amount were lost during upload round-trip");
        } finally {
            buffer.release();
        }
    }
}
