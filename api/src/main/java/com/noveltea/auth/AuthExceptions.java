package com.noveltea.auth;

public final class AuthExceptions {
    private AuthExceptions() {}

    /** Credentials, refresh token, or pairing code did not check out. */
    public static class InvalidCredentials extends RuntimeException {
        public InvalidCredentials(String message) {
            super(message);
        }
    }

    /** Authenticated, but not permitted to touch this resource. */
    public static class AccessDenied extends RuntimeException {
        public AccessDenied(String message) {
            super(message);
        }
    }

    public static class EmailAlreadyRegistered extends RuntimeException {
        public EmailAlreadyRegistered(String email) {
            super("already registered: " + email);
        }
    }
}
