package space.subkek.nodeledger;

import java.nio.ByteBuffer;

/**
 * Reader for decoding NODELEDGER payload bytes into Java values.
 *
 * @param <T> Java type produced by this reader.
 */
public interface NodeLedgerReader<T> {
    /**
     * Reads a Java value from a NODELEDGER payload.
     *
     * @param payload payload bytes.
     * @return decoded value.
     * @throws NodeLedgerException when decoding fails.
     */
    T read(ByteBuffer payload) throws NodeLedgerException;
}
