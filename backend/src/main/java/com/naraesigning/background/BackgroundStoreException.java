package com.naraesigning.background;

public final class BackgroundStoreException extends RuntimeException {
    public BackgroundStoreException() {
        super("BACKGROUND_STORE_UNAVAILABLE");
    }

    BackgroundStoreException(Throwable cause) {
        super("BACKGROUND_STORE_UNAVAILABLE", cause);
    }
}
