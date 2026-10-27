package io.github.langqi99.aeallpattern.machine;

import appeng.api.networking.IManagedGridNode;
import io.github.langqi99.aeallpattern.AeAllPattern;
import io.github.langqi99.aeallpattern.aggregate.AggregateProviderRefreshService;
import io.github.langqi99.aeallpattern.registry.ModDataComponents;
import io.github.langqi99.aeallpattern.registry.ModItems;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Dependency-free bridge for aggregate patterns in PackagedAuto's ME packaging provider. */
public final class PackagedAutoAggregateCompat {
    private PackagedAutoAggregateCompat() {
    }

    public static boolean isAggregatePattern(ItemStack stack) {
        return stack.is(ModItems.AGGREGATE_PATTERN.get())
                && ModDataComponents.hasAggregatePattern(stack);
    }

    public static boolean hasAggregatePattern(Object provider) {
        try {
            Object handler = provider.getClass().getMethod("getItemHandler").invoke(provider);
            ItemStack stack = (ItemStack) handler.getClass()
                    .getMethod("getStackInSlot", int.class).invoke(handler, 0);
            return isAggregatePattern(stack);
        } catch (ReflectiveOperationException | RuntimeException error) {
            AeAllPattern.LOGGER.debug("Failed to inspect ME packaging provider pattern", error);
            return false;
        }
    }

    /** Tests whether a packaging provider holds the changed saved catalog. */
    public static boolean containsLibrary(Object provider, java.util.UUID libraryId) {
        if (provider == null || libraryId == null) {
            return false;
        }
        try {
            Object handler = provider.getClass().getMethod("getItemHandler").invoke(provider);
            ItemStack stack = (ItemStack) handler.getClass()
                    .getMethod("getStackInSlot", int.class).invoke(handler, 0);
            if (!isAggregatePattern(stack)) {
                return false;
            }
            var ref = ModDataComponents.getAggregatePattern(stack);
            return ref != null && libraryId.equals(ref.libraryId());
        } catch (ReflectiveOperationException | RuntimeException error) {
            AeAllPattern.LOGGER.debug("Failed to resolve packaging provider catalog UUID", error);
            return false;
        }
    }

    /**
     * PackagingProviderItemHandler.load() may run before its block entity has a world.
     * Retry the aggregate catalog on the first server tick and register for later JEI/EMI rescans.
     */
    public static void refreshLoadedProvider(Object provider) {
        if (!hasAggregatePattern(provider)) {
            return;
        }
        refreshProviderCatalog(provider);
    }

    /** Used by the server-side catalog refresh service; does not modify physical pattern items. */
    private static void refreshProviderCatalog(Object provider) {
        if (!(provider instanceof BlockEntity entity) || entity.isRemoved()
                || !(entity.getLevel() instanceof ServerLevel)) {
            return;
        }
        try {
            Object handler = provider.getClass().getMethod("getItemHandler").invoke(provider);
            refreshRecipeList(handler);
        } catch (ReflectiveOperationException | RuntimeException error) {
            AeAllPattern.LOGGER.warn("Could not restore ME packaging provider aggregate recipes", error);
        }
    }

    public static void refreshRecipeList(Object handler) {
        try {
            ItemStack stack = (ItemStack) handler.getClass()
                    .getMethod("getStackInSlot", int.class).invoke(handler, 0);
            if (!isAggregatePattern(stack)) {
                return;
            }
            Field blockEntityField = handler.getClass().getField("blockEntity");
            Object provider = blockEntityField.get(handler);
            if (!(provider instanceof BlockEntity blockEntity)
                    || !(blockEntity.getLevel() instanceof ServerLevel level)) {
                return;
            }
            // PackagedAuto only calls updateRecipeList for inventory changes. A catalog
            // refresh changes the recipes behind a UUID without changing the ItemStack.
            AggregateProviderRefreshService.track(
                    level.getServer(), provider, PackagedAutoAggregateCompat::refreshProviderCatalog,
                    null, PackagedAutoAggregateCompat::containsLibrary);
            @SuppressWarnings("unchecked")
            List<Object> recipeList = (List<Object>) provider.getClass().getField("recipeList").get(provider);
            recipeList.clear();
            recipeList.addAll(PackagedCraftingAdapter.packageRecipeInfos(level, stack));
            provider.getClass().getMethod("postPatternChange").invoke(provider);
        } catch (ReflectiveOperationException | RuntimeException error) {
            AeAllPattern.LOGGER.debug("Failed to refresh aggregate recipes in ME packaging provider", error);
        }
    }

    public static List<?> usePackageWorkflow(Object provider, List<?> originalPatterns) {
        if (!hasAggregatePattern(provider)) {
            return originalPatterns;
        }
        try {
            Object nodeObject = provider.getClass().getMethod("getMainNode").invoke(provider);
            if (!(nodeObject instanceof IManagedGridNode node) || !node.isActive()
                    || !(provider instanceof BlockEntity blockEntity)
                    || !(blockEntity.getLevel() instanceof ServerLevel level)) {
                return List.of();
            }

            ClassLoader loader = provider.getClass().getClassLoader();
            Class<?> recipeInfoType = Class.forName("thelm.packagedauto.api.IPackageRecipeInfo", false, loader);
            Class<?> packageDetails = Class.forName(
                    "thelm.packagedauto.integration.appeng.recipe.PackageCraftingPatternDetails", false, loader);
            Class<?> recipeDetails = Class.forName(
                    "thelm.packagedauto.integration.appeng.recipe.RecipeCraftingPatternDetails", false, loader);
            Method isCraftable = recipeInfoType.getMethod("isCraftable");
            Method isPackageable = recipeInfoType.getMethod("isPackageable");
            Method getPatterns = recipeInfoType.getMethod("getPatterns");
            Method getExtraPatterns = recipeInfoType.getMethod("getExtraPatterns");

            List<?> recipes = (List<?>) provider.getClass().getField("recipeList").get(provider);
            List<Object> result = new ArrayList<>();
            for (Object recipe : recipes) {
                // Aggregate workflows must always go through PackagedAuto's package/unpackage
                // stages. Exposing a direct shortcut bypasses the target machine and defeats
                // the atomic dispatch that PackagedExCrafting requires.
                if ((boolean) isPackageable.invoke(recipe)) {
                    addPackagePatterns(result, (List<?>) getPatterns.invoke(recipe), packageDetails,
                            level.registryAccess());
                    addPackagePatterns(result, (List<?>) getExtraPatterns.invoke(recipe), packageDetails,
                            level.registryAccess());
                }
                if ((boolean) isCraftable.invoke(recipe)) {
                    result.add(newPatternDetails(recipeDetails, recipe, level.registryAccess()));
                }
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException error) {
            AeAllPattern.LOGGER.debug("Failed to publish aggregate package workflow", error);
            return originalPatterns;
        }
    }

    private static void addPackagePatterns(
            List<Object> result,
            List<?> patterns,
            Class<?> packageDetails,
            HolderLookup.Provider registries) throws ReflectiveOperationException {
        for (Object pattern : patterns) {
            result.add(newPatternDetails(packageDetails, pattern, registries));
        }
    }

    private static Object newPatternDetails(
            Class<?> detailsType,
            Object recipeOrPattern,
            HolderLookup.Provider registries) throws ReflectiveOperationException {
        for (Constructor<?> constructor : detailsType.getConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 2
                    && parameterTypes[0].isInstance(recipeOrPattern)
                    && parameterTypes[1].isInstance(registries)) {
                return constructor.newInstance(recipeOrPattern, registries);
            }
        }
        for (Constructor<?> constructor : detailsType.getConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 1 && parameterTypes[0].isInstance(recipeOrPattern)) {
                return constructor.newInstance(recipeOrPattern);
            }
        }
        StringBuilder signatures = new StringBuilder();
        for (Constructor<?> constructor : detailsType.getConstructors()) {
            if (!signatures.isEmpty()) {
                signatures.append(", ");
            }
            signatures.append(constructor);
        }
        throw new NoSuchMethodException("No compatible constructor found for " + detailsType.getName()
                + " with " + recipeOrPattern.getClass().getName() + " and " + registries.getClass().getName()
                + "; available: " + signatures);
    }
}
