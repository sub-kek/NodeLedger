package space.subkek.nodeledger;

import java.nio.file.Path;

/**
 * Entry point for NodeLedger document operations.
 */
public final class NodeLedger {
    private NodeLedger() {
    }

    /**
     * Opens a NODELEDGER document from a file.
     *
     * @param path file path.
     * @return opened document.
     * @throws NodeLedgerException when the file cannot be read or parsed.
     */
    public static NodeLedgerDocument open(Path path) throws NodeLedgerException {
        return parser().read(path);
    }

    /**
     * Creates an empty NODELEDGER document.
     *
     * @return empty document.
     */
    public static NodeLedgerDocument create() {
        return parser().create();
    }

    /**
     * Creates a parser with built-in codecs registered.
     *
     * @return default parser.
     */
    public static NodeLedgerParser parser() {
        return new NodeLedgerParser();
    }
}
