package com.noveltea.snapshot;

import java.util.UUID;

public final class SnapshotExceptions {
    private SnapshotExceptions() {}

    public static class SnapshotNotFound extends RuntimeException {
        public SnapshotNotFound(UUID id) {
            super("snapshot not found: " + id);
        }
    }

    /** The document moved on since the version the author was shown. */
    public static class StaleDocument extends RuntimeException {
        private final long currentVersion;

        public StaleDocument(UUID documentId, long expected, long actual) {
            super("document " + documentId + " is at version " + actual + ", not " + expected);
            this.currentVersion = actual;
        }

        public long currentVersion() {
            return currentVersion;
        }
    }
}
