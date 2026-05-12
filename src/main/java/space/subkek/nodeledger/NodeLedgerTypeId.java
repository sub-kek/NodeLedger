package space.subkek.nodeledger;

import java.nio.charset.StandardCharsets;

/**
 * NODELEDGER built-in and custom type identifier support.
 */
public final class NodeLedgerTypeId {
    /**
     * Type id for nested nodes.
     */
    public static final long NODE = 0L;

    /**
     * Type id for null values.
     */
    public static final long NULL = pack("NULL");

    /**
     * Type id for boolean values.
     */
    public static final long BOOL = pack("BOOL");

    /**
     * Type id for signed 32-bit integer values.
     */
    public static final long I32 = pack("I32");

    /**
     * Type id for signed 64-bit integer values.
     */
    public static final long I64 = pack("I64");

    /**
     * Type id for unsigned 64-bit integer values.
     */
    public static final long U64 = pack("U64");

    /**
     * Type id for 64-bit floating point values.
     */
    public static final long F64 = pack("F64");

    /**
     * Type id for UTF-8 string values.
     */
    public static final long STR = pack("STR");

    /**
     * Type id for raw byte values.
     */
    public static final long BYTES = pack("BYTES");

    /**
     * Type id for list values.
     */
    public static final long LIST = pack("LIST");

    private NodeLedgerTypeId() {
    }

    /**
     * Packs an ASCII type name into a NODELEDGER type id.
     *
     * @param name ASCII type name with at most eight bytes.
     * @return packed type id.
     */
    public static long pack(String name) {
        if (name.length() > Long.BYTES) {
            throw new IllegalArgumentException("NODELEDGER type names must fit into eight ASCII bytes");
        }

        long typeId = 0L;
        for (int i = 0; i < name.length(); i++) {
            int value = name.charAt(i);
            if (value == 0 || value > 0x7F) {
                throw new IllegalArgumentException("NODELEDGER type names must use non-zero ASCII bytes");
            }
            typeId |= (long) value << (i * Byte.SIZE);
        }
        return typeId;
    }

    /**
     * Unpacks a NODELEDGER type id into an ASCII type name.
     *
     * @param typeId packed type id.
     * @return unpacked type name.
     */
    public static String unpack(long typeId) {
        byte[] bytes = new byte[Long.BYTES];
        int length = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            byte value = (byte) (typeId >>> (i * Byte.SIZE));
            if (value == 0) {
                break;
            }
            bytes[length++] = value;
        }
        return new String(bytes, 0, length, StandardCharsets.US_ASCII);
    }
}
