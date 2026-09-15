package com.noveltea.sync;

public final class SyncExceptions {
    private SyncExceptions() {}

    /**
     * The push carried more changes than the server accepts in one request.
     *
     * <p>Refused whole, before anything is applied. A batch stopped halfway would leave
     * the client holding a partial {@code applied} list for a request it has to send
     * again, which is the shape that turns accepted changes into spurious conflict
     * copies on the retry.
     *
     * <p>The limit travels in the error body rather than only in the documentation,
     * because the client's remedy is to split the batch and it cannot do that against a
     * number it has to guess.
     */
    public static class PushBatchTooLarge extends RuntimeException {
        private final int limit;
        private final int sent;

        public PushBatchTooLarge(int limit, int sent) {
            super("this push carries " + sent + " changes; at most " + limit
                    + " are accepted in one request");
            this.limit = limit;
            this.sent = sent;
        }

        public int limit() {
            return limit;
        }

        public int sent() {
            return sent;
        }
    }
}
