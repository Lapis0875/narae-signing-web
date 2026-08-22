package com.naraesigning.background;

public interface BackgroundObjectStore {
    byte[] get(String objectKey);
    void put(String objectKey, byte[] ciphertext);
    void delete(String objectKey);
}
