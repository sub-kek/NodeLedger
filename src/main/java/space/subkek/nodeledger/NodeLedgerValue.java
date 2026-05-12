package space.subkek.nodeledger;

import java.nio.file.Path;

/**
 * Internal representation of a typed NODELEDGER value.
 */
class NodeLedgerValue {
    private final long typeId;
    private final Object value;
    private Path sourcePath;
    private long objectOffset;
    private long payloadSize;
    private boolean dirty;

    /**
     * Creates an empty value placeholder.
     */
    public NodeLedgerValue() {
        this(NodeLedgerTypeId.NULL, null, null, -1L, 0L, true);
    }

    NodeLedgerValue(long typeId, Object value) {
        this(typeId, value, null, -1L, -1L, true);
    }

    NodeLedgerValue(long typeId, Path sourcePath, long objectOffset) {
        this(typeId, null, sourcePath, objectOffset, -1L, false);
    }

    private NodeLedgerValue(long typeId, Object value, Path sourcePath, long objectOffset, long payloadSize, boolean dirty) {
        this.typeId = typeId;
        this.value = value;
        this.sourcePath = sourcePath;
        this.objectOffset = objectOffset;
        this.payloadSize = payloadSize;
        this.dirty = dirty;
    }

    /**
     * Returns this value's NODELEDGER type id.
     *
     * @return type id.
     */
    public long typeId() {
        return typeId;
    }

    /**
     * Returns the decoded Java value when it is already available.
     *
     * @return Java value.
     */
    public Object value() {
        return value;
    }

    /**
     * Returns whether this value stores a null value.
     *
     * @return {@code true} when this value is null.
     */
    public boolean isNull() {
        return typeId == NodeLedgerTypeId.NULL;
    }

    Path sourcePath() {
        return sourcePath;
    }

    long objectOffset() {
        return objectOffset;
    }

    long payloadSize() {
        return payloadSize;
    }

    void payloadSize(long payloadSize) {
        this.payloadSize = payloadSize;
    }

    boolean isDirty() {
        return dirty;
    }

    boolean isStored() {
        return sourcePath != null && objectOffset >= 0L;
    }

    void markStored(Path sourcePath, long objectOffset, long payloadSize) {
        this.sourcePath = sourcePath;
        this.objectOffset = objectOffset;
        this.payloadSize = payloadSize;
        this.dirty = false;
    }
}
