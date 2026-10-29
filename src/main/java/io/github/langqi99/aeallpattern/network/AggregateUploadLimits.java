package io.github.langqi99.aeallpattern.network;

/**
 * Shared byte budgets for client-generated aggregate catalogs.
 *
 * <p>The recipe-count limit is configurable, but the network/upload path also has a hard
 * memory budget. Keeping the constants in one place lets the client fail fast with a clear
 * message instead of uploading pages that the server will silently discard.</p>
 */
public final class AggregateUploadLimits {
    /** Maximum serialized recipe bytes buffered by the server for one upload session. */
    public static final long MAX_BUFFERED_UPLOAD_BYTES = 32L * 1024 * 1024;
    /** Maximum serialized recipe bytes in one page, leaving room for packet headers. */
    public static final int MAX_PAGE_RECIPE_BYTES = 20_000;

    private AggregateUploadLimits() {
    }
}
