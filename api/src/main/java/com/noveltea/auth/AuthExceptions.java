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

    /**
     * Self-registration is off on this instance.
     *
     * <p>Distinct from {@link InvalidCredentials} on purpose, and it leaks nothing: the
     * answer is the same for every caller and every address, so it cannot be used to learn
     * whether an account exists. Somebody who has been told "sign up at my server" needs to
     * hear "ask the administrator", not "invalid credentials".
     */
    public static class RegistrationClosed extends RuntimeException {
        public RegistrationClosed() {
            super("this server does not accept self-registration; ask its administrator "
                    + "for an account");
        }
    }

    public static class EmailAlreadyRegistered extends RuntimeException {
        public EmailAlreadyRegistered(String email) {
            super("already registered: " + email);
        }
    }
}
