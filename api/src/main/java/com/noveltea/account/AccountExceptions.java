package com.noveltea.account;

public final class AccountExceptions {
    private AccountExceptions() {}

    /** Sign-in attempted on an account whose deletion has already been carried out. */
    public static class AccountDeleted extends RuntimeException {
        public AccountDeleted() {
            super("this account has been deleted");
        }
    }

    public static class NoDeletionPending extends RuntimeException {
        public NoDeletionPending() {
            super("no deletion is scheduled for this account");
        }
    }
}
