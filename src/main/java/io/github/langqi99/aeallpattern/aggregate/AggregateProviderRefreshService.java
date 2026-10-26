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
import java.util.function.Predicate;
import java.util.function.BiPredicate;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.OnDatapackSyncEvent;

/** Refreshes loaded AE2 pattern providers after server recipes finish reloading. */
public final class AggregateProviderRefreshService {
    private static final Map<MinecraftServer, List<TrackedRefresh>> PROVIDERS =
            new WeakHashMap<>();
    private static final Map<MinecraftServer, Long> PENDING = new WeakHashMap<>();
    // Targeted catalog writes are unrelated to global datapack reloads.
    private static final Map<MinecraftServer, java.util.Set<UUID>> PENDING_LIBRARIES = new WeakHashMap<>();
    private static final Map<MinecraftServer, Long> REFRESHED = new WeakHashMap<>();
    // A provider can first register before AE2 has finished rebuilding the ME grid.
    // Two bounded post-load retries publish patterns without a permanent polling loop.
    private static final Map<MinecraftServer, List<DelayedRefresh>> DELAYED = new WeakHashMap<>();
    // One inexpensive health probe each second, rotating across aggregate-bearing
    // providers. Unlike unconditional requests, healthy networks are never rebuilt.
    private static final Map<MinecraftServer, Integer> AUDIT_CURSOR = new WeakHashMap<>();
    // If an addon cannot currently publish valid children (unloaded chunk,
    // disconnected ME channel or filtered-only catalog), avoid a refresh storm.
    private static final Map<MinecraftServer, Map<Object, Long>> AUTO_REPAIRED_AT = new WeakHashMap<>();
    private static final long AUTO_REPAIR_COOLDOWN_TICKS = 200;

    private AggregateProviderRefreshService() {
    }

    private record TrackedRefresh(
            WeakReference<Object> owner, Consumer<Object> refresh,
            Predicate<Object> healthy, BiPredicate<Object, UUID> containsLibrary) {
    }

    private record DelayedRefresh(TrackedRefresh tracked, long dueTick) {
    }

    public static synchronized void track(MinecraftServer server, Object owner, Consumer<Object> refresh) {
        track(server, owner, refresh, null, null);
    }

    /**
     * Health predicates must NOT capture the tracked owner: tracked providers are
     * held weakly to avoid retaining unloaded chunks indefinitely.
     */
    public static synchronized void track(
            MinecraftServer server, Object owner,
            Consumer<Object> refresh, Predicate<Object> healthy) {
        track(server, owner, refresh, healthy, null);
    }

    /** The final predicate is side-effect-free and must not capture owner. */
    public static synchronized void track(
            MinecraftServer server, Object owner,
            Consumer<Object> refresh, Predicate<Object> healthy,
            BiPredicate<Object, UUID> containsLibrary) {
        List<TrackedRefresh> providers =
                PROVIDERS.computeIfAbsent(server, ignored -> new ArrayList<>());
        providers.removeIf(tracked -> tracked.owner().get() == null);
        if (providers.stream().noneMatch(tracked -> tracked.owner().get() == owner)) {
            TrackedRefresh registered = new TrackedRefresh(new WeakReference<>(owner), refresh, healthy, containsLibrary);
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
     * Republish only providers actually containing a changed catalog. A newly
     * generated unrelated machine pattern must not flush a large ME network.
     */
    public static synchronized void requestRefreshForLibrary(MinecraftServer server, UUID libraryId) {
        PENDING_LIBRARIES.computeIfAbsent(server, ignored -> new LinkedHashSet<>()).add(libraryId);
    }

    /**
     * Called every server tick. Runs event-driven refreshes immediately and startup
     * recovery twice, then stops. The steady-state path does no catalog rebuilds.
     */
    public static void tickServer(MinecraftServer server) {
        LinkedHashSet<TrackedRefresh> due = new LinkedHashSet<>();
        TrackedRefresh audit = null;
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

            java.util.Set<UUID> changedLibraries = PENDING_LIBRARIES.remove(server);
            if (changedLibraries != null && !changedLibraries.isEmpty()) {
                List<TrackedRefresh> providers = PROVIDERS.get(server);
                if (providers != null) {
                    providers.removeIf(tracked -> tracked.owner().get() == null);
                    for (TrackedRefresh tracked : providers) {
                        Object owner = tracked.owner().get();
                        if (owner != null && tracked.containsLibrary() != null) {
                            for (UUID libraryId : changedLibraries) {
                                if (tracked.containsLibrary().test(owner, libraryId)) {
                                    due.add(tracked);
                                    break;
                                }
                            }
                        }
                    }
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

            // The AE2 crafting index may lose a provider long after login,
            // without notifying the underlying addon logic. Examine one
            // provider per second, at a stable O(1) lookup cost.
            if (server.overworld().getGameTime() % 20L == 0L) {
                List<TrackedRefresh> providers = PROVIDERS.get(server);
                if (providers != null) {
                    providers.removeIf(tracked -> tracked.owner().get() == null);
                    List<TrackedRefresh> monitored = providers.stream()
                            .filter(tracked -> tracked.healthy() != null).toList();
                    if (!monitored.isEmpty()) {
                        int cursor = AUDIT_CURSOR.getOrDefault(server, 0);
                        audit = monitored.get(Math.floorMod(cursor, monitored.size()));
                        AUDIT_CURSOR.put(server, (cursor + 1) & Integer.MAX_VALUE);
                    }
                }
            }
        }
        if (audit != null) {
            Object owner = audit.owner().get();
            if (owner != null) {
                try {
                    if (!audit.healthy().test(owner)) {
                        long now = server.overworld().getGameTime();
                        boolean shouldRepair;
                        synchronized (AggregateProviderRefreshService.class) {
                            Map<Object, Long> repaired = AUTO_REPAIRED_AT.computeIfAbsent(
                                    server, ignored -> new WeakHashMap<>());
                            Long last = repaired.get(owner);
                            shouldRepair = last == null || now - last >= AUTO_REPAIR_COOLDOWN_TICKS;
                            if (shouldRepair) {
                                repaired.put(owner, now);
                            }
                        }
                        if (shouldRepair) {
                            AeAllPattern.LOGGER.warn(
                                    "AE2 crafting index dropped an Aggregate Pattern from {}; republishing it",
                                    owner.getClass().getName());
                            due.add(audit);
                        }
                    }
                } catch (RuntimeException error) {
                    AeAllPattern.LOGGER.warn(
                            "Could not verify aggregate crafting index health for {}",
                            owner.getClass().getName(), error);
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
