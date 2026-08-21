package com.naraesigning.background;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class InMemoryBackgroundObjectStore implements BackgroundObjectStore {
    private final Map<String, byte[]> objects = new LinkedHashMap<>();
    boolean failPut;
    boolean failDeleteOnce;

    @Override
    public void put(String objectKey, byte[] ciphertext) {
        objects.put(objectKey, ciphertext.clone());
        if (failPut) throw new BackgroundStoreException();
    }

    @Override
    public void delete(String objectKey) {
        if (failDeleteOnce) {
            failDeleteOnce = false;
            throw new BackgroundStoreException();
        }
        objects.remove(objectKey);
    }

    @Override public byte[] get(String key) { return Arrays.copyOf(objects.get(key), objects.get(key).length); }
    Set<String> keys() { return Set.copyOf(objects.keySet()); }
}
