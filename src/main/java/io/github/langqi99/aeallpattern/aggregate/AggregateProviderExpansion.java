package io.github.langqi99.aeallpattern.aggregate;

import appeng.api.crafting.IPatternDetails;
import appeng.api.inventories.InternalInventory;
import io.github.langqi99.aeallpattern.registry.ModDataComponents;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;

/**
 * Shared helper for addon pattern providers that build their published list by calling
 * {@code PatternDetailsHelper.decodePattern} once per slot.
 *
 * <p>Such hosts only ever see the single marker the aggregate decoder returns, so an aggregate
 * holding thousands of children looks like a one-recipe pattern to them. Hosts inject this helper
 * right before they publish their list so every child gets added as well.</p>
 *
 * <p>Expansion goes through the scheduled path: a cold aggregate is spread across ticks and the
 * host's refresh runs again once the complete list is ready. Callers that are already running
 * because of that callback must pass {@code null} as the completion callback, otherwise an
 * aggregate whose children are all deselected would reschedule itself forever.</p>
 */
public final class AggregateProviderExpansion {
    private AggregateProviderExpansion() {
    }

    /** Avoid tracking plain addon providers; only aggregate hosts need startup recovery. */
    public static boolean containsAggregate(InternalInventory inventory) {
        if (inventory == null) {
            return false;
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (ModDataComponents.hasAggregatePattern(inventory.getStackInSlot(slot))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Library-specific selection for targeted catalog updates. A newly generated
     * unrelated aggregate must never rebuild already-indexed ME providers.
     */
    public static boolean containsLibrary(InternalInventory inventory, java.util.UUID libraryId) {
        if (inventory == null || libraryId == null) {
            return false;
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && ModDataComponents.hasAggregatePattern(stack)) {
                AggregatePatternRef ref = ModDataComponents.getAggregatePattern(stack);
                if (ref != null && libraryId.equals(ref.libraryId())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks whether a provider should have at least one published aggregate child.
     * Fully disabled catalogs and catalogs whose children are all filtered out by the
     * item's options are legitimately empty and must not trigger auto-repair.
     */
    public static boolean expectsPublishedChildren(InternalInventory inventory, Level level) {
        if (inventory == null || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty() || !ModDataComponents.hasAggregatePattern(stack)) {
                continue;
            }
            var selection = ModDataComponents.getAggregatePatternSelection(stack);
            if (selection != null && selection.isNoneEnabled()) {
                continue;
            }
            var ref = ModDataComponents.getAggregatePattern(stack);
            if (ref != null && AggregatePatternExpander.isPending(serverLevel.getServer(), ref.libraryId())) {
                continue;
            }
            // Use the real expansion filters/options instead of only the saved recipe count.
            // A catalog whose children are all probabilistic, durability-consuming, or filtered
            // by the item's options is legitimately empty and must not trigger auto-repair.
            // A single bad recipe must not make the whole provider look unindexed either.
            try {
                if (ref != null && AggregatePatternExpander.expandFirst(stack, serverLevel) != null) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // Treat an unreadable/expansion-failing catalog as not currently expecting
                // children; the normal provider refresh path still handles real index loss.
            }
        }
        return false;
    }

    /**
     * Publishes every child of every aggregate pattern held in {@code inventory}.
     *
     * @param onCompletion re-runs the host refresh when a cold expansion finishes; {@code null}
     *                     during the re-run itself so the host cannot loop on itself
     * @param sink         receives every expanded child
     * @return {@code true} when the aggregate is still expanding (the list is incomplete)
     */
    public static boolean expandSlots(InternalInventory inventory,
                                      Level level,
                                      Runnable onCompletion,
                                      Consumer<IPatternDetails> sink) {
        if (inventory == null || level == null) {
            return false;
        }
        boolean pending = false;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty() || !ModDataComponents.hasAggregatePattern(stack)) {
                continue;
            }
            List<IPatternDetails> expanded =
                    AggregatePatternExpander.expandScheduled(stack, level, onCompletion);
            if (expanded.isEmpty()) {
                pending = true;
            }
            for (IPatternDetails pattern : expanded) {
                sink.accept(pattern);
            }
        }
        return pending;
    }
}
