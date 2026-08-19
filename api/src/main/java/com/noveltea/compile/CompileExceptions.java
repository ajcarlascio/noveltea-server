package com.noveltea.compile;

public final class CompileExceptions {
    private CompileExceptions() {}

    /** The requested format or destination is not part of this edition. */
    public static class UnavailableInThisEdition extends RuntimeException {
        public UnavailableInThisEdition(String message) {
            super(message);
        }
    }

    /** The artifact is gone: purged after its TTL, or the job never produced one. */
    public static class ArtifactUnavailable extends RuntimeException {
        public ArtifactUnavailable(String message) {
            super(message);
        }
    }
}
