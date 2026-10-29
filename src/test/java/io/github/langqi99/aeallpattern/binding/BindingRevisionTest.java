package io.github.langqi99.aeallpattern.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/** The in-memory revision must change for all mutations that invalidate linker jobs. */
class BindingRevisionTest {
    @Test
    void bindingChangesIncrementRevisionButReadsDoNot() {
        BindingSavedData data = new BindingSavedData();
        GlobalPos anchor = GlobalPos.of(Level.OVERWORLD, new BlockPos(1, 64, 1));
        GlobalPos other = GlobalPos.of(Level.OVERWORLD, new BlockPos(2, 64, 2));
        BindingRecord first = makeRecord(anchor);
        BindingRecord second = makeRecord(other);
        assertEquals(0L, data.mutationRevision());
        data.put(first);
        assertEquals(1L, data.mutationRevision());
        assertEquals(1, data.byAnchor(anchor).size());
        assertEquals(1L, data.mutationRevision());
        data.put(second);
        assertEquals(2L, data.mutationRevision());
        assertTrue(data.remove(first.bindingId()));
        assertEquals(3L, data.mutationRevision());
        assertEquals(0, data.byAnchor(anchor).size());
        assertEquals(1, data.removeByAnchor(other));
        assertEquals(4L, data.mutationRevision());
        assertEquals(0, data.removeByAnchor(other));
        assertEquals(4L, data.mutationRevision());
    }

    private static BindingRecord makeRecord(GlobalPos anchor) {
        return new BindingRecord(BindingRecord.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID(), UUID.randomUUID(), anchor,
                GlobalPos.of(Level.OVERWORLD, new BlockPos(10, 64, 10)),
                Direction.NORTH, "anchor-fingerprint", "target-fingerprint",
                "aeallpattern:test", 1, 0L, 0L);
    }
}
