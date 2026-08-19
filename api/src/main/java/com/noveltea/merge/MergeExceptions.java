package com.noveltea.merge;

import java.util.UUID;

public final class MergeExceptions {
    private MergeExceptions() {}

    /** The referenced item is not an unresolved conflict copy. */
    public static class NotAConflictCopy extends RuntimeException {
        public NotAConflictCopy(UUID id) {
            super("not an unresolved conflict copy: " + id);
        }
    }

    /** The original moved on while the author was merging. */
    public static class StaleOriginal extends RuntimeException {
        private final long currentVersion;

        public StaleOriginal(UUID id, long expected, long actual) {
            super("original " + id + " is at version " + actual + ", not " + expected);
            this.currentVersion = actual;
        }

        public long currentVersion() {
            return currentVersion;
        }
    }
}
