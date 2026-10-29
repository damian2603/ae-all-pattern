package io.github.langqi99.aeallpattern.network;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

/** Server tells the client to stop a queued upload and explains why. */
public record AggregateUploadRejectedPayload(UUID uploadId, String reasonKey) {
    public static final int MAX_REASON_KEY_LENGTH = 128;

    public static final FriendlyStreamCodec<AggregateUploadRejectedPayload> STREAM_CODEC =
            FriendlyStreamCodec.of(AggregateUploadRejectedPayload::encode, AggregateUploadRejectedPayload::decode);

    public AggregateUploadRejectedPayload {
        if (uploadId == null || reasonKey == null || reasonKey.isBlank()
                || reasonKey.length() > MAX_REASON_KEY_LENGTH) {
            throw new IllegalArgumentException("invalid aggregate upload rejection");
        }
    }

    private static void encode(FriendlyByteBuf buffer, AggregateUploadRejectedPayload payload) {
        buffer.writeUUID(payload.uploadId());
        buffer.writeUtf(payload.reasonKey(), MAX_REASON_KEY_LENGTH);
    }

    private static AggregateUploadRejectedPayload decode(FriendlyByteBuf buffer) {
        return new AggregateUploadRejectedPayload(buffer.readUUID(), buffer.readUtf(MAX_REASON_KEY_LENGTH));
    }
}
