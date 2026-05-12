package space.subkek.nodeledger;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Registry for built-in and custom NODELEDGER value codecs.
 */
public class NodeLedgerCodecRegistry {
    private final Map<Class<?>, NodeLedgerCodec<?>> codecsByJavaType = new HashMap<>();
    private final Map<Long, NodeLedgerCodec<?>> codecsByTypeId = new HashMap<>();

    /**
     * Creates an empty codec registry.
     */
    NodeLedgerCodecRegistry() {
    }

    @SuppressWarnings("unchecked")
    private static <T> NodeLedgerCodec<T> castCodec(NodeLedgerCodec<?> codec) {
        return (NodeLedgerCodec<T>) codec;
    }

    /**
     * Registers a codec.
     *
     * @param codec codec to register.
     * @param <T>   Java type handled by the codec.
     */
    public <T> void register(NodeLedgerCodec<T> codec) {
        Objects.requireNonNull(codec, "codec");
        if (codec.typeId() == NodeLedgerTypeId.NODE) {
            throw new IllegalArgumentException("type id 0 is reserved for nodes");
        }
        NodeLedgerCodec<?> existingJavaType = codecsByJavaType.get(codec.javaType());
        if (existingJavaType != null && existingJavaType.typeId() != codec.typeId()) {
            throw new IllegalArgumentException("codec already registered for " + codec.javaType().getName());
        }
        NodeLedgerCodec<?> existingTypeId = codecsByTypeId.get(codec.typeId());
        if (existingTypeId != null && !existingTypeId.javaType().equals(codec.javaType())) {
            throw new IllegalArgumentException("codec type id already registered: " + codec.typeId());
        }
        codecsByJavaType.put(codec.javaType(), codec);
        codecsByTypeId.put(codec.typeId(), codec);
    }

    /**
     * Registers a reader and writer pair for a Java type.
     *
     * @param javaType Java type.
     * @param typeId   NODELEDGER type id.
     * @param reader   payload reader.
     * @param writer   payload writer.
     * @param <T>      Java type handled by the reader and writer.
     */
    public <T> void register(Class<T> javaType, long typeId, NodeLedgerReader<T> reader, NodeLedgerWriter<T> writer) {
        register(new RegisteredCodec<>(javaType, typeId, reader, writer));
    }

    /**
     * Finds a codec by Java type.
     *
     * @param javaType Java type.
     * @param <T>      Java type handled by the codec.
     * @return codec, or {@code null} when no codec is registered.
     */
    public <T> NodeLedgerCodec<T> findByJavaType(Class<T> javaType) {
        Objects.requireNonNull(javaType, "javaType");
        NodeLedgerCodec<?> exact = codecsByJavaType.get(javaType);
        if (exact != null) {
            return castCodec(exact);
        }

        for (Map.Entry<Class<?>, NodeLedgerCodec<?>> entry : codecsByJavaType.entrySet()) {
            if (entry.getKey().isAssignableFrom(javaType)) {
                return castCodec(entry.getValue());
            }
        }
        return null;
    }

    /**
     * Finds a codec by NODELEDGER type id.
     *
     * @param typeId NODELEDGER type id.
     * @return codec, or {@code null} when no codec is registered.
     */
    public NodeLedgerCodec<?> findByTypeId(long typeId) {
        return codecsByTypeId.get(typeId);
    }

    private record RegisteredCodec<T>(
        Class<T> javaType,
        long typeId,
        NodeLedgerReader<T> reader,
        NodeLedgerWriter<T> writer
    ) implements NodeLedgerCodec<T> {
        private RegisteredCodec {
            Objects.requireNonNull(javaType, "javaType");
            Objects.requireNonNull(reader, "reader");
            Objects.requireNonNull(writer, "writer");
        }
    }
}
