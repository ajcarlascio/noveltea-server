package com.noveltea.project;

import java.util.UUID;

public final class ProjectExceptions {
    private ProjectExceptions() {}

    /** Permanent destruction was attempted on a project that is still live. */
    public static class ProjectNotDeleted extends RuntimeException {
        public ProjectNotDeleted(UUID id) {
            super("project " + id + " must be deleted before it can be purged");
        }
    }

    /** A client-supplied project id already belongs to another owner. Says nothing more. */
    public static class ProjectIdTaken extends RuntimeException {
        public ProjectIdTaken() {
            super("that project id is already in use");
        }
    }
}
