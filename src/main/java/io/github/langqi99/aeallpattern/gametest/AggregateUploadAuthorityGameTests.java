package io.github.langqi99.aeallpattern.gametest;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import io.github.langqi99.aeallpattern.AeAllPattern;
import io.github.langqi99.aeallpattern.aggregate.AggregateCatalogMutationService;
import io.github.langqi99.aeallpattern.aggregate.AggregatePatternKind;
import io.github.langqi99.aeallpattern.aggregate.AggregatePatternLibrary;
import io.github.langqi99.aeallpattern.aggregate.AggregateRecipe;
import io.github.langqi99.aeallpattern.network.AggregateCatalogAuthority;
import io.github.langqi99.aeallpattern.network.AggregateGenerationService;
import io.github.langqi99.aeallpattern.network.GenerateAggregatePayload;
import java.util.List;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Shared catalog replacement must not be writable by an arbitrary multiplayer client. */
@GameTestHolder(AeAllPattern.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AggregateUploadAuthorityGameTests {
    private AggregateUploadAuthorityGameTests() {
    }

    @GameTest(batch = "provider_compat", template = "empty", timeoutTicks = 120)
    public static void nonOperatorCannotReplaceExistingSharedCatalog(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        // makeMockPlayer() returns an anonymous Player, not a ServerPlayer. Forge's
        // FakePlayer is a real ServerPlayer; clear its connection so rejection does not
        // try to send a packet to a test-only player.
        ServerPlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "authority_test"));
        player.connection = null;
        BlockPos playerPos = helper.absolutePos(new BlockPos(1, 1, 1));
        player.setPos(playerPos.getX() + 0.5, playerPos.getY(), playerPos.getZ() + 0.5);
        var machine = new ResourceLocation("aeallpattern", "authority_" + UUID.randomUUID().toString().replace("-", ""));
        var recipe = new AggregateRecipe("authority-recipe",
                new ResourceLocation("minecraft", "oak_planks"), AggregatePatternKind.CRAFTING,
                List.of(new GenericStack(AEItemKey.of(Items.OAK_LOG), 1)),
                List.of(new GenericStack(AEItemKey.of(Items.OAK_PLANKS), 4)), 1);
        var original = AggregateCatalogMutationService.put(
                server, machine, "block.minecraft.crafting_table", List.of(recipe));
        var originalEntry = AggregatePatternLibrary.get(server).find(original.libraryId()).orElseThrow();
        helper.assertTrue(!AggregateCatalogAuthority.canReplaceSharedCatalog(player),
                "Mock multiplayer player unexpectedly has operator authority");

        var payload = new GenerateAggregatePayload(
                UUID.randomUUID(), BlockPos.ZERO, machine, "block.minecraft.crafting_table",
                original.libraryId(), 0, 1, 1, List.of(recipe));
        AggregateGenerationService.handle(payload, player);

        var after = AggregatePatternLibrary.get(server).find(original.libraryId()).orElseThrow();
        helper.assertTrue(after.contentHash().equals(originalEntry.contentHash()),
                "Non-operator client replaced a shared aggregate catalog");
        helper.succeed();
    }
}
