package io.github.dummyplayers;

enum DummyTier {
    NORMAL("normal", 5),
    PRO("pro", 7),
    PROMAX("promax", 10);

    final String key;
    final int defaultChunkSize;

    DummyTier(String key, int defaultChunkSize) {
        this.key = key;
        this.defaultChunkSize = defaultChunkSize;
    }

    static DummyTier fromName(String name) {
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "normal" -> NORMAL;
            case "pro" -> PRO;
            case "promax" -> PROMAX;
            default -> null;
        };
    }
}
