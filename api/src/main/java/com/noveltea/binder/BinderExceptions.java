package com.noveltea.binder;

import java.util.UUID;

public final class BinderExceptions {
    private BinderExceptions() {}

    public static class BinderItemNotFound extends RuntimeException {
        public BinderItemNotFound(UUID id) {
            super("binder item not found: " + id);
        }
    }

    /** Attempt to move an item beneath itself or one of its own descendants. */
    public static class BinderCycle extends RuntimeException {
        public BinderCycle(UUID itemId, UUID newParentId) {
            super("moving " + itemId + " under " + newParentId + " would create a cycle");
        }
    }

    public static class CrossProjectMove extends RuntimeException {
        public CrossProjectMove(UUID itemId, UUID newParentId) {
            super("cannot move " + itemId + " under an item in a different project: " + newParentId);
        }
    }
}
