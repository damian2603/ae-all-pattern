package io.github.langqi99.aeallpattern.network;

import io.github.langqi99.aeallpattern.config.AeAllPatternCommonConfig;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-side authority for mutating shared aggregate catalogs.
 *
 * <p>Recipe-viewer uploads are client-supplied data. Creating a new catalog is a normal
 * generator action, but replacing an existing shared UUID can affect every provider that
 * already references it. By default only operators (and the singleplayer host) may do that.
 * Server-native machine adapters remain trusted because they read the server recipe manager.</p>
 */
public final class AggregateCatalogAuthority {
    private AggregateCatalogAuthority() {
    }

    public static boolean canReplaceSharedCatalog(ServerPlayer player) {
        int required = AeAllPatternCommonConfig.SHARED_CATALOG_REFRESH_PERMISSION_LEVEL.get();
        return player.hasPermissions(required)
                || player.getServer().isSingleplayerOwner(player.getGameProfile());
    }
}
