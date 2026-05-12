package space.subkek.nodeledger;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NodeLedgerParserTest {
    private static final Path TEST_DIR = Path.of(".test");

    private static Path testFile(String fileName) throws Exception {
        Files.createDirectories(TEST_DIR);
        Path path = TEST_DIR.resolve(fileName);
        Files.deleteIfExists(path);
        return path;
    }

    private static void writeSingleValueFile(Path path, long typeId, byte[] payload) throws Exception {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        writeHeader(file);
        long objectOffset = file.size();
        writeLong(file, typeId);
        writeLong(file, payload.length);
        file.writeBytes(payload);
        long rootOffset = file.size();
        writeLong(file, NodeLedgerTypeId.NODE);
        writeLong(file, 1L);
        writeNodeEntry(file, "value", typeId, objectOffset);
        writeLong(file, rootOffset);
        Files.write(path, file.toByteArray());
    }

    private static void writeHeader(ByteArrayOutputStream file) {
        file.writeBytes(new byte[]{'N', 'L', 'E', 'D', 'G', 'E', 'R', 0});
        writeLong(file, 1L);
        writeLong(file, 0L);
    }

    private static void writeNodeEntry(ByteArrayOutputStream file, String key, long typeId, long offset) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        writeLong(file, keyBytes.length);
        file.writeBytes(keyBytes);
        writeLong(file, typeId);
        writeLong(file, offset);
    }

    private static void writeLong(ByteArrayOutputStream file, long value) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(value);
        file.writeBytes(buffer.array());
    }

    @Test
    void builtInValuesRoundTripThroughFile() throws Exception {
        Path path = testFile("values.nl");
        NodeLedgerDocument document = NodeLedger.create();

        document.set("user.name", "alice");
        document.set("user.age", 30);
        document.set("user.active", true);
        document.set("blob", new byte[]{1, 2, 3});
        document.set("items", List.of("one", 2, false));
        document.save(path);

        NodeLedgerDocument loaded = NodeLedger.open(path);

        assertEquals("alice", loaded.get("user.name", String.class));
        assertEquals(30, loaded.get("user.age", Integer.class));
        assertEquals(true, loaded.get("user.active", Boolean.class));
        assertArrayEquals(new byte[]{1, 2, 3}, loaded.get("blob", byte[].class));
        assertEquals(List.of("one", 2, false), loaded.get("items", List.class));
        assertTrue(loaded.root().containsKey("user"));
        assertTrue(loaded.root().getNode("user").containsKey("name"));
        assertFalse(loaded.isDirty());
    }

    @Test
    void savingLoadedDocumentAppendsNewRoot() throws Exception {
        Path path = testFile("append.nl");
        NodeLedgerDocument document = NodeLedger.create();
        document.set("value", "first");
        document.save(path);
        long firstSize = Files.size(path);

        NodeLedgerDocument loaded = NodeLedger.open(path);
        loaded.set("value", "second");
        loaded.save(path);
        long secondSize = Files.size(path);

        assertTrue(secondSize > firstSize);
        assertEquals("second", NodeLedger.open(path).get("value", String.class));
    }

    @Test
    void highLevelApiTrimCompactsOpenedDocument() throws Exception {
        Path source = testFile("source.nl");
        Path trimmed = testFile("trimmed.nl");
        NodeLedgerDocument document = NodeLedger.create();
        document.set("value", "first");
        document.set("user.name", "alice");
        document.set("user.age", 30);
        document.set("user.active", true);
        document.set("blob", new byte[]{1, 2, 3});
        document.set("items", List.of("one", 2, false));
        document.save(source);

        NodeLedgerDocument loaded = NodeLedger.open(source);
        loaded.set("value", "second");
        loaded.set("user.age", 31);
        assertTrue(loaded.remove("user.active"));
        loaded.save(source);
        long appendedSize = Files.size(source);

        loaded.trim(trimmed);
        NodeLedgerDocument compacted = NodeLedger.open(trimmed);

        assertTrue(Files.size(trimmed) < appendedSize);
        assertEquals(trimmed, loaded.path().orElseThrow());
        assertFalse(loaded.isDirty());
        assertEquals(Files.size(trimmed), loaded.fileSize());
        assertTrue(loaded.lastTrimTimestamp() > 0);
        assertEquals("second", compacted.get("value", String.class));
        assertEquals("alice", compacted.get("user.name", String.class));
        assertEquals(31, compacted.get("user.age", Integer.class));
        assertFalse(compacted.contains("user.active"));
        assertArrayEquals(new byte[]{1, 2, 3}, compacted.get("blob", byte[].class));
        assertEquals(List.of("one", 2, false), compacted.get("items", List.class));
        assertThrows(NodeLedgerException.class, () -> compacted.trim(trimmed));
    }

    @Test
    void customTypeRoundTripsThroughRegisteredCodec() throws Exception {
        Path path = testFile("custom.nl");
        long pointType = NodeLedgerTypeId.pack("POINT");
        NodeLedgerParser parser = NodeLedger.parser();
        parser.register(Point.class, pointType, payload -> new Point(payload.getInt(), payload.getInt()), point -> {
            ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES * 2).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(point.x());
            buffer.putInt(point.y());
            buffer.flip();
            return buffer;
        });

        NodeLedgerDocument document = parser.create();
        document.set("point", new Point(10, 20));
        document.save(path);

        NodeLedgerDocument loaded = parser.read(path);

        assertEquals(new Point(10, 20), loaded.get("point", Point.class));
    }

    @Test
    void pathsUseDotsAndRejectEmptySegments() {
        NodeLedgerDocument document = NodeLedger.create();

        assertThrows(
            IllegalArgumentException.class,
            () -> document.set("user..name", "alice")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> document.set(".user", "alice")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> document.set("user.", "alice")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> document.set(" user.name", "alice")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> document.set("user.name ", "alice")
        );
    }

    @Test
    void duplicateNodeKeysAreRejectedWhenReading() throws Exception {
        Path path = testFile("duplicate-keys.nl");
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        writeHeader(file);
        long nullOffset = file.size();
        writeLong(file, NodeLedgerTypeId.NULL);
        writeLong(file, 0L);
        long rootOffset = file.size();
        writeLong(file, NodeLedgerTypeId.NODE);
        writeLong(file, 2L);
        writeNodeEntry(file, "dup", NodeLedgerTypeId.NULL, nullOffset);
        writeNodeEntry(file, "dup", NodeLedgerTypeId.NULL, nullOffset);
        writeLong(file, rootOffset);
        Files.write(path, file.toByteArray());

        assertThrows(NodeLedgerException.class, () -> NodeLedger.open(path));
    }

    @Test
    void entryTypeMustMatchObjectTypeWhenOpening() throws Exception {
        Path path = testFile("entry-object-mismatch.nl");
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        writeHeader(file);
        long objectOffset = file.size();
        writeLong(file, NodeLedgerTypeId.STR);
        writeLong(file, 0L);
        long rootOffset = file.size();
        writeLong(file, NodeLedgerTypeId.NODE);
        writeLong(file, 1L);
        writeNodeEntry(file, "value", NodeLedgerTypeId.BOOL, objectOffset);
        writeLong(file, rootOffset);
        Files.write(path, file.toByteArray());

        assertThrows(NodeLedgerException.class, () -> NodeLedger.open(path));
    }

    @Test
    void nodeCyclesAreRejectedWhenReading() throws Exception {
        Path path = testFile("node-cycle.nl");
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        writeHeader(file);
        long rootOffset = file.size();
        writeLong(file, NodeLedgerTypeId.NODE);
        writeLong(file, 1L);
        writeNodeEntry(file, "self", NodeLedgerTypeId.NODE, rootOffset);
        writeLong(file, rootOffset);
        Files.write(path, file.toByteArray());

        assertThrows(NodeLedgerException.class, () -> NodeLedger.open(path));
    }

    @Test
    void booleanPayloadMustBeZeroOrOne() throws Exception {
        Path path = testFile("invalid-bool.nl");
        writeSingleValueFile(path, NodeLedgerTypeId.BOOL, new byte[]{2});

        NodeLedgerDocument document = NodeLedger.open(path);

        assertThrows(NodeLedgerException.class, () -> document.get("value", Boolean.class));
    }

    @Test
    void stringPayloadMustMatchEnclosingPayloadSize() throws Exception {
        Path path = testFile("string-extra.nl");
        ByteBuffer payload = ByteBuffer.allocate(Long.BYTES + 2).order(ByteOrder.LITTLE_ENDIAN);
        payload.putLong(1L);
        payload.put((byte) 'a');
        payload.put((byte) 'x');
        writeSingleValueFile(path, NodeLedgerTypeId.STR, payload.array());

        NodeLedgerDocument document = NodeLedger.open(path);

        assertThrows(NodeLedgerException.class, () -> document.get("value", String.class));
    }

    @Test
    void listItemPayloadSizeMustBeNonNegative() throws Exception {
        Path path = testFile("list-negative-payload.nl");
        ByteBuffer payload = ByteBuffer.allocate(Long.BYTES * 3).order(ByteOrder.LITTLE_ENDIAN);
        payload.putLong(1L);
        payload.putLong(NodeLedgerTypeId.STR);
        payload.putLong(-1L);
        writeSingleValueFile(path, NodeLedgerTypeId.LIST, payload.array());

        NodeLedgerDocument document = NodeLedger.open(path);

        assertThrows(NodeLedgerException.class, () -> document.get("value", List.class));
    }

    @Test
    void stringPayloadMustBeValidUtf8() throws Exception {
        Path path = testFile("string-invalid-utf8.nl");
        ByteBuffer payload = ByteBuffer.allocate(Long.BYTES + 1).order(ByteOrder.LITTLE_ENDIAN);
        payload.putLong(1L);
        payload.put((byte) 0xC3);
        writeSingleValueFile(path, NodeLedgerTypeId.STR, payload.array());

        NodeLedgerDocument document = NodeLedger.open(path);

        assertThrows(NodeLedgerException.class, () -> document.get("value", String.class));
    }

    @Test
    void duplicateCodecRegistrationsAreRejected() {
        NodeLedgerParser parser = NodeLedger.parser();

        assertThrows(
            IllegalArgumentException.class,
            () -> parser.register(String.class, NodeLedgerTypeId.pack("TEXT"), payload -> "", value -> ByteBuffer.allocate(0))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> parser.register(Point.class, NodeLedgerTypeId.STR, payload -> new Point(0, 0), point -> ByteBuffer.allocate(0))
        );
    }

    @Test
    void unsignedLongValuesMustBeInRange() throws Exception {
        NodeLedgerDocument tooLarge = NodeLedger.create();
        tooLarge.set("value", BigInteger.ONE.shiftLeft(Long.SIZE));

        assertThrows(NodeLedgerException.class, () -> tooLarge.save(testFile("u64-too-large.nl")));

        NodeLedgerDocument negative = NodeLedger.create();
        negative.set("value", BigInteger.valueOf(-1L));

        assertThrows(NodeLedgerException.class, () -> negative.save(testFile("u64-negative.nl")));
    }

    private record Point(int x, int y) {
    }
}
