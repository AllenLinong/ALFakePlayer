package io.github.dummyplayers;

enum DummyTier {
    NORMAL("normal", 3),
    PRO("pro", 5),
    PROMAX("promax", 7);

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
