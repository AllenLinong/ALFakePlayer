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

    static DummyTier fromCommand(String command) {
        return switch (command.toLowerCase(java.util.Locale.ROOT)) {
            case "spawn", "spawnnormal", "normal" -> NORMAL;
            case "spawnpro", "pro" -> PRO;
            case "spawnpromax", "promax" -> PROMAX;
            default -> null;
        };
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
