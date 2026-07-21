package com.stephanofer.networkpoints.persistence;

import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.UUID;

public final class UuidBinary {

    public static final Comparator<UUID> COMPARATOR = (left, right) -> compare(bytes(left), bytes(right));

    private UuidBinary() {
    }

    public static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    public static UUID uuid(byte[] value) {
        if (value == null || value.length != 16) {
            throw new IllegalArgumentException("UUID binary value must contain exactly 16 bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static int compare(byte[] left, byte[] right) {
        for (int index = 0; index < 16; index++) {
            int comparison = Integer.compare(Byte.toUnsignedInt(left[index]), Byte.toUnsignedInt(right[index]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }
}
