package io.github.langqi99.aeallpattern.aggregate;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * AE2-terminal-style search over the full recipe list of an aggregate pattern.
 *
 * <p>Space-separated tokens are ANDed. Per-token prefixes select the matched field and a
 * leading {@code !} negates the token: {@code @} matches the mod id or display name,
 * {@code $} matches any item/fluid tag, {@code =} matches the registry id, {@code #}
 * matches the registry id too, and a bare token matches the display name. Matching is
 * case-insensitive. Used on the server against the complete recipe list so the client
 * picker can search every stored pattern, not just the initially synced subset.
 */
public final class AggregatePatternSearch {
    private AggregatePatternSearch() {
    }

    /** Filters the full recipe list; a blank query returns the leading recipes. */
    public static List<AggregatePatternSelectionMenu.Entry> filter(
            List<AggregateRecipe> recipes, String searchText, boolean searchOutputs, int limit) {
        return AggregatePatternSelectionMenu.entriesFromRecipes(filterRecipes(
                recipes, searchText, searchOutputs ? AggregateSearchMode.OUTPUTS : AggregateSearchMode.INPUTS, limit));
    }

    /** Filters by inputs and outputs together, matching AE terminal search behavior. */
    public static List<AggregatePatternSelectionMenu.Entry> filterAny(
            List<AggregateRecipe> recipes, String searchText, int limit) {
        return AggregatePatternSelectionMenu.entriesFromRecipes(
                filterRecipesAny(recipes, searchText, limit));
    }

    /** Returns matching recipes without converting them, allowing server-side UI paging. */
    public static List<AggregateRecipe> filterRecipesAny(
            List<AggregateRecipe> recipes, String searchText, int limit) {
        return filterRecipes(recipes, searchText, AggregateSearchMode.ALL, limit);
    }

    /** Searches the complete catalog in the chosen scope before any UI paging is applied. */
    public static List<AggregateRecipe> filterRecipes(
            List<AggregateRecipe> recipes, String searchText, AggregateSearchMode mode, int limit) {
        int safeLimit = Math.max(0, limit);
        if (searchText == null || searchText.isBlank()) {
            return recipes.subList(0, Math.min(safeLimit, recipes.size()));
        }
        List<AggregateRecipe> matched = new ArrayList<>(Math.min(safeLimit, recipes.size()));
        // Prepare query tokens once. Large catalogs may inspect hundreds of thousands
        // of stack keys, so repeated lowercase/split for every stack is expensive.
        String[] tokens = tokenize(searchText);
        for (AggregateRecipe recipe : recipes) {
            if (matched.size() >= safeLimit) {
                break;
            }
            if ((mode != AggregateSearchMode.OUTPUTS && matchesRecipeTokens(recipe, tokens, false))
                    || (mode != AggregateSearchMode.INPUTS && matchesRecipeTokens(recipe, tokens, true))) {
                matched.add(recipe);
            }
        }
        return List.copyOf(matched);
    }

    /** A filtered UI page plus exact totals, without storing every matching recipe. */
    public record SearchPage(
            List<AggregateRecipe> recipes, int totalMatches, int selectedMatches, int pageIndex) {
        public SearchPage {
            recipes = List.copyOf(recipes);
        }
    }

    /**
     * Count all matches and enabled recipes in one scan while retaining at most
     * one UI page. Large modpacks should not allocate a full 18k+ result list for
     * every search request. Only an out-of-range page needs a second scan.
     */
    public static SearchPage searchPage(
            List<AggregateRecipe> recipes, String query, AggregateSearchMode mode,
            int requestedPage, int pageSize, Predicate<String> enabled) {
        int safePage = Math.max(0, requestedPage);
        int safeSize = Math.max(1, pageSize);
        String[] tokens = query == null || query.isBlank() ? null : tokenize(query);
        long start = (long) safePage * safeSize;
        List<AggregateRecipe> visible = new ArrayList<>(Math.min(recipes.size(), safeSize));
        int matches = 0;
        int selected = 0;
        for (AggregateRecipe recipe : recipes) {
            if (!matchesQuery(recipe, mode, tokens)) {
                continue;
            }
            if (enabled.test(recipe.patternId())) {
                selected++;
            }
            if (matches >= start && (long) matches < start + safeSize) {
                visible.add(recipe);
            }
            matches++;
        }
        int lastPage = Math.max(0, (matches - 1) / safeSize);
        if (safePage > lastPage) {
            safePage = lastPage;
            start = (long) safePage * safeSize;
            visible.clear();
            int index = 0;
            for (AggregateRecipe recipe : recipes) {
                if (matchesQuery(recipe, mode, tokens)) {
                    if (index >= start && (long) index < start + safeSize) {
                        visible.add(recipe);
                    }
                    index++;
                    if ((long) index >= start + safeSize) {
                        break;
                    }
                }
            }
        }
        return new SearchPage(visible, matches, selected, safePage);
    }

    private static boolean matchesQuery(
            AggregateRecipe recipe, AggregateSearchMode mode, String[] tokens) {
        return tokens == null
                || ((mode != AggregateSearchMode.OUTPUTS && matchesRecipeTokens(recipe, tokens, false))
                    || (mode != AggregateSearchMode.INPUTS && matchesRecipeTokens(recipe, tokens, true)));
    }

    /** True when any input (or output, per mode) stack of the recipe matches the query. */
    public static boolean matchesRecipe(AggregateRecipe recipe, String searchText, boolean searchOutputs) {
        return matchesRecipeTokens(recipe, tokenize(searchText), searchOutputs);
    }

    private static boolean matchesRecipeTokens(
            AggregateRecipe recipe, String[] tokens, boolean searchOutputs) {
        List<GenericStack> stacks = searchOutputs ? recipe.outputs() : recipe.inputs();
        if (stacks.isEmpty()) {
            return false;
        }
        for (GenericStack stack : stacks) {
            if (stack != null && stack.what() != null && matchesStackTokens(stack.what(), tokens)) {
                return true;
            }
        }
        return false;
    }

    public static boolean matchesStack(AEKey key, String query) {
        return matchesStackTokens(key, tokenize(query));
    }

    private static String[] tokenize(String query) {
        return query.toLowerCase(Locale.ROOT).split("\\s+");
    }

    private static boolean matchesStackTokens(AEKey key, String[] tokens) {
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            boolean negate = token.charAt(0) == '!';
            String term = negate ? token.substring(1) : token;
            boolean matched;
            if (term.startsWith("@")) {
                matched = modMatches(key, term.substring(1));
            } else if (term.startsWith("$")) {
                matched = tagMatches(key, term.substring(1));
            } else if (term.startsWith("=") || term.startsWith("#")) {
                matched = registryId(key).toString().contains(term.substring(1));
            } else {
                matched = key.getDisplayName().getString().toLowerCase(Locale.ROOT).contains(term);
            }
            if (negate == matched) {
                return false;
            }
        }
        return true;
    }

    private static boolean modMatches(AEKey key, String term) {
        String modId = registryId(key).getNamespace();
        if (modId.contains(term)) {
            return true;
        }
        return net.minecraftforge.fml.ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getDisplayName().toLowerCase(Locale.ROOT).contains(term))
                .orElse(false);
    }

    @SuppressWarnings("deprecation")
    private static boolean tagMatches(AEKey key, String term) {
        if (key instanceof AEItemKey itemKey) {
            return itemKey.getItem().builtInRegistryHolder().tags()
                    .anyMatch(tag -> tag.location().toString().contains(term));
        }
        if (key instanceof AEFluidKey fluidKey) {
            return fluidKey.getFluid().builtInRegistryHolder().tags()
                    .anyMatch(tag -> tag.location().toString().contains(term));
        }
        return false;
    }

    private static ResourceLocation registryId(AEKey key) {
        if (key instanceof AEItemKey itemKey) {
            return BuiltInRegistries.ITEM.getKey(itemKey.getItem());
        }
        if (key instanceof AEFluidKey fluidKey) {
            return BuiltInRegistries.FLUID.getKey(fluidKey.getFluid());
        }
        return key.getId();
    }
}
