package com.naraesigning.background;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;

final class MinioBackgroundObjectStore implements BackgroundObjectStore {
    private final MinioClient minio;
    private final String bucket;

    MinioBackgroundObjectStore(MinioClient minio, String bucket) {
        this.minio = minio;
        this.bucket = bucket;
    }

    @Override
    public void put(String objectKey, byte[] ciphertext) {
        try (var input = new ByteArrayInputStream(ciphertext)) {
            minio.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey)
                    .stream(input, ciphertext.length, -1).contentType("application/octet-stream").build());
        } catch (Exception exception) {
            throw new BackgroundStoreException(exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception exception) {
            throw new BackgroundStoreException(exception);
        }
    }
}
