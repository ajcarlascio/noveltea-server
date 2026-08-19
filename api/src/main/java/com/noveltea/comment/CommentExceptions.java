package com.noveltea.comment;

import java.util.UUID;

public final class CommentExceptions {
    private CommentExceptions() {}

    public static class CommentNotFound extends RuntimeException {
        public CommentNotFound(UUID id) {
            super("comment not found: " + id);
        }
    }

    /** Editing or deleting someone else's comment. */
    public static class NotTheAuthor extends RuntimeException {
        public NotTheAuthor() {
            super("only the author can change a comment");
        }
    }
}
