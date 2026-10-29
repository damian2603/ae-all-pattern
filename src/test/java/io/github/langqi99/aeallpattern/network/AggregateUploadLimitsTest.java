package io.github.langqi99.aeallpattern.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class AggregateUploadLimitsTest {
    @Test
    void clientAndServerShareTheSameByteBudgets() {
        assertEquals(32L * 1024 * 1024, AggregateUploadLimits.MAX_BUFFERED_UPLOAD_BYTES);
        assertEquals(20_000, AggregateUploadLimits.MAX_PAGE_RECIPE_BYTES);
        assertTrue(AggregateUploadLimits.MAX_PAGE_RECIPE_BYTES < 32 * 1024);
    }

    @Test
    void rejectionPayloadRoundTripsAndRejectsInvalidKeys() {
        UUID id = UUID.randomUUID();
        var payload = new AggregateUploadRejectedPayload(id, "message.aeallpattern.generator.upload_too_large");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            AggregateUploadRejectedPayload.STREAM_CODEC.encode(buffer, payload);
            assertEquals(payload, AggregateUploadRejectedPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
        assertThrows(IllegalArgumentException.class,
                () -> new AggregateUploadRejectedPayload(id, " "));
        assertThrows(IllegalArgumentException.class,
                () -> new AggregateUploadRejectedPayload(id, "x".repeat(129)));
    }
}
