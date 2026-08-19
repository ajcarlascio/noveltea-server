package com.noveltea.model;

import java.util.Optional;

/** Mirrors the CHECK constraint on {@code device.platform}. */
public enum DevicePlatform {
    WEB("web"),
    WINDOWS("windows"),
    MACOS("macos"),
    IOS("ios");

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
