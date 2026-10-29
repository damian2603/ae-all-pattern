package io.github.langqi99.aeallpattern.network;

import io.github.langqi99.aeallpattern.aggregate.AggregateMetadataView;
import io.github.langqi99.aeallpattern.aggregate.AggregatePatternLibrary;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;

import java.util.Objects;

public final class AggregateMetadataSyncService {
    private AggregateMetadataSyncService() {
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            send(player);
        }
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            send(player);
        }
    }

    /** Machine adapters read these recipes directly from the server recipe manager.
     *  Sending them through JEI/EMI at login is not only redundant: incomplete
     *  viewer registration can replace an entire persisted catalog with 0/0. */
    static boolean serverOwnedPackagedCatalog(net.minecraft.resources.ResourceLocation catalystId) {
        return catalystId.getNamespace().equals("packagedexcrafting")
                || catalystId.getNamespace().equals("packagedavaritia")
                || catalystId.getNamespace().equals("packageddraconic");
    }

    public static void send(ServerPlayer player) {
        var entries = AggregatePatternLibrary.get(Objects.requireNonNull(player.getServer())).entries().stream()
                .map(entry -> new AggregateMetadataView.Entry(
                        entry.libraryId(), entry.catalystId(), entry.machineTranslationKey(),
                        entry.contentHash(), entry.recipeCount(), entry.seriesHash(), entry.batchSize(),
                        entry.batchIndex(), entry.batchCount(), entry.totalRecipeCount(),
                        entry.batchCount() == 1
                                && !serverOwnedPackagedCatalog(entry.catalystId())
                                && AggregateStartupRefreshState.isRequired(player.getServer(), entry.libraryId())))
                .toList();
        BindingNetwork.sendToPlayer(player, new AggregateMetadataPayload(entries));
    }

    public static void sendToOnlinePlayers(MinecraftServer server) {
        server.getPlayerList().getPlayers().forEach(AggregateMetadataSyncService::send);
    }
}
