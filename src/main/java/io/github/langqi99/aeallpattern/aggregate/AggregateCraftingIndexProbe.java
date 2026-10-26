package io.github.langqi99.aeallpattern.aggregate;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IManagedGridNode;
import java.util.List;

/**
 * Very cheap live check of AE2's crafting index, without rebuilding any pattern.
 *
 * <p>An addon can retain a fully expanded local pattern list after AE2 removes
 * its crafting provider (e.g. during a grid topology change). A later explicit
 * /aeallpattern refresh re-indexes it. Instead of periodically calling
 * requestUpdate() for every large aggregate, check a single real child first
 * and repair only when AE2 no longer knows that pattern.</p>
 */
public final class AggregateCraftingIndexProbe {
    private AggregateCraftingIndexProbe() {
    }

    public static boolean isIndexed(IManagedGridNode node, List<IPatternDetails> patterns) {
        if (node == null || !node.isActive() || node.getGrid() == null
                || patterns == null || patterns.isEmpty()) {
            return true;
        }

        IPatternDetails sample = null;
        for (IPatternDetails candidate : patterns) {
            if (candidate == null || candidate instanceof AggregatePatternMarkerDetails) {
                continue;
            }
            var outputs = candidate.getOutputs();
            if (outputs.length > 0 && outputs[0] != null && outputs[0].what() != null) {
                sample = candidate;
                break;
            }
        }
        // A completely deselected aggregate legitimately publishes no recipes.
        if (sample == null) {
            return true;
        }

        return node.getGrid().getCraftingService()
                .getCraftingFor(sample.getPrimaryOutput().what()).contains(sample);
    }
}
