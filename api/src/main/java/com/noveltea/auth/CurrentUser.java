package com.noveltea.auth;

import java.util.UUID;

/**
 * The authenticated principal: who is calling, and from which paired device.
 *
 * @param mustChangePassword true while somebody other than the account holder knows the
 *     current password — a freshly bootstrapped administrator, or an account an admin
 *     created or reset. {@code PasswordChangeRequiredFilter} refuses every route but the
 *     one that fixes it, so no handler needs to check this itself.
 */
public record CurrentUser(UUID userId, UUID deviceId, boolean mustChangePassword) {

    /** The ordinary case: nothing outstanding before this caller may use the API. */
    public CurrentUser(UUID userId, UUID deviceId) {
        this(userId, deviceId, false);
    }
}
