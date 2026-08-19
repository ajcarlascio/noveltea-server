package com.noveltea.auth;

import java.util.UUID;

/** The authenticated principal: who is calling, and from which paired device. */
public record CurrentUser(UUID userId, UUID deviceId) {}
