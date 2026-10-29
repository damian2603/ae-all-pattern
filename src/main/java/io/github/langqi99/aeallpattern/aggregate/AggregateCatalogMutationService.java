package io.github.langqi99.aeallpattern.aggregate;

import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/**
 * Shared mutation path for server-native and JEI/EMI-generated catalogs.
 *
 * <p>Generating an unrelated aggregate is not a datapack reload. In particular,
 * never globally drop the 18k-child expansion caches and republish every
 * AdvancedAE/ExtendedAE provider just because a new pattern was created.</p>
 */
public final class AggregateCatalogMutationService {
    private AggregateCatalogMutationService() {
    }

    public static AggregatePatternRef put(
            MinecraftServer server, ResourceLocation catalystId,
            String machineTranslationKey, List<AggregateRecipe> recipes) {
        AggregatePatternLibrary library = AggregatePatternLibrary.get(server);
        UUID existing = library.entries().stream()
                .filter(entry -> entry.batchCount() == 1
                        && entry.catalystId().equals(catalystId))
                .findFirst()
                .map(AggregatePatternLibrary.Entry::libraryId)
                .orElse(null);
        List<AggregateRecipe> previous = existing == null
                ? List.of() : library.recipes(server, existing).orElse(List.of());
        // Clicking the same machine again often yields an identical catalog.
        // Return another valid physical item without touching pages or ME indexes.
        if (existing != null && previous.equals(recipes)) {
            return new AggregatePatternRef(existing, catalystId);
        }
        AggregatePatternRef ref = library.put(server, catalystId, machineTranslationKey, recipes);
        if (existing != null) {
            // put() deliberately reuses the UUID so physical copies of this
            // aggregate continue pointing at the new catalog. Only providers
            // holding this UUID need to be reindexed.
            refreshOnlyChangedLibrary(server, ref.libraryId(), previous);
        }
        return ref;
    }

    public static AggregatePatternRef replace(
            MinecraftServer server, UUID libraryId, ResourceLocation catalystId,
            String machineTranslationKey, List<AggregateRecipe> recipes) {
        AggregatePatternLibrary library = AggregatePatternLibrary.get(server);
        List<AggregateRecipe> previous = library.recipes(server, libraryId).orElse(List.of());
        AggregatePatternRef ref = library.replace(
                server, libraryId, catalystId, machineTranslationKey, recipes);
        refreshOnlyChangedLibrary(server, ref.libraryId(), previous);
        return ref;
    }

    private static void refreshOnlyChangedLibrary(
            MinecraftServer server, UUID libraryId, List<AggregateRecipe> previous) {
        AggregatePatternExpander.invalidateLibrary(server, libraryId, previous);
        AggregateProviderRefreshService.requestRefreshForLibrary(server, libraryId);
    }
}
