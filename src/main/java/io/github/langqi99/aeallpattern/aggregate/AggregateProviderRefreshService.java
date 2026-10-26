package io.github.langqi99.aeallpattern.aggregate;

import io.github.langqi99.aeallpattern.AeAllPattern;
import io.github.langqi99.aeallpattern.recipe.RecipeIndexService;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.OnDatapackSyncEvent;

/** Refreshes loaded AE2 pattern providers after server recipes finish reloading. */
public final class AggregateProviderRefreshService {
    private static final Map<MinecraftServer, List<TrackedRefresh>> PROVIDERS =
            new WeakHashMap<>();
    private static final Map<MinecraftServer, Long> PENDING = new WeakHashMap<>();
    private static final Map<MinecraftServer, Long> REFRESHED = new WeakHashMap<>();
    // A provider can first register before AE2 has finished rebuilding the ME grid.
    // Two bounded post-load retries publish patterns without a permanent polling loop.
    private static final Map<MinecraftServer, List<DelayedRefresh>> DELAYED = new WeakHashMap<>();

    private AggregateProviderRefreshService() {
    }

    private record TrackedRefresh(WeakReference<Object> owner, Consumer<Object> refresh) {
    }

    private record DelayedRefresh(TrackedRefresh tracked, long dueTick) {
    }

    public static synchronized void track(MinecraftServer server, Object owner, Consumer<Object> refresh) {
        List<TrackedRefresh> providers =
                PROVIDERS.computeIfAbsent(server, ignored -> new ArrayList<>());
        providers.removeIf(tracked -> tracked.owner().get() == null);
        if (providers.stream().noneMatch(tracked -> tracked.owner().get() == owner)) {
            TrackedRefresh registered = new TrackedRefresh(new WeakReference<>(owner), refresh);
            providers.add(registered);
            // Network startup may happen *after* the server datapack event. Without
            // these retries, only the explicit /aeallpattern refresh command can
            // re-index some addon providers (notably AdvancedAE).
            long now = server.overworld().getGameTime();
            List<DelayedRefresh> scheduled = DELAYED.computeIfAbsent(server, ignored -> new ArrayList<>());
            scheduled.add(new DelayedRefresh(registered, now + 40));
            scheduled.add(new DelayedRefresh(registered, now + 120));
        }
    }

    public static synchronized void onDatapackSync(OnDatapackSyncEvent event) {
        MinecraftServer server = event.getPlayerList().getServer();
        long generation = RecipeIndexService.generation();
        if (!REFRESHED.getOrDefault(server, 0L).equals(generation)) {
            PENDING.put(server, generation);
        }
    }

    /** Schedules every tracked provider to observe a replaced aggregate library entry. */
    public static synchronized void requestRefresh(MinecraftServer server) {
        PENDING.put(server, RecipeIndexService.generation());
    }

    /**
     * Called every server tick. Runs event-driven refreshes immediately and startup
     * recovery twice, then stops. The steady-state path does no catalog rebuilds.
     */
    public static void tickServer(MinecraftServer server) {
        LinkedHashSet<TrackedRefresh> due = new LinkedHashSet<>();
        synchronized (AggregateProviderRefreshService.class) {
            Long generation = PENDING.remove(server);
            if (generation != null && generation == RecipeIndexService.generation()) {
                REFRESHED.put(server, generation);
                List<TrackedRefresh> providers = PROVIDERS.get(server);
                if (providers != null) {
                    providers.removeIf(tracked -> tracked.owner().get() == null);
                    due.addAll(providers);
                }
            }

            List<DelayedRefresh> delayed = DELAYED.get(server);
            if (delayed != null) {
                long tick = server.overworld().getGameTime();
                Iterator<DelayedRefresh> iterator = delayed.iterator();
                while (iterator.hasNext()) {
                    DelayedRefresh entry = iterator.next();
                    if (entry.tracked().owner().get() == null) {
                        iterator.remove();
                    } else if (entry.dueTick() <= tick) {
                        due.add(entry.tracked());
                        iterator.remove();
                    }
                }
                if (delayed.isEmpty()) {
                    DELAYED.remove(server);
                }
            }
        }
        for (TrackedRefresh tracked : due) {
            Object owner = tracked.owner().get();
            if (owner == null) {
                continue;
            }
            try {
                tracked.refresh().accept(owner);
            } catch (RuntimeException error) {
                AeAllPattern.LOGGER.warn("Failed to refresh aggregate recipes in {}",
                        owner.getClass().getName(), error);
            }
        }
    }
}
