package io.github.langqi99.aeallpattern.aggregate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import io.github.langqi99.aeallpattern.TestMinecraftBootstrap;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Prevent Forge upload page budgets from ignoring modded ItemStack metadata. */
class AggregateRecipeWireSizeTest {
    @BeforeAll
    static void bootstrap() {
        TestMinecraftBootstrap.initialize();
    }

    @Test
    void sizeMeasurementMatchesActualWireEncodingWithLargeNbt() {
        ItemStack tagged = new ItemStack(Items.DIAMOND);
        tagged.getOrCreateTag().putString("LargeModdedData", "x".repeat(5_000));
        AggregateRecipe recipe = new AggregateRecipe("wire-nbt",
                new ResourceLocation("aeallpattern", "wire_nbt"),
                AggregatePatternKind.PROCESSING,
                List.of(new GenericStack(AEItemKey.of(Items.OAK_LOG), 1)),
                List.of(GenericStack.fromItemStack(tagged)), 20);

        FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
        try {
            AggregateRecipe.STREAM_CODEC.encode(encoded, recipe);
            assertEquals(encoded.readableBytes(), recipe.encodedSizeEstimate());
            assertTrue(recipe.encodedSizeEstimate() > 5_000,
                    "large item metadata must count toward network limits");
        } finally {
            encoded.release();
        }
    }
}
