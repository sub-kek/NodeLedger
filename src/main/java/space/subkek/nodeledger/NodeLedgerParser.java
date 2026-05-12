package space.subkek.nodeledger;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Parser and writer for the NODELEDGER binary file format.
 */
@SuppressWarnings("unchecked")
public class NodeLedgerParser {
    private static final byte[] MAGIC = new byte[]{'N', 'L', 'E', 'D', 'G', 'E', 'R', 0};
    private static final long VERSION = 1L;
    private static final int HEADER_SIZE = 24;
    private static final int ROOT_OFFSET_SIZE = Long.BYTES;
    private static final int OBJECT_HEADER_SIZE = Long.BYTES * 2;
    private static final int COPY_BUFFER_SIZE = 1024 * 1024;
    private static final BigInteger MAX_U64 = BigInteger.ONE.shiftLeft(Long.SIZE).subtract(BigInteger.ONE);

    private final NodeLedgerCodecRegistry registry = new NodeLedgerCodecRegistry();

    /**
     * Creates a parser.
     */
    public NodeLedgerParser() {
        registerBuiltInCodecs();
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long offset) throws IOException, NodeLedgerException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, offset);
            if (read < 0) {
                throw new NodeLedgerException("Unexpected end of NODELEDGER file");
            }
            offset += read;
        }
    }

    private static void writeBytes(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void transferFully(FileChannel source, FileChannel target, long position, long count)
        throws IOException {
        long remaining = count;
        long cursor = position;
        ByteBuffer fallback = ByteBuffer.allocate((int) Math.min(COPY_BUFFER_SIZE, count));
        while (remaining > 0L) {
            long transferred = source.transferTo(cursor, remaining, target);
            if (transferred == 0L) {
                fallback.clear();
                fallback.limit((int) Math.min(fallback.capacity(), remaining));
                int read = source.read(fallback, cursor);
                if (read < 0) {
                    throw new IOException("Unexpected end of source while copying NODELEDGER object");
                }
                fallback.flip();
                writeBytes(target, fallback);
                transferred = read;
            }
            cursor += transferred;
            remaining -= transferred;
        }
    }

    private static void requireRange(long offset, long size, long fileSize, String message) throws NodeLedgerException {
        if (offset < HEADER_SIZE || size < 0 || offset > fileSize || size > fileSize - offset) {
            throw new NodeLedgerException(message);
        }
    }

    private static void requireRemaining(ByteBuffer payload, int bytes, String message) throws NodeLedgerException {
        if (payload.remaining() < bytes) {
            throw new NodeLedgerException(message);
        }
    }

    private static String decodeUtf8(ByteBuffer bytes, String message) throws NodeLedgerException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(bytes)
                .toString();
        } catch (CharacterCodingException exception) {
            throw new NodeLedgerException(message, exception);
        }
    }

    /**
     * Returns the codec registry used by this parser.
     *
     * @return codec registry.
     */
    public NodeLedgerCodecRegistry registry() {
        return registry;
    }

    /**
     * Registers a codec.
     *
     * @param codec codec to register.
     * @param <T>   Java type handled by the codec.
     * @return this parser.
     */
    public <T> NodeLedgerParser register(NodeLedgerCodec<T> codec) {
        registry.register(codec);
        return this;
    }

    /**
     * Registers a reader and writer pair for a Java type.
     *
     * @param javaType Java type.
     * @param typeId   NODELEDGER type id.
     * @param reader   payload reader.
     * @param writer   payload writer.
     * @param <T>      Java type handled by the reader and writer.
     * @return this parser.
     */
    public <T> NodeLedgerParser register(
        Class<T> javaType,
        long typeId,
        NodeLedgerReader<T> reader,
        NodeLedgerWriter<T> writer
    ) {
        registry.register(javaType, typeId, reader, writer);
        return this;
    }

    /**
     * Creates an empty document that uses this parser and its registered codecs.
     *
     * @return empty document.
     */
    public NodeLedgerDocument create() {
        return new NodeLedgerDocument(this, null, new NodeLedgerNode(), 0L, 0L, true);
    }

    /**
     * Reads a document from a file.
     *
     * @param path source file path.
     * @return parsed document.
     * @throws NodeLedgerException when reading fails.
     */
    public NodeLedgerDocument read(Path path) throws NodeLedgerException {
        Objects.requireNonNull(path, "path");
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize < HEADER_SIZE + ROOT_OFFSET_SIZE) {
                throw new NodeLedgerException("NODELEDGER file is too small");
            }

            Header header = readHeader(channel);
            long rootOffset = readLong(channel, fileSize - ROOT_OFFSET_SIZE);
            if (rootOffset < HEADER_SIZE || rootOffset >= fileSize - ROOT_OFFSET_SIZE) {
                throw new NodeLedgerException("Invalid root offset: " + rootOffset);
            }

            NodeLedgerNode root = readNode(channel, path, rootOffset, fileSize);
            return new NodeLedgerDocument(this, path, root, fileSize, header.lastTrimTimestamp(), false);
        } catch (IOException exception) {
            throw new NodeLedgerException("Failed to read NODELEDGER file: " + path, exception);
        }
    }

    /**
     * Writes a document to a file.
     *
     * @param document document to write.
     * @param path     target file path.
     * @throws NodeLedgerException when writing fails.
     */
    public void write(NodeLedgerDocument document, Path path) throws NodeLedgerException {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(path, "path");
        if (!document.isDirty() && document.path().filter(path::equals).isPresent()) {
            return;
        }
        boolean append = document.path().filter(path::equals).isPresent();
        writeDocument(document, path, append, false);
    }

    /**
     * Writes a compacted document to a file.
     *
     * @param document document to write.
     * @param path     target file path.
     * @throws NodeLedgerException when writing fails.
     */
    public void writeCompacted(NodeLedgerDocument document, Path path) throws NodeLedgerException {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(path, "path");
        if (document.path().filter(path::equals).isPresent()) {
            throw new NodeLedgerException("compact target must be different from the source file");
        }
        writeDocument(document, path, false, true);
    }

    /**
     * Compacts a NODELEDGER file into a new file.
     *
     * @param source source file path.
     * @param target target file path.
     * @throws NodeLedgerException when trimming fails.
     */
    public void trim(Path source, Path target) throws NodeLedgerException {
        if (source.equals(target)) {
            throw new NodeLedgerException("trim source and target must be different files");
        }
        NodeLedgerDocument document = read(source);
        writeCompacted(document, target);
    }

    /**
     * Reads a stored or in-memory value as a Java type.
     *
     * @param value    NODELEDGER value.
     * @param javaType expected Java type.
     * @param <T>      Java value type.
     * @return decoded value.
     * @throws NodeLedgerException when decoding fails.
     */
    public <T> T readValue(NodeLedgerValue value, Class<T> javaType) throws NodeLedgerException {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(javaType, "javaType");
        if (value.isNull()) {
            validateStoredNull(value);
            return null;
        }
        if (value.value() != null) {
            return javaType.cast(value.value());
        }

        NodeLedgerCodec<?> codec = registry.findByTypeId(value.typeId());
        if (codec == null) {
            throw new NodeLedgerException("No NODELEDGER codec registered for type id " + value.typeId());
        }
        if (!javaType.isAssignableFrom(codec.javaType())) {
            throw new NodeLedgerException("NODELEDGER type " + value.typeId() + " is registered as "
                + codec.javaType().getName() + ", not " + javaType.getName());
        }
        return javaType.cast(readStoredValue(value, codec));
    }

    private void writeDocument(NodeLedgerDocument document, Path path, boolean append, boolean compact)
        throws NodeLedgerException {
        long headerTrimTimestamp = document.lastTrimTimestamp();
        try (FileChannel target = openTarget(path, append)) {
            if (append) {
                validateHeader(target);
                target.position(target.size());
            } else {
                target.truncate(0L);
                if (compact) {
                    headerTrimTimestamp = System.currentTimeMillis();
                }
                writeHeader(target, headerTrimTimestamp);
            }

            long rootOffset = writeNode(target, path, document.root(), compact);
            writeLong(target, rootOffset);
            target.force(true);
            document.markSaved(path, target.size());
            document.lastTrimTimestamp(headerTrimTimestamp);
        } catch (IOException exception) {
            throw new NodeLedgerException("Failed to write NODELEDGER file: " + path, exception);
        }
    }

    private FileChannel openTarget(Path path, boolean append) throws IOException {
        if (append) {
            FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
            );
            if (channel.size() == 0L) {
                writeHeader(channel, 0L);
            }
            return channel;
        }

        return FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private long writeNode(FileChannel target, Path targetPath, NodeLedgerNode node, boolean compact)
        throws IOException, NodeLedgerException {
        List<NodeItem> items = new ArrayList<>();
        for (Map.Entry<String, NodeLedgerNode.Entry> entry : node.entries().entrySet()) {
            NodeLedgerNode childNode = entry.getValue().node();
            if (childNode != null) {
                items.add(new NodeItem(entry.getKey(), NodeLedgerTypeId.NODE, writeNode(target, targetPath, childNode, compact)));
            } else {
                NodeLedgerValue value = entry.getValue().value();
                long offset = writeValueObject(target, targetPath, value, compact);
                items.add(new NodeItem(entry.getKey(), value.typeId(), offset));
            }
        }

        long nodeOffset = target.position();
        writeLong(target, NodeLedgerTypeId.NODE);
        writeLong(target, items.size());
        for (NodeItem item : items) {
            byte[] key = item.key().getBytes(StandardCharsets.UTF_8);
            writeLong(target, key.length);
            writeBytes(target, ByteBuffer.wrap(key));
            writeLong(target, item.typeId());
            writeLong(target, item.offset());
        }
        return nodeOffset;
    }

    private long writeValueObject(FileChannel target, Path targetPath, NodeLedgerValue value, boolean compact)
        throws IOException, NodeLedgerException {
        boolean storedInTarget = value.isStored() && targetPath.equals(value.sourcePath());
        if (!compact && storedInTarget && !value.isDirty()) {
            return value.objectOffset();
        }
        if (compact && value.isStored() && !value.isDirty()) {
            long offset = copyStoredObject(target, value);
            value.markStored(targetPath, offset, value.payloadSize());
            return offset;
        }
        if (!compact && value.isStored() && !value.isDirty()) {
            long offset = copyStoredObject(target, value);
            value.markStored(targetPath, offset, value.payloadSize());
            return offset;
        }

        ByteBuffer payload = encodePayload(value);
        int payloadSize = payload.remaining();
        long objectOffset = target.position();
        writeLong(target, value.typeId());
        writeLong(target, payloadSize);
        writeBytes(target, payload);
        value.markStored(targetPath, objectOffset, payloadSize);
        return objectOffset;
    }

    private long copyStoredObject(FileChannel target, NodeLedgerValue value) throws IOException, NodeLedgerException {
        Path sourcePath = value.sourcePath();
        if (sourcePath == null) {
            throw new NodeLedgerException("Cannot copy a value that is not stored in a NODELEDGER file");
        }

        try (FileChannel source = FileChannel.open(sourcePath, StandardOpenOption.READ)) {
            ObjectHeader header = readObjectHeader(source, value.objectOffset(), source.size());
            if (header.typeId() != value.typeId()) {
                throw new NodeLedgerException("Object type mismatch at offset " + value.objectOffset());
            }

            long targetOffset = target.position();
            transferFully(source, target, value.objectOffset(), OBJECT_HEADER_SIZE + header.payloadSize());
            value.payloadSize(header.payloadSize());
            return targetOffset;
        }
    }

    private ByteBuffer encodePayload(NodeLedgerValue value) throws NodeLedgerException {
        if (value.isNull()) {
            return ByteBuffer.allocate(0);
        }

        NodeLedgerCodec<Object> codec = (NodeLedgerCodec<Object>) registry.findByJavaType(value.value().getClass());
        if (codec == null) {
            throw new NodeLedgerException("No NODELEDGER codec registered for " + value.value().getClass().getName());
        }
        ByteBuffer payload = codec.writer().write(value.value());
        if (payload == null) {
            throw new NodeLedgerException("NODELEDGER writer returned null payload");
        }
        payload.order(ByteOrder.LITTLE_ENDIAN);
        return payload;
    }

    private Object readStoredValue(NodeLedgerValue value, NodeLedgerCodec<?> codec) throws NodeLedgerException {
        try (FileChannel channel = FileChannel.open(value.sourcePath(), StandardOpenOption.READ)) {
            ObjectHeader header = readObjectHeader(channel, value.objectOffset(), channel.size());
            if (header.typeId() != value.typeId()) {
                throw new NodeLedgerException("Object type mismatch at offset " + value.objectOffset());
            }
            if (header.payloadSize() > Integer.MAX_VALUE) {
                throw new NodeLedgerException("Payload is too large for ByteBuffer decoding: " + header.payloadSize());
            }

            ByteBuffer payload = ByteBuffer.allocate((int) header.payloadSize()).order(ByteOrder.LITTLE_ENDIAN);
            readFully(channel, payload, value.objectOffset() + OBJECT_HEADER_SIZE);
            payload.flip();
            value.payloadSize(header.payloadSize());
            try {
                Object decoded = ((NodeLedgerCodec<Object>) codec).reader().read(payload);
                if (payload.hasRemaining()) {
                    throw new NodeLedgerException("NODELEDGER payload has trailing bytes for type " + value.typeId());
                }
                return decoded;
            } catch (RuntimeException exception) {
                throw new NodeLedgerException("Invalid NODELEDGER payload for type " + value.typeId(), exception);
            }
        } catch (IOException exception) {
            throw new NodeLedgerException("Failed to read stored NODELEDGER value", exception);
        }
    }

    private NodeLedgerNode readNode(FileChannel channel, Path path, long offset, long fileSize)
        throws IOException, NodeLedgerException {
        return readNode(channel, path, offset, fileSize, fileSize - ROOT_OFFSET_SIZE, new HashSet<>());
    }

    private NodeLedgerNode readNode(
        FileChannel channel,
        Path path,
        long offset,
        long fileSize,
        long dataEnd,
        Set<Long> nodeStack
    ) throws IOException, NodeLedgerException {
        requireRange(offset, Long.BYTES * 2L, dataEnd, "Invalid node offset: " + offset);
        if (!nodeStack.add(offset)) {
            throw new NodeLedgerException("NODELEDGER node cycle at offset " + offset);
        }

        long type = readLong(channel, offset);
        if (type != NodeLedgerTypeId.NODE) {
            throw new NodeLedgerException("Expected node at offset " + offset);
        }

        long length = readLong(channel, offset + Long.BYTES);
        long cursor = offset + Long.BYTES * 2L;
        if (length < 0 || length > (dataEnd - cursor) / (Long.BYTES * 3L + 1L)) {
            throw new NodeLedgerException("Invalid node length at offset " + offset);
        }
        NodeLedgerNode node = new NodeLedgerNode();
        for (long i = 0; i < length; i++) {
            requireRange(cursor, Long.BYTES, dataEnd, "Truncated NODELEDGER node at offset " + offset);
            long keyLength = readLong(channel, cursor);
            cursor += Long.BYTES;
            if (keyLength <= 0 || keyLength > Integer.MAX_VALUE) {
                throw new NodeLedgerException("Invalid key length in node at offset " + offset);
            }
            requireRange(cursor, keyLength, dataEnd, "Invalid key length in node at offset " + offset);

            ByteBuffer keyBuffer = ByteBuffer.allocate((int) keyLength);
            readFully(channel, keyBuffer, cursor);
            keyBuffer.flip();
            String key = decodeUtf8(keyBuffer, "Invalid UTF-8 node key at offset " + offset);
            cursor += keyLength;
            if (node.containsKey(key)) {
                throw new NodeLedgerException("Duplicate NODELEDGER key in node at offset " + offset + ": " + key);
            }

            requireRange(cursor, Long.BYTES * 2L, dataEnd, "Truncated NODELEDGER node entry at offset " + offset);
            long entryType = readLong(channel, cursor);
            cursor += Long.BYTES;
            long entryOffset = readLong(channel, cursor);
            cursor += Long.BYTES;
            if (entryOffset < HEADER_SIZE || entryOffset >= dataEnd) {
                throw new NodeLedgerException("Invalid entry offset: " + entryOffset);
            }

            if (entryType == NodeLedgerTypeId.NODE) {
                node.putNode(key, readNode(channel, path, entryOffset, fileSize, dataEnd, nodeStack));
            } else {
                ObjectHeader objectHeader = readObjectHeader(channel, entryOffset, dataEnd);
                if (objectHeader.typeId() != entryType) {
                    throw new NodeLedgerException("Object type mismatch at offset " + entryOffset);
                }
                node.put(key, new NodeLedgerValue(entryType, path, entryOffset));
            }
        }
        nodeStack.remove(offset);
        return node;
    }

    private Header readHeader(FileChannel channel) throws IOException, NodeLedgerException {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, buffer, 0L);
        buffer.flip();
        for (byte magicByte : MAGIC) {
            if (buffer.get() != magicByte) {
                throw new NodeLedgerException("Invalid NODELEDGER magic");
            }
        }
        long version = buffer.getLong();
        if (version != VERSION) {
            throw new NodeLedgerException("Unsupported NODELEDGER version: " + version);
        }
        return new Header(buffer.getLong());
    }

    private void validateHeader(FileChannel channel) throws IOException, NodeLedgerException {
        if (channel.size() == 0L) {
            writeHeader(channel, 0L);
            return;
        }
        if (channel.size() < HEADER_SIZE + ROOT_OFFSET_SIZE) {
            throw new NodeLedgerException("Cannot append to truncated NODELEDGER file");
        }
        readHeader(channel);
    }

    private void writeHeader(FileChannel channel, long lastTrimTimestamp) throws IOException {
        writeBytes(channel, ByteBuffer.wrap(MAGIC));
        writeLong(channel, VERSION);
        writeLong(channel, lastTrimTimestamp);
    }

    private ObjectHeader readObjectHeader(FileChannel channel, long offset, long fileSize)
        throws IOException, NodeLedgerException {
        requireRange(offset, OBJECT_HEADER_SIZE, fileSize, "Invalid object offset: " + offset);
        long typeId = readLong(channel, offset);
        long payloadSize = readLong(channel, offset + Long.BYTES);
        if (typeId == NodeLedgerTypeId.NODE) {
            throw new NodeLedgerException("Object type must not be NODE at offset " + offset);
        }
        if (payloadSize < 0 || payloadSize > fileSize - offset - OBJECT_HEADER_SIZE) {
            throw new NodeLedgerException("Invalid object payload size at offset " + offset);
        }
        return new ObjectHeader(typeId, payloadSize);
    }

    private void validateStoredNull(NodeLedgerValue value) throws NodeLedgerException {
        if (!value.isStored()) {
            return;
        }
        try (FileChannel channel = FileChannel.open(value.sourcePath(), StandardOpenOption.READ)) {
            ObjectHeader header = readObjectHeader(channel, value.objectOffset(), channel.size());
            if (header.typeId() != NodeLedgerTypeId.NULL) {
                throw new NodeLedgerException("Object type mismatch at offset " + value.objectOffset());
            }
            if (header.payloadSize() != 0L) {
                throw new NodeLedgerException("NULL payload must be empty at offset " + value.objectOffset());
            }
        } catch (IOException exception) {
            throw new NodeLedgerException("Failed to read stored NODELEDGER null value", exception);
        }
    }

    private long readLong(FileChannel channel, long offset) throws IOException, NodeLedgerException {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, buffer, offset);
        buffer.flip();
        return buffer.getLong();
    }

    private void writeLong(FileChannel channel, long value) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(value);
        buffer.flip();
        writeBytes(channel, buffer);
    }

    private void registerBuiltInCodecs() {
        registry.register(Integer.class, NodeLedgerTypeId.I32, payload -> {
            requireRemaining(payload, Integer.BYTES, "I32 payload is truncated");
            return payload.getInt();
        }, value -> {
            ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(value);
            buffer.flip();
            return buffer;
        });
        registry.register(Long.class, NodeLedgerTypeId.I64, payload -> {
            requireRemaining(payload, Long.BYTES, "I64 payload is truncated");
            return payload.getLong();
        }, value -> {
            ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putLong(value);
            buffer.flip();
            return buffer;
        });
        registry.register(BigInteger.class, NodeLedgerTypeId.U64, payload -> {
            requireRemaining(payload, Long.BYTES, "U64 payload is truncated");
            long raw = payload.getLong();
            return new BigInteger(Long.toUnsignedString(raw));
        }, value -> {
            if (value.signum() < 0 || value.compareTo(MAX_U64) > 0) {
                throw new NodeLedgerException("U64 value is out of range: " + value);
            }
            ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putLong(value.longValue());
            buffer.flip();
            return buffer;
        });
        registry.register(Boolean.class, NodeLedgerTypeId.BOOL, payload -> {
            requireRemaining(payload, 1, "BOOL payload is truncated");
            byte raw = payload.get();
            if (raw != 0 && raw != 1) {
                throw new NodeLedgerException("BOOL payload must be 0 or 1");
            }
            return raw == 1;
        }, value -> {
            ByteBuffer buffer = ByteBuffer.allocate(1);
            buffer.put((byte) (value ? 1 : 0));
            buffer.flip();
            return buffer;
        });
        registry.register(Double.class, NodeLedgerTypeId.F64, payload -> {
            requireRemaining(payload, Double.BYTES, "F64 payload is truncated");
            return payload.getDouble();
        }, value -> {
            ByteBuffer buffer = ByteBuffer.allocate(Double.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putDouble(value);
            buffer.flip();
            return buffer;
        });
        registry.register(String.class, NodeLedgerTypeId.STR, payload -> {
            requireRemaining(payload, Long.BYTES, "String payload is truncated");
            long length = payload.getLong();
            if (length < 0 || length > payload.remaining()) {
                throw new NodeLedgerException("String payload length exceeds enclosing payload");
            }
            byte[] bytes = new byte[(int) length];
            payload.get(bytes);
            return decodeUtf8(ByteBuffer.wrap(bytes), "Invalid UTF-8 string payload");
        }, value -> {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES + bytes.length).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putLong(bytes.length);
            buffer.put(bytes);
            buffer.flip();
            return buffer;
        });
        registry.register(byte[].class, NodeLedgerTypeId.BYTES, payload -> {
            requireRemaining(payload, Long.BYTES, "Bytes payload is truncated");
            long length = payload.getLong();
            if (length < 0 || length > payload.remaining()) {
                throw new NodeLedgerException("Bytes payload length exceeds enclosing payload");
            }
            byte[] bytes = new byte[(int) length];
            payload.get(bytes);
            return bytes;
        }, value -> {
            ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES + value.length).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putLong(value.length);
            buffer.put(value);
            buffer.flip();
            return buffer;
        });
        registry.register(List.class, NodeLedgerTypeId.LIST, this::readListPayload, this::writeListPayload);
    }

    private List<?> readListPayload(ByteBuffer payload) throws NodeLedgerException {
        requireRemaining(payload, Long.BYTES, "List payload is truncated");
        long length = payload.getLong();
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new NodeLedgerException("List is too large for in-memory decoding: " + length);
        }

        List<Object> values = new ArrayList<>((int) length);
        for (long i = 0; i < length; i++) {
            requireRemaining(payload, Long.BYTES * 2, "List item header is truncated");
            long typeId = payload.getLong();
            long payloadSize = payload.getLong();
            if (payloadSize < 0 || payloadSize > payload.remaining()) {
                throw new NodeLedgerException("List item payload exceeds enclosing payload");
            }
            ByteBuffer itemPayload = payload.slice().order(ByteOrder.LITTLE_ENDIAN);
            itemPayload.limit((int) payloadSize);
            payload.position(payload.position() + (int) payloadSize);
            if (typeId == NodeLedgerTypeId.NULL) {
                if (payloadSize != 0L) {
                    throw new NodeLedgerException("NULL list item payload must be empty");
                }
                values.add(null);
                continue;
            }

            NodeLedgerCodec<?> codec = registry.findByTypeId(typeId);
            if (codec == null) {
                throw new NodeLedgerException("No codec registered for list item type " + typeId);
            }
            try {
                Object value = ((NodeLedgerCodec<Object>) codec).reader().read(itemPayload);
                if (itemPayload.hasRemaining()) {
                    throw new NodeLedgerException("List item payload has trailing bytes for type " + typeId);
                }
                values.add(value);
            } catch (RuntimeException exception) {
                throw new NodeLedgerException("Invalid NODELEDGER list item payload for type " + typeId, exception);
            }
        }
        return values;
    }

    private ByteBuffer writeListPayload(List<?> values) throws NodeLedgerException {
        List<EncodedListItem> encoded = new ArrayList<>(values.size());
        long totalSize = Long.BYTES;
        for (Object value : values) {
            if (value == null) {
                encoded.add(new EncodedListItem(NodeLedgerTypeId.NULL, ByteBuffer.allocate(0)));
                totalSize += Long.BYTES * 2L;
                continue;
            }

            NodeLedgerCodec<Object> codec = (NodeLedgerCodec<Object>) registry.findByJavaType(value.getClass());
            if (codec == null) {
                throw new NodeLedgerException("No NODELEDGER codec registered for list item " + value.getClass().getName());
            }
            ByteBuffer payload = codec.writer().write(value);
            if (payload == null) {
                throw new NodeLedgerException("NODELEDGER writer returned null list item payload");
            }
            encoded.add(new EncodedListItem(codec.typeId(), payload));
            totalSize += Long.BYTES * 2L + payload.remaining();
        }
        if (totalSize > Integer.MAX_VALUE) {
            throw new NodeLedgerException("List payload is too large for in-memory encoding: " + totalSize);
        }

        ByteBuffer buffer = ByteBuffer.allocate((int) totalSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(values.size());
        for (EncodedListItem item : encoded) {
            buffer.putLong(item.typeId());
            buffer.putLong(item.payload().remaining());
            buffer.put(item.payload());
        }
        buffer.flip();
        return buffer;
    }

    private record Header(long lastTrimTimestamp) {
    }

    private record ObjectHeader(long typeId, long payloadSize) {
    }

    private record NodeItem(String key, long typeId, long offset) {
    }

    private record EncodedListItem(long typeId, ByteBuffer payload) {
    }
}
