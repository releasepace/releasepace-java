package io.releasepace;

import java.nio.charset.StandardCharsets;

/**
 * Canonical rollout bucketing.
 *
 * <p>Must produce identical buckets to the API and to every other SDK.
 * If implementations drift, the same user is enabled in one runtime and
 * disabled in another — a bug that presents as a race condition and is
 * miserable to trace.
 *
 * <p>Algorithm: MurmurHash3 x86 32-bit, seed 0. Input is
 * {@code "{flagKey}:{entityId}"} encoded UTF-8; bucket is
 * {@code hash % 100}.
 *
 * <p>The fixture in {@code src/test/resources/bucketing-vectors.json}
 * is the source of truth. Do not change this implementation without
 * changing every other SDK in lockstep.
 */
public final class Bucketing {

    private static final int C1 = 0xcc9e2d51;
    private static final int C2 = 0x1b873593;

    private Bucketing() {}

    /**
     * MurmurHash3 x86 32-bit.
     *
     * <p>Java has no unsigned int, so the hash is computed in a signed
     * {@code int} (identical bit pattern) and widened to {@code long}
     * only at the end to present the unsigned value.
     */
    public static long murmur3_32(String key) {
        return murmur3_32(key, 0);
    }

    public static long murmur3_32(String key, int seed) {
        byte[] data = key.getBytes(StandardCharsets.UTF_8);
        int length = data.length;
        int nblocks = length >> 2;
        int h1 = seed;

        for (int i = 0; i < nblocks; i++) {
            int j = i * 4;
            int k1 = (data[j] & 0xff)
                    | ((data[j + 1] & 0xff) << 8)
                    | ((data[j + 2] & 0xff) << 16)
                    | ((data[j + 3] & 0xff) << 24);

            k1 *= C1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= C2;

            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }

        int k1 = 0;
        int tail = nblocks * 4;
        switch (length & 3) {
            case 3:
                k1 ^= (data[tail + 2] & 0xff) << 16;
                // falls through
            case 2:
                k1 ^= (data[tail + 1] & 0xff) << 8;
                // falls through
            case 1:
                k1 ^= (data[tail] & 0xff);
                k1 *= C1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= C2;
                h1 ^= k1;
                break;
            default:
                break;
        }

        h1 ^= length;
        h1 ^= (h1 >>> 16);
        h1 *= 0x85ebca6b;
        h1 ^= (h1 >>> 13);
        h1 *= 0xc2b2ae35;
        h1 ^= (h1 >>> 16);

        return Integer.toUnsignedLong(h1);
    }

    /**
     * Deterministic 0–99 bucket for a flag/entity pair.
     *
     * <p>The flag key is part of the hash on purpose: hashing the
     * entity alone would put the same unlucky cohort in the first N% of
     * every flag forever.
     */
    public static int bucketOf(String flagKey, String entityId) {
        return (int) (murmur3_32(flagKey + ":" + entityId) % 100L);
    }

    /**
     * Whether an entity falls inside a rollout percentage.
     *
     * <p>Ramping only ever adds entities, so nobody loses a feature
     * they have already started using.
     */
    public static boolean inRollout(String flagKey, String entityId, int rolloutPct) {
        if (rolloutPct <= 0) {
            return false;
        }
        if (rolloutPct >= 100) {
            return true;
        }
        return bucketOf(flagKey, entityId) < rolloutPct;
    }
}
