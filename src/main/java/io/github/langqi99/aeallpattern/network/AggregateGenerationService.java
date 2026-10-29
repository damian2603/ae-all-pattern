package io.github.langqi99.aeallpattern.network;

import io.github.langqi99.aeallpattern.AeAllPattern;

import io.github.langqi99.aeallpattern.aggregate.AggregatePatternLibrary;
import io.github.langqi99.aeallpattern.aggregate.AggregatePatternData;
import io.github.langqi99.aeallpattern.aggregate.AggregateCatalogMutationService;
import io.github.langqi99.aeallpattern.aggregate.AggregateRecipe;
import io.github.langqi99.aeallpattern.machine.MachineAdapterRegistry;
import io.github.langqi99.aeallpattern.registry.ModDataComponents;
import io.github.langqi99.aeallpattern.registry.ModItems;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;

/** Validates and assembles bounded JEI scan pages before updating the server library. */
@SuppressWarnings("deprecation")
public final class AggregateGenerationService {
    private static final long UPLOAD_TIMEOUT_TICKS = 20L * 120L;
    // A client must not pin unlimited concurrent large upload catalogs in server RAM.
    private static final int MAX_CONCURRENT_UPLOADS_PER_PLAYER = 2;
    private static final long MAX_BUFFERED_UPLOAD_BYTES = 32L * 1024 * 1024;
    private static final int MAX_SERVER_PAGE_RECIPE_BYTES = 20_000;
    private static final Map<UploadKey, Upload> UPLOADS = new HashMap<>();

    private AggregateGenerationService() {
    }

    /** Never hold an unfinished client upload after disconnecting or leaving a world. */
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            UPLOADS.keySet().removeIf(key -> key.playerId().equals(player.getUUID()));
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        UPLOADS.clear();
    }

    public static void handle(GenerateAggregatePayload payload, ServerPlayer player) {
        long now = player.level().getGameTime();
        UPLOADS.entrySet().removeIf(entry -> now - entry.getValue().lastUpdateTick > UPLOAD_TIMEOUT_TICKS);
        if (!validTarget(payload, player)) {
            return;
        }
        if (payload.totalRecipeCount() > AggregatePatternData.configuredRecipeLimit()) {
            return;
        }

        UploadKey key = new UploadKey(player.getUUID(), payload.uploadId());
        if (!UPLOADS.containsKey(key) && UPLOADS.keySet().stream()
                .filter(existing -> existing.playerId().equals(player.getUUID()))
                .count() >= MAX_CONCURRENT_UPLOADS_PER_PLAYER) {
            AeAllPattern.LOGGER.warn("Rejecting excess concurrent aggregate uploads from {}", player.getUUID());
            return;
        }
        Upload upload = UPLOADS.computeIfAbsent(key, ignored -> new Upload(payload, now));
        if (!upload.matches(payload) || !upload.add(payload, now)) {
            UPLOADS.remove(key);
            return;
        }
        if (!upload.complete()) {
            return;
        }
        UPLOADS.remove(key);
        List<AggregateRecipe> recipes = upload.flatten();
        if (recipes.size() != payload.totalRecipeCount()) {
            return;
        }
        var library = AggregatePatternLibrary.get(Objects.requireNonNull(player.getServer()));
        boolean refresh = payload.replacementLibraryId() != null;
        // Startup viewer registration may be incomplete even if JEI/EMI reports a
        // NONEMPTY recipe list. Do not replace a persisted catalog if a known
        // recipe disappears: a 2,000 -> 1,900 scan is not proof that 100 recipes
        // were removed from the pack. Explicit generator use can still rebuild
        // that same UUID via AggregateCatalogMutationService.put().
        if (refresh) {
            var existing = library.find(payload.replacementLibraryId());
            if (existing.isPresent()) {
                int savedCount = existing.orElseThrow().recipeCount();
                if (savedCount > 0) {
                    var savedRecipes = library.recipes(player.getServer(), payload.replacementLibraryId());
                    List<String> savedIds = savedRecipes
                            .map(saved -> saved.stream().map(AggregateRecipe::patternId).toList())
                            .orElseGet(List::of);
                    List<String> incomingIds = recipes.stream().map(AggregateRecipe::patternId).toList();
                    if (shouldPreserveOnIncompleteRefresh(
                            savedCount, savedIds, incomingIds, savedRecipes.isPresent())) {
                        AeAllPattern.LOGGER.warn(
                                "Ignoring incomplete JEI/EMI startup scan for {}: saved={}, incoming={}; "
                                        + "keeping saved recipes. Use the generator on the machine "
                                        + "to deliberately rebuild a changed catalog.",
                                payload.replacementLibraryId(), savedCount, recipes.size());
                        // Mark this server-session refresh as handled, so reconnecting
                        // clients don't retry the same incomplete viewer snapshot.
                        AggregateStartupRefreshState.markComplete(player.getServer(), payload.replacementLibraryId());
                        AggregateMetadataSyncService.sendToOnlinePlayers(player.getServer());
                        return;
                    }
                }
            }
        }
        var ref = refresh
                ? AggregateCatalogMutationService.replace(
                        player.getServer(), payload.replacementLibraryId(),
                        payload.catalystId(), payload.machineTranslationKey(), recipes)
                : AggregateCatalogMutationService.put(player.getServer(), payload.catalystId(),
                        payload.machineTranslationKey(), recipes);
        AggregateStartupRefreshState.markComplete(player.getServer(), ref.libraryId());
        AggregateMetadataSyncService.sendToOnlinePlayers(player.getServer());

        if (refresh) {
            return;
        }

        ItemStack aggregate = new ItemStack(ModItems.AGGREGATE_PATTERN.get());
        ModDataComponents.setAggregatePattern(aggregate, ref);
        if (!player.addItem(aggregate)) {
            player.drop(aggregate, false);
        }
        player.level().playSound(
                null, payload.machinePos(), SoundEvents.EXPERIENCE_ORB_PICKUP,
                SoundSource.PLAYERS, 0.6F, 1.25F);
        player.displayClientMessage(Component.translatable(
                "message.aeallpattern.generator.created",
                Component.translatable(payload.machineTranslationKey()), recipes.size()), true);
    }

    /**
     * A startup viewer scan is advisory: missing IDs or fewer recipes may mean
     * JEI/EMI has not yet loaded every category. Never delete saved entries
     * based on such a scan. This is not used for explicit generator actions.
     */
    static boolean shouldPreserveOnIncompleteRefresh(
            int savedCount, List<String> savedPatternIds,
            List<String> incomingPatternIds, boolean savedPagesReadable) {
        if (savedCount <= 0) {
            return false;
        }
        if (!savedPagesReadable || savedPatternIds.size() != savedCount
                || incomingPatternIds.size() < savedCount) {
            return true;
        }
        return !new HashSet<>(incomingPatternIds).containsAll(savedPatternIds);
    }


    private static boolean validTarget(GenerateAggregatePayload payload, ServerPlayer player) {
        if (payload.replacementLibraryId() != null) {
            return AggregatePatternLibrary.get(Objects.requireNonNull(player.getServer()))
                    .find(payload.replacementLibraryId())
                    .filter(entry -> entry.batchCount() == 1)
                    .filter(entry -> entry.catalystId().equals(payload.catalystId()))
                    .filter(entry -> entry.machineTranslationKey().equals(payload.machineTranslationKey()))
                    .isPresent();
        }
        // The client scan runs across many ticks, so the player legitimately walks away from
        // the machine while it completes. Enforce the machine identity instead of proximity:
        // the clicked block must still be the same block, and the player must still hold the
        // generator item.
        if (!holdsGenerator(player)
                || !player.level().hasChunkAt(payload.machinePos())) {
            return false;
        }
        var block = player.level().getBlockState(payload.machinePos()).getBlock();
        if (!BuiltInRegistries.BLOCK.getKey(block).equals(payload.catalystId())
                || !block.getDescriptionId().equals(payload.machineTranslationKey())) {
            return false;
        }
        // Exact server-side adapters own their recipe catalog. Do not let a client
        // JEI scan create a second, category-wide aggregate for the same machine.
        var target = player.level().getBlockEntity(payload.machinePos());
        // Blocks without block entities (currently the crafting table) use native AE
        // crafting patterns and have no server-side machine adapter to validate.
        return target == null || MachineAdapterRegistry.find(player.serverLevel(), target).isEmpty();
    }

    private static boolean holdsGenerator(ServerPlayer player) {
        return player.getMainHandItem().is(ModItems.ALL_PATTERN_GENERATOR.get())
                || player.getOffhandItem().is(ModItems.ALL_PATTERN_GENERATOR.get());
    }

    private record UploadKey(UUID playerId, UUID uploadId) {
    }

    private static final class Upload {
        private final BlockPos machinePos;
        private final ResourceLocation catalystId;
        private final String machineKey;
        private final UUID replacementLibraryId;
        private final int pageCount;
        private final int totalRecipeCount;
        private final Map<Integer, List<AggregateRecipe>> pages = new HashMap<>();
        private int bufferedRecipes;
        private long bufferedBytes;
        private long lastUpdateTick;

        private Upload(GenerateAggregatePayload first, long now) {
            machinePos = first.machinePos();
            catalystId = first.catalystId();
            machineKey = first.machineTranslationKey();
            replacementLibraryId = first.replacementLibraryId();
            pageCount = first.pageCount();
            totalRecipeCount = first.totalRecipeCount();
            lastUpdateTick = now;
        }

        private boolean matches(GenerateAggregatePayload page) {
            return machinePos.equals(page.machinePos())
                    && catalystId.equals(page.catalystId())
                    && machineKey.equals(page.machineTranslationKey())
                    && Objects.equals(replacementLibraryId, page.replacementLibraryId())
                    && pageCount == page.pageCount()
                    && totalRecipeCount == page.totalRecipeCount();
        }

        private boolean add(GenerateAggregatePayload page, long now) {
            List<AggregateRecipe> previous = pages.get(page.pageIndex());
            if (previous != null && !previous.equals(page.recipes())) {
                return false;
            }
            if (previous == null) {
                if ((long) bufferedRecipes + page.recipes().size() > totalRecipeCount) {
                    return false;
                }
                long pageBytes = 0;
                for (AggregateRecipe recipe : page.recipes()) {
                    pageBytes += recipe.consumeEncodedSize();
                    if (pageBytes > MAX_SERVER_PAGE_RECIPE_BYTES) {
                        return false;
                    }
                }
                if (bufferedBytes + pageBytes > MAX_BUFFERED_UPLOAD_BYTES) {
                    return false;
                }
                bufferedRecipes += page.recipes().size();
                bufferedBytes += pageBytes;
                pages.put(page.pageIndex(), page.recipes());
            }
            lastUpdateTick = now;
            return true;
        }

        private boolean complete() {
            return pages.size() == pageCount && bufferedRecipes == totalRecipeCount;
        }

        private List<AggregateRecipe> flatten() {
            List<AggregateRecipe> recipes = new ArrayList<>(totalRecipeCount);
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                recipes.addAll(pages.get(pageIndex));
            }
            return List.copyOf(recipes);
        }
    }
}
