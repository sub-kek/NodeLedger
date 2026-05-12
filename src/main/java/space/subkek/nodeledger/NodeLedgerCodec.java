package space.subkek.nodeledger;

/**
 * Codec that binds a Java type to a NODELEDGER type id.
 *
 * @param <T> Java type handled by this codec.
 */
public interface NodeLedgerCodec<T> {
    /**
     * Returns the Java type handled by this codec.
     *
     * @return Java type.
     */
    Class<T> javaType();

    /**
     * Returns the NODELEDGER type id handled by this codec.
     *
     * @return NODELEDGER type id.
     */
    long typeId();

    /**
     * Returns the payload reader.
     *
     * @return reader.
     */
    NodeLedgerReader<T> reader();

    /**
     * Returns the payload writer.
     *
     * @return writer.
     */
    NodeLedgerWriter<T> writer();
}
