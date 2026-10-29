package io.github.langqi99.aeallpattern.machine;

import appeng.api.stacks.AEItemKey;
import io.github.langqi99.aeallpattern.AeAllPattern;
import io.github.langqi99.aeallpattern.aggregate.AggregateRecipe;
import io.github.langqi99.aeallpattern.binding.BindingRecord;
import io.github.langqi99.aeallpattern.recipe.RecipeCatalog;
import io.github.langqi99.aeallpattern.recipe.RecipeFingerprint;
import io.github.langqi99.aeallpattern.recipe.RecipeSnapshot;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

/**
 * Optional Forge 1.20.1 bridge for PackagedDraconic's Fusion Package Crafter.
 *
 * <p>The generic processing-package recipe is NOT accepted by the fusion crafter.
 * Preserve the fusion recipe id, core catalyst and injector stacks in the addon
 * specific FusionPackageRecipeInfo instead. All interactions use reflection so
 * neither PackagedDraconic nor Draconic Evolution is a required dependency.</p>
 */
final class PackagedDraconicAdapter implements MachineAdapter {
    static final ResourceLocation MACHINE = new ResourceLocation("packageddraconic", "fusion_crafter");
    private static final ResourceLocation RECIPE_TYPE =
            new ResourceLocation("draconicevolution", "fusion_crafting");
    private static final ResourceLocation ADAPTER_ID =
            new ResourceLocation("aeallpattern", "packaged_draconic_fusion");

    @Override
    public ResourceLocation id() {
        return ADAPTER_ID;
    }

    @Override
    public int schemaVersion() {
        return 1;
    }

    @Override
    public boolean supports(ServerLevel level, BlockEntity target) {
        return MACHINE.equals(BuiltInRegistries.BLOCK.getKey(target.getBlockState().getBlock()));
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public RecipeCatalog discoverRecipes(ServerLevel level, BlockEntity target, long generation) {
        RecipeType<?> type = BuiltInRegistries.RECIPE_TYPE.getOptional(RECIPE_TYPE).orElse(null);
        if (type == null) {
            return new RecipeCatalog(generation, List.of(), 0);
        }
        List<RecipeSnapshot> snapshots = new ArrayList<>();
        int filtered = 0;
        List<Recipe<?>> recipes = (List) level.getRecipeManager().getAllRecipesFor((RecipeType) type);
        for (Recipe<?> recipe : recipes.stream()
                .sorted(Comparator.comparing(value -> value.getId().toString())).toList()) {
            try {
                List<List<ItemStack>> alternatives = alternatives(recipe);
                List<ItemStack> representative = alternatives.stream().map(stacks -> stacks.get(0)).toList();
                Object info = recipeInfo(recipe.getId(), representative);
                ItemStack output = output(info);
                if (output.isEmpty() || !valid(info)) {
                    filtered++;
                    continue;
                }
                String normalized = alternatives.stream().map(stacks ->
                        stacks.stream().map(PackagedDraconicAdapter::key).toList().toString()).toList().toString();
                RecipeFingerprint fingerprint = new RecipeFingerprint(id().toString(),
                        recipe.getId().toString(), normalized, key(output), schemaVersion());
                snapshots.add(RecipeSnapshot.withAlternatives(
                        recipe.getId(), alternatives, output, fingerprint, 200));
            } catch (ReflectiveOperationException | RuntimeException error) {
                filtered++;
                AeAllPattern.LOGGER.debug("Skipping unsupported Draconic fusion recipe {}", recipe.getId(), error);
            }
        }
        return new RecipeCatalog(generation, snapshots, filtered);
    }

    /**
     * Order is the core catalyst followed by the actual fusion injectors. The
     * catalyst count is preserved (IngredientStack), and alternate tag items are
     * represented as native AE processing input alternatives.
     */
    private static List<List<ItemStack>> alternatives(Recipe<?> recipe) throws ReflectiveOperationException {
        Class<?> fusion = Class.forName("com.brandon3055.draconicevolution.api.crafting.IFusionRecipe");
        if (!fusion.isInstance(recipe)) {
            throw new IllegalArgumentException("Not an IFusionRecipe");
        }
        Ingredient catalyst = (Ingredient) fusion.getMethod("getCatalyst").invoke(recipe);
        List<List<ItemStack>> slots = new ArrayList<>();
        slots.add(choices(catalyst));
        @SuppressWarnings("unchecked")
        List<Object> injectors = (List<Object>) fusion.getMethod("fusionIngredients").invoke(recipe);
        // Call through the public interface: some recipe implementations use
        // private nested IFusionIngredient classes, inaccessible by reflection.
        Class<?> ingredientApi = Class.forName(
                "com.brandon3055.draconicevolution.api.crafting.IFusionRecipe$IFusionIngredient");
        Method getIngredient = ingredientApi.getMethod("get");
        for (Object injector : injectors) {
            Ingredient ingredient = (Ingredient) getIngredient.invoke(injector);
            slots.add(choices(ingredient));
        }
        if (slots.size() > AggregateRecipe.MAX_INPUTS) {
            throw new IllegalArgumentException("Too many Draconic Fusion inputs: " + slots.size());
        }
        return List.copyOf(slots);
    }

    private static List<ItemStack> choices(Ingredient ingredient) {
        int max = io.github.langqi99.aeallpattern.aggregate.AggregateInputSlot.configuredAlternativeLimit();
        List<ItemStack> choices = java.util.Arrays.stream(ingredient.getItems())
                .filter(stack -> !stack.isEmpty())
                .map(ItemStack::copy)
                .sorted(Comparator.comparing(PackagedDraconicAdapter::key))
                .limit(max)
                .toList();
        if (choices.isEmpty()) {
            throw new IllegalArgumentException("Empty fusion ingredient");
        }
        return choices;
    }

    /** Reconstruct exactly the recipe type expected by the PackagedDraconic machine. */
    static Object recipeInfo(ResourceLocation recipeId, List<ItemStack> inputs)
            throws ReflectiveOperationException {
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("Missing fusion catalyst");
        }
        Class<?> infoClass = Class.forName("thelm.packageddraconic.recipe.FusionPackageRecipeInfo");
        Object info = infoClass.getConstructor().newInstance();
        CompoundTag tag = new CompoundTag();
        tag.putString("Recipe", recipeId.toString());
        tag.put("InputCore", inputs.get(0).save(new CompoundTag()));
        // PackagedAuto's MiscHelper.loadAllItems does NOT read a bare NBT
        // list in positional order. Every serialized injector needs an
        // "Index" tag; otherwise all entries overwrite list element zero and
        // the fusion crafter sees only the final injector.
        ListTag injectors = new ListTag();
        for (int index = 1; index < inputs.size(); index++) {
            CompoundTag stackTag = inputs.get(index).save(new CompoundTag());
            stackTag.putByte("Index", (byte) (index - 1));
            // Match MiscHelper.saveItemWithLargeCount instead of vanilla
            // ItemStack.save (which stores only a byte count).
            stackTag.putInt("Count", inputs.get(index).getCount());
            injectors.add(stackTag);
        }
        tag.put("InputInjector", injectors);
        infoClass.getMethod("load", CompoundTag.class).invoke(info, tag);
        return info;
    }

    static Object packageRecipeInfo(ServerLevel level, AggregateRecipe recipe) {
        try {
            Recipe<?> nativeRecipe = level.getRecipeManager().byKey(recipe.recipeId()).orElse(null);
            if (nativeRecipe == null) {
                return null;
            }
            Object info = recipeInfo(recipe.recipeId(),
                    recipe.inputs().stream()
                            .map(stack -> {
                                if (!(stack.what() instanceof AEItemKey key) || stack.amount() > Integer.MAX_VALUE) {
                                    return ItemStack.EMPTY;
                                }
                                return key.toStack((int) stack.amount());
                            })
                            .toList());
            if (!valid(info) || output(info).isEmpty()) {
                return null;
            }
            return info;
        } catch (ReflectiveOperationException | RuntimeException error) {
            AeAllPattern.LOGGER.debug("Cannot rebuild Draconic fusion package {}", recipe.recipeId(), error);
            return null;
        }
    }

    @Override
    public boolean insert(ServerLevel level, BindingRecord binding, ItemStack stack) {
        return false;
    }

    @Override
    public boolean insertRecipe(
            ServerLevel level, BindingRecord binding, RecipeSnapshot snapshot, List<ItemStack> inputs) {
        BlockEntity target = level.getBlockEntity(binding.target().pos());
        if (target == null || !supports(level, target)) {
            return false;
        }
        Recipe<?> nativeRecipe = level.getRecipeManager().byKey(snapshot.recipeId()).orElse(null);
        if (nativeRecipe == null) {
            return false;
        }
        try {
            List<List<ItemStack>> recipeSlots = alternatives(nativeRecipe);
            if (inputs.size() != recipeSlots.size()) {
                return false;
            }
            for (int i = 0; i < inputs.size(); i++) {
                final int slot = i;
                if (inputs.get(i).isEmpty() ||
                        recipeSlots.get(i).stream().noneMatch(choice -> choice.getItem() == inputs.get(slot).getItem()
                                && inputs.get(slot).getCount() >= choice.getCount())) {
                    return false;
                }
            }
            Object info = recipeInfo(snapshot.recipeId(), inputs);
            ItemStack actual = output(info);
            ItemStack expected = snapshot.output();
            if (!valid(info) || actual.isEmpty()
                    || !ItemStack.isSameItemSameTags(actual, expected)
                    || actual.getCount() != expected.getCount()) {
                return false;
            }
            Method accept = java.util.Arrays.stream(target.getClass().getMethods())
                    .filter(method -> method.getName().equals("acceptPackage")
                            && method.getParameterCount() == 3)
                    .findFirst().orElse(null);
            return accept != null && Boolean.TRUE.equals(
                    accept.invoke(target, info, inputs, binding.clickedSide()));
        } catch (ReflectiveOperationException | RuntimeException error) {
            AeAllPattern.LOGGER.debug("Draconic Fusion package was not accepted {}", snapshot.recipeId(), error);
            return false;
        }
    }

    @Override
    public ItemStack extractAnyOutput(ServerLevel level, BindingRecord binding, boolean simulate) {
        BlockEntity target = level.getBlockEntity(binding.target().pos());
        if (target == null) {
            return ItemStack.EMPTY;
        }
        // FusionCrafterItemHandler exposes slot 0 (the *catalyst*) as extractable
        // when idle. Never use a generic extractAny() here: it can steal the core
        // catalyst from the next craft. Slot 1 is the finished output only.
        for (Direction side : Direction.values()) {
            var handler = target.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
            if (handler != null && handler.getSlots() > 1) {
                ItemStack stack = handler.extractItem(1, Integer.MAX_VALUE, simulate);
                if (!stack.isEmpty()) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    static boolean valid(Object info) throws ReflectiveOperationException {
        return Boolean.TRUE.equals(info.getClass().getMethod("isValid").invoke(info));
    }

    static ItemStack output(Object info) throws ReflectiveOperationException {
        Object result = info.getClass().getMethod("getOutput").invoke(info);
        return result instanceof ItemStack stack ? stack : ItemStack.EMPTY;
    }

    private static String key(ItemStack stack) {
        return AEItemKey.of(stack) + "*" + stack.getCount();
    }
}
