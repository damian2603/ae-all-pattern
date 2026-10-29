package io.github.langqi99.aeallpattern.aggregate;

import appeng.api.stacks.GenericStack;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.langqi99.aeallpattern.recipe.RecipeSnapshot;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import io.netty.buffer.Unpooled;

import net.minecraft.network.FriendlyByteBuf;
import io.github.langqi99.aeallpattern.network.FriendlyStreamCodec;
import net.minecraft.resources.ResourceLocation;

/** One concrete native AE2 recipe stored inside an aggregate pattern. */
public record AggregateRecipe(
        String patternId,
        ResourceLocation recipeId,
        AggregatePatternKind kind,
        List<GenericStack> inputs,
        List<AggregateInputSlot> inputSlots,
        List<GenericStack> outputs,
        int probabilisticOutputMask,
        int processingTicks) {
    public static final int MAX_INPUTS = 81;
    public static final int MAX_OUTPUTS = 27;
    public static final int MAX_TOTAL_INPUT_ALTERNATIVES = Integer.MAX_VALUE;

    // This is a transient optimization, not a second copy of every catalog in memory.
    private static final long MAX_CACHED_WIRE_BYTES = 32L * 1024 * 1024;
    private static final int MAX_CACHED_WIRE_RECIPES = 32_768;
    private static final ReferenceQueue<AggregateRecipe> ENCODED_BYTES_QUEUE = new ReferenceQueue<>();
    private static final Map<IdentityWeakReference, CachedWireBytes> ENCODED_BYTES =
            new LinkedHashMap<>(128, 0.75F, true);
    private static long cachedWireBytes;
    private static final Codec<List<GenericStack>> INPUTS_CODEC = GenericStackCodec.CODEC.listOf()
            .flatXmap(inputs -> validateStacks(inputs, MAX_INPUTS, "inputs"), DataResult::success);
    private static final Codec<List<GenericStack>> OUTPUTS_CODEC = GenericStackCodec.CODEC.listOf()
            .flatXmap(outputs -> validateStacks(outputs, MAX_OUTPUTS, "outputs"), DataResult::success);
    private static final Codec<List<AggregateInputSlot>> INPUT_SLOTS_CODEC = AggregateInputSlot.CODEC.listOf()
            .flatXmap(AggregateRecipe::validateInputSlots, DataResult::success);

    public static final Codec<AggregateRecipe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("pattern_id").forGetter(AggregateRecipe::patternId),
            ResourceLocation.CODEC.fieldOf("recipe_id").forGetter(AggregateRecipe::recipeId),
            AggregatePatternKind.CODEC.optionalFieldOf("kind", AggregatePatternKind.PROCESSING)
                    .forGetter(AggregateRecipe::kind),
            INPUTS_CODEC.fieldOf("inputs").forGetter(AggregateRecipe::inputs),
            INPUT_SLOTS_CODEC.optionalFieldOf("input_slots", List.of()).forGetter(AggregateRecipe::inputSlots),
            OUTPUTS_CODEC.fieldOf("outputs").forGetter(AggregateRecipe::outputs),
            Codec.INT.optionalFieldOf("probabilistic_output_mask", 0)
                    .forGetter(AggregateRecipe::probabilisticOutputMask),
            Codec.INT.optionalFieldOf("processing_ticks", 1).forGetter(AggregateRecipe::processingTicks)
    ).apply(instance, AggregateRecipe::new));
    public static final FriendlyStreamCodec<AggregateRecipe> STREAM_CODEC = FriendlyStreamCodec.of(
            AggregateRecipe::encode, AggregateRecipe::decode);

    public AggregateRecipe {
        if (patternId == null || patternId.isBlank() || patternId.length() > 160) {
            throw new IllegalArgumentException("invalid aggregate pattern id");
        }
        inputs = copyAndValidate(inputs, MAX_INPUTS, "inputs");
        inputSlots = inputSlots == null || inputSlots.isEmpty()
                ? inputs.stream().map(AggregateInputSlot::exact).toList()
                : copyAndValidateSlots(inputSlots);
        inputs = inputSlots.stream().map(AggregateInputSlot::primary).toList();
        outputs = copyAndValidate(outputs, MAX_OUTPUTS, "outputs");
        probabilisticOutputMask &= (1 << outputs.size()) - 1;
        processingTicks = Math.max(1, processingTicks);
    }

    /** Compatibility constructor for callers that do not provide probability metadata. */
    public AggregateRecipe(
            String patternId,
            ResourceLocation recipeId,
            AggregatePatternKind kind,
            List<GenericStack> inputs,
            List<AggregateInputSlot> inputSlots,
            List<GenericStack> outputs,
            int processingTicks) {
        this(patternId, recipeId, kind, inputs, inputSlots, outputs, 0, processingTicks);
    }

    /** Compatibility constructor for existing processing-only callers and saved data. */
    public AggregateRecipe(
            String patternId,
            ResourceLocation recipeId,
            AggregatePatternKind kind,
            List<GenericStack> inputs,
            List<GenericStack> outputs,
            int processingTicks) {
        this(patternId, recipeId, kind, inputs, List.of(), outputs, 0, processingTicks);
    }

    /** Compatibility constructor for existing processing-only callers and saved data. */
    public AggregateRecipe(
            String patternId,
            ResourceLocation recipeId,
            List<GenericStack> inputs,
            List<GenericStack> outputs,
            int processingTicks) {
        this(patternId, recipeId, AggregatePatternKind.PROCESSING, inputs, List.of(), outputs, 0, processingTicks);
    }

    public static AggregateRecipe from(RecipeSnapshot snapshot) {
        List<AggregateInputSlot> slots = snapshot.inputAlternatives().stream()
                .map(alternatives -> new AggregateInputSlot(
                        alternatives.stream().map(GenericStack::fromItemStack).toList(),
                        java.util.Optional.empty()))
                .toList();
        return new AggregateRecipe(
                snapshot.fingerprint().stableKey(),
                snapshot.recipeId(),
                AggregatePatternKind.PROCESSING,
                slots.stream().map(AggregateInputSlot::primary).toList(),
                slots,
                List.of(Objects.requireNonNull(GenericStack.fromItemStack(snapshot.output()))),
                0,
                snapshot.processingTicks());
    }

    public boolean isProbabilisticOutput(int index) {
        return index >= 0 && index < outputs.size() && (probabilisticOutputMask & (1 << index)) != 0;
    }

    private static DataResult<List<GenericStack>> validateStacks(
            List<GenericStack> stacks, int maximum, String name) {
        if (stacks.isEmpty() || stacks.size() > maximum) {
            return DataResult.error(() -> "aggregate recipe must have 1-" + maximum + " " + name);
        }
        if (stacks.stream().anyMatch(stack -> stack == null || stack.what() == null || stack.amount() <= 0)) {
            return DataResult.error(() -> "aggregate recipe " + name + " must be non-empty");
        }
        return DataResult.success(stacks);
    }

    private static DataResult<List<AggregateInputSlot>> validateInputSlots(List<AggregateInputSlot> slots) {
        try {
            return DataResult.success(copyAndValidateSlots(slots));
        } catch (IllegalArgumentException error) {
            return DataResult.error(error::getMessage);
        }
    }

    private static List<AggregateInputSlot> copyAndValidateSlots(List<AggregateInputSlot> slots) {
        if (slots == null || slots.isEmpty() || slots.size() > MAX_INPUTS) {
            throw new IllegalArgumentException("aggregate recipe must have 1-" + MAX_INPUTS + " input slots");
        }
        List<AggregateInputSlot> result = List.copyOf(slots);
        if (result.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("aggregate recipe input slots must be non-empty");
        }
        long totalAlternatives = result.stream().mapToLong(slot -> slot.alternatives().size()).sum();
        if (totalAlternatives > MAX_TOTAL_INPUT_ALTERNATIVES) {
            throw new IllegalArgumentException("aggregate recipe has too many explicit input alternatives");
        }
        return result;
    }

    private static List<GenericStack> copyAndValidate(
            List<GenericStack> stacks, int maximum, String name) {
        if (stacks == null || stacks.isEmpty() || stacks.size() > maximum) {
            throw new IllegalArgumentException("aggregate recipe must have 1-" + maximum + " " + name);
        }
        List<GenericStack> result = List.copyOf(stacks);
        if (result.stream().anyMatch(stack -> stack == null || stack.what() == null || stack.amount() <= 0)) {
            throw new IllegalArgumentException("aggregate recipe " + name + " must be non-empty");
        }
        return result;
    }

    private static void encode(FriendlyByteBuf buffer, AggregateRecipe recipe) {
        CachedWireBytes cached = takeEncodedBytes(recipe);
        if (cached != null && cached.reusableForEncoding()) {
            // These bytes were produced by this codec and include all component/NBT data.
            buffer.writeBytes(cached.bytes());
            return;
        }
        encodeFields(buffer, recipe);
    }

    private static void encodeFields(FriendlyByteBuf buffer, AggregateRecipe recipe) {
        buffer.writeUtf(recipe.patternId(), 160);
        buffer.writeResourceLocation(recipe.recipeId());
        buffer.writeEnum(recipe.kind());
        buffer.writeVarInt(recipe.inputSlots.size());
        recipe.inputSlots.forEach(slot -> AggregateInputSlot.STREAM_CODEC.encode(buffer, slot));
        buffer.writeVarInt(recipe.outputs.size());
        recipe.outputs.forEach(stack -> GenericStack.writeBuffer(stack, buffer));
        buffer.writeVarInt(recipe.probabilisticOutputMask);
        buffer.writeVarInt(recipe.processingTicks());
    }

    private static AggregateRecipe decode(FriendlyByteBuf buffer) {
        int wireStart = buffer.readerIndex();
        String patternId = buffer.readUtf(160);
        ResourceLocation recipeId = buffer.readResourceLocation();
        AggregatePatternKind kind = buffer.readEnum(AggregatePatternKind.class);
        int inputCount = checkedCount(buffer.readVarInt(), MAX_INPUTS, "input");
        List<AggregateInputSlot> inputSlots = java.util.stream.IntStream.range(0, inputCount)
                .mapToObj(index -> AggregateInputSlot.STREAM_CODEC.decode(buffer)).toList();
        int outputCount = checkedCount(buffer.readVarInt(), MAX_OUTPUTS, "output");
        List<GenericStack> outputs = java.util.stream.IntStream.range(0, outputCount)
                .mapToObj(index -> java.util.Objects.requireNonNull(GenericStack.readBuffer(buffer))).toList();
        int probabilisticOutputMask = buffer.readVarInt();
        AggregateRecipe recipe = new AggregateRecipe(
                patternId, recipeId, kind,
                inputSlots.stream().map(AggregateInputSlot::primary).toList(),
                inputSlots, outputs, probabilisticOutputMask, buffer.readVarInt());
        int wireLength = buffer.readerIndex() - wireStart;
        byte[] wireBytes = new byte[wireLength];
        buffer.getBytes(wireStart, wireBytes);
        rememberEncodedBytes(recipe, wireBytes, false);
        return recipe;
    }

    private static int checkedCount(int count, int maximum, String name) {
        if (count < 1 || count > maximum) {
            throw new IllegalArgumentException("invalid aggregate " + name + " count: " + count);
        }
        return count;
    }

    /**
     * Measure the actual wire format, including AE item components and arbitrarily
     * sized NBT. A fixed metadata allowance can underestimate modded ItemStacks,
     * causing upload pages to exceed Forge's packet limit.
     */
    public int encodedSizeEstimate() {
        CachedWireBytes cached = peekEncodedBytes(this);
        if (cached != null) {
            return cached.bytes().length;
        }
        byte[] encoded = encodeToBytes(this);
        rememberEncodedBytes(this, encoded, true);
        return encoded.length;
    }

    /**
     * Returns the exact wire size and releases any cached bytes. Network decoders cache the
     * original bytes, so server upload validation does not serialize decoded NBT a second time.
     */
    public int consumeEncodedSize() {
        CachedWireBytes cached = takeEncodedBytes(this);
        return cached != null ? cached.bytes().length : encodeToBytes(this).length;
    }

    private static byte[] encodeToBytes(AggregateRecipe recipe) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(256));
        try {
            encodeFields(buffer, recipe);
            byte[] encoded = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), encoded);
            return encoded;
        } finally {
            buffer.release();
        }
    }

    private static synchronized CachedWireBytes peekEncodedBytes(AggregateRecipe recipe) {
        expungeStaleEncodedBytes();
        return ENCODED_BYTES.get(new IdentityWeakReference(recipe));
    }

    private static synchronized CachedWireBytes takeEncodedBytes(AggregateRecipe recipe) {
        expungeStaleEncodedBytes();
        CachedWireBytes removed = ENCODED_BYTES.remove(new IdentityWeakReference(recipe));
        if (removed != null) {
            cachedWireBytes -= removed.bytes().length;
        }
        return removed;
    }

    private static synchronized void rememberEncodedBytes(
            AggregateRecipe recipe, byte[] encoded, boolean reusableForEncoding) {
        expungeStaleEncodedBytes();
        CachedWireBytes previous = ENCODED_BYTES.remove(new IdentityWeakReference(recipe));
        if (previous != null) {
            cachedWireBytes -= previous.bytes().length;
        }
        if (encoded.length > MAX_CACHED_WIRE_BYTES) {
            return;
        }
        ENCODED_BYTES.put(
                new IdentityWeakReference(recipe, ENCODED_BYTES_QUEUE),
                new CachedWireBytes(encoded, reusableForEncoding));
        cachedWireBytes += encoded.length;
        Iterator<Map.Entry<IdentityWeakReference, CachedWireBytes>> iterator =
                ENCODED_BYTES.entrySet().iterator();
        while (cachedWireBytes > MAX_CACHED_WIRE_BYTES || ENCODED_BYTES.size() > MAX_CACHED_WIRE_RECIPES) {
            Map.Entry<IdentityWeakReference, CachedWireBytes> eldest = iterator.next();
            cachedWireBytes -= eldest.getValue().bytes().length;
            iterator.remove();
        }
    }

    private static synchronized void expungeStaleEncodedBytes() {
        IdentityWeakReference reference;
        while ((reference = (IdentityWeakReference) ENCODED_BYTES_QUEUE.poll()) != null) {
            CachedWireBytes removed = ENCODED_BYTES.remove(reference);
            if (removed != null) {
                cachedWireBytes -= removed.bytes().length;
            }
        }
    }

    private record CachedWireBytes(byte[] bytes, boolean reusableForEncoding) {
    }

    private static final class IdentityWeakReference extends WeakReference<AggregateRecipe> {
        private final int identityHash;

        private IdentityWeakReference(AggregateRecipe recipe) {
            super(recipe);
            identityHash = System.identityHashCode(recipe);
        }

        private IdentityWeakReference(AggregateRecipe recipe, ReferenceQueue<AggregateRecipe> queue) {
            super(recipe, queue);
            identityHash = System.identityHashCode(recipe);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof IdentityWeakReference reference)) {
                return false;
            }
            AggregateRecipe recipe = get();
            return recipe != null && recipe == reference.get();
        }
    }
}
