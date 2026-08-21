package com.naraesigning.background;

public interface BackgroundObjectStore {
    void put(String objectKey, byte[] ciphertext);
    void delete(String objectKey);
}
