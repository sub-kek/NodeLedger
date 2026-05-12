package space.subkek.nodeledger;

/**
 * Base checked exception for NodeLedger parsing and writing failures.
 */
public class NodeLedgerException extends Exception {
    /**
     * Creates an exception with no detail message.
     */
    public NodeLedgerException() {
    }

    /**
     * Creates an exception with a detail message.
     *
     * @param message detail message.
     */
    public NodeLedgerException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a detail message and cause.
     *
     * @param message detail message.
     * @param cause   cause.
     */
    public NodeLedgerException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates an exception with a cause.
     *
     * @param cause cause.
     */
    public NodeLedgerException(Throwable cause) {
        super(cause);
    }
}
