package space.subkek.nodeledger;

import java.nio.ByteBuffer;

/**
 * Writer for encoding Java values into NODELEDGER payload bytes.
 *
 * @param <T> Java type consumed by this writer.
 */
public interface NodeLedgerWriter<T> {
    /**
     * Writes a Java value into NODELEDGER payload bytes.
     *
     * @param value Java value.
     * @return encoded payload bytes.
     * @throws NodeLedgerException when encoding fails.
     */
    ByteBuffer write(T value) throws NodeLedgerException;
}
