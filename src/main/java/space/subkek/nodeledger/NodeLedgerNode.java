package space.subkek.nodeledger;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * NodeLedger tree node containing named child values and nested nodes.
 */
public class NodeLedgerNode {
    private final TreeMap<String, Entry> entries = new TreeMap<>();

    /**
     * Creates an empty node.
     */
    NodeLedgerNode() {
    }

    private static void validateKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("NODELEDGER node key must not be empty");
        }
        if (key.indexOf('.') >= 0) {
            throw new IllegalArgumentException("NODELEDGER node key must not contain dots");
        }
    }

    /**
     * Stores a child value by key.
     *
     * @param key   child key.
     * @param value child value.
     */
    void put(String key, NodeLedgerValue value) {
        validateKey(key);
        entries.put(key, Entry.value(value));
    }

    /**
     * Stores a nested child node by key.
     *
     * @param key  child key.
     * @param node child node.
     */
    void putNode(String key, NodeLedgerNode node) {
        validateKey(key);
        entries.put(key, Entry.node(node));
    }

    /**
     * Reads a child value by key.
     *
     * @param key child key.
     * @return child value, or {@code null} when no value exists.
     */
    NodeLedgerValue get(String key) {
        validateKey(key);
        Entry entry = entries.get(key);
        if (entry == null || entry.node() != null) {
            return null;
        }
        return entry.value();
    }

    /**
     * Reads a nested child node by key.
     *
     * @param key child key.
     * @return child node, or {@code null} when no node exists.
     */
    public NodeLedgerNode getNode(String key) {
        validateKey(key);
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        return entry.node();
    }

    /**
     * Removes a child by key.
     *
     * @param key child key.
     * @return {@code true} when a child was removed.
     */
    boolean remove(String key) {
        validateKey(key);
        return entries.remove(key) != null;
    }

    /**
     * Returns whether a child key exists.
     *
     * @param key child key.
     * @return {@code true} when the key exists.
     */
    public boolean containsKey(String key) {
        validateKey(key);
        return entries.containsKey(key);
    }

    /**
     * Returns child keys.
     *
     * @return child keys.
     */
    public Set<String> keys() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    /**
     * Returns value children.
     *
     * @return value child map.
     */
    Map<String, NodeLedgerValue> values() {
        TreeMap<String, NodeLedgerValue> values = new TreeMap<>();
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            if (entry.getValue().value() != null) {
                values.put(entry.getKey(), entry.getValue().value());
            }
        }
        return Collections.unmodifiableMap(values);
    }

    Map<String, Entry> entries() {
        return Collections.unmodifiableMap(entries);
    }

    static final class Entry {
        private final NodeLedgerValue value;
        private final NodeLedgerNode node;

        private Entry(NodeLedgerValue value, NodeLedgerNode node) {
            this.value = value;
            this.node = node;
        }

        static Entry value(NodeLedgerValue value) {
            return new Entry(value, null);
        }

        static Entry node(NodeLedgerNode node) {
            return new Entry(null, node);
        }

        NodeLedgerValue value() {
            return value;
        }

        NodeLedgerNode node() {
            return node;
        }
    }
}
