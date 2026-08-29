package com.noveltea.model;

import java.util.Optional;

/**
 * Mirrors the CHECK constraint on {@code device.platform}.
 *
 * Must also match {@code Platform} in the client's {@code src/features/auth/api.ts},
 * which is the only thing that ever sets this field. It did not: this held WINDOWS and
 * MACOS while the client sent "tauri" and "android", so the two overlapped on web and
 * ios alone and every desktop sign-in was recorded as a browser.
 *
 * WINDOWS and MACOS are kept so no row already written becomes invalid, and so a later
 * shell can be specific about which desktop it is if that ever earns its keep.
 */
public enum DevicePlatform {
    WEB("web"),
    TAURI("tauri"),
    ANDROID("android"),
    IOS("ios"),
    WINDOWS("windows"),
    MACOS("macos"),
    LINUX("linux");

    private final String wire;

    DevicePlatform(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<DevicePlatform> fromWire(String value) {
        return WireEnums.lookup(DevicePlatform.class, DevicePlatform::wire, value);
    }
}
