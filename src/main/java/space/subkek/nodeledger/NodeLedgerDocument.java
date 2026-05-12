package space.subkek.nodeledger;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Mutable NodeLedger document rooted at a node tree.
 */
public class NodeLedgerDocument {
    private final NodeLedgerParser parser;
    private final NodeLedgerNode root;
    private Path path;
    private long fileSize;
    private long lastTrimTimestamp;
    private boolean dirty;

    /**
     * Creates an empty document.
     */
    NodeLedgerDocument() {
        this(new NodeLedgerParser(), null, new NodeLedgerNode(), 0L, 0L, true);
    }

    NodeLedgerDocument(
        NodeLedgerParser parser,
        Path path,
        NodeLedgerNode root,
        long fileSize,
        long lastTrimTimestamp,
        boolean dirty
    ) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.path = path;
        this.root = Objects.requireNonNull(root, "root");
        this.fileSize = fileSize;
        this.lastTrimTimestamp = lastTrimTimestamp;
        this.dirty = dirty;
    }

    private static PathParts splitPath(String path) {
        if (path.isEmpty()) {
            throw new IllegalArgumentException("path must not be empty");
        }

        String[] parts = path.split("\\.", -1);
        for (String part : parts) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException("path must not contain empty segments");
            }
            if (!part.equals(part.strip())) {
                throw new IllegalArgumentException("path segments must not contain surrounding whitespace");
            }
            if (part.indexOf('.') >= 0) {
                throw new IllegalArgumentException("path keys must not contain dots");
            }
        }
        return new PathParts(parts);
    }

    /**
     * Returns the root node.
     *
     * @return root node.
     */
    public NodeLedgerNode root() {
        return root;
    }

    /**
     * Returns the source file path when this document was opened from a file.
     *
     * @return source path.
     */
    public Optional<Path> path() {
        return Optional.ofNullable(path);
    }

    /**
     * Returns the file size observed when the document was opened or saved.
     *
     * @return file size in bytes.
     */
    public long fileSize() {
        return fileSize;
    }

    /**
     * Returns the last trim timestamp stored in the NODELEDGER header.
     *
     * @return last trim timestamp.
     */
    public long lastTrimTimestamp() {
        return lastTrimTimestamp;
    }

    /**
     * Stores a value at a path.
     *
     * @param path  document path.
     * @param value value to store.
     * @param <T>   Java value type.
     */
    public <T> void set(String path, T value) {
        Objects.requireNonNull(path, "path");
        PathParts parts = splitPath(path);
        NodeLedgerNode node = root;
        for (String part : parts.parents()) {
            NodeLedgerNode child = node.getNode(part);
            if (child == null) {
                child = new NodeLedgerNode();
                node.putNode(part, child);
            }
            node = child;
        }
        node.put(parts.name(), new NodeLedgerValue(typeIdForValue(value), value));
        dirty = true;
    }

    /**
     * Reads a value from a path.
     *
     * @param path     document path.
     * @param javaType expected Java type.
     * @param <T>      Java value type.
     * @return decoded value.
     * @throws NodeLedgerException when the value cannot be read as the requested type.
     */
    public <T> T get(String path, Class<T> javaType) throws NodeLedgerException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(javaType, "javaType");
        NodeLedgerValue value = findValue(path);
        if (value == null) {
            return null;
        }
        return parser.readValue(value, javaType);
    }

    /**
     * Removes a value or node at a path.
     *
     * @param path document path.
     * @return {@code true} when a value was removed.
     */
    public boolean remove(String path) {
        Objects.requireNonNull(path, "path");
        PathParts parts = splitPath(path);
        NodeLedgerNode node = findParent(parts);
        if (node == null) {
            return false;
        }
        boolean removed = node.remove(parts.name());
        dirty |= removed;
        return removed;
    }

    /**
     * Checks whether a path exists.
     *
     * @param path document path.
     * @return {@code true} when the path exists.
     */
    public boolean contains(String path) {
        Objects.requireNonNull(path, "path");
        PathParts parts = splitPath(path);
        NodeLedgerNode node = findParent(parts);
        return node != null && node.containsKey(parts.name());
    }

    /**
     * Returns whether this document has unsaved changes.
     *
     * @return {@code true} when the document is dirty.
     */
    public boolean isDirty() {
        return dirty;
    }

    /**
     * Saves this document to a file.
     *
     * @param path target file path.
     * @throws NodeLedgerException when writing fails.
     */
    public void save(Path path) throws NodeLedgerException {
        parser.write(this, path);
    }

    /**
     * Compacts this document into a new file.
     *
     * @param path target file path.
     * @throws NodeLedgerException when trimming fails.
     */
    public void trim(Path path) throws NodeLedgerException {
        if (this.path != null && this.path.equals(path)) {
            throw new NodeLedgerException("trim target must be different from the source file");
        }
        parser.writeCompacted(this, path);
    }

    NodeLedgerParser parser() {
        return parser;
    }

    void markSaved(Path path, long fileSize) {
        this.path = path;
        this.fileSize = fileSize;
        this.dirty = false;
    }

    void lastTrimTimestamp(long lastTrimTimestamp) {
        this.lastTrimTimestamp = lastTrimTimestamp;
    }

    private NodeLedgerValue findValue(String path) {
        PathParts parts = splitPath(path);
        NodeLedgerNode node = findParent(parts);
        if (node == null) {
            return null;
        }
        return node.get(parts.name());
    }

    private NodeLedgerNode findParent(PathParts parts) {
        NodeLedgerNode node = root;
        for (String part : parts.parents()) {
            node = node.getNode(part);
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    private long typeIdForValue(Object value) {
        if (value == null) {
            return NodeLedgerTypeId.NULL;
        }

        NodeLedgerCodec<?> codec = parser.registry().findByJavaType(value.getClass());
        if (codec == null) {
            throw new IllegalArgumentException("No NODELEDGER codec registered for " + value.getClass().getName());
        }
        return codec.typeId();
    }

    private record PathParts(String[] parts) {
        private String[] parents() {
            String[] parents = new String[Math.max(0, parts.length - 1)];
            System.arraycopy(parts, 0, parents, 0, parents.length);
            return parents;
        }

        private String name() {
            return parts[parts.length - 1];
        }
    }
}
