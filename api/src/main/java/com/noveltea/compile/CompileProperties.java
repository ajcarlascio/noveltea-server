package com.noveltea.compile;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param storagePath where `server` exports are written. In Docker this is the operator's
 *     mounted volume; the author manages what lands there.
 * @param stagingPath where `download` exports wait to be collected. Purged after
 *     {@code downloadTtl}, so it can be a container-local scratch directory.
 */
@ConfigurationProperties(prefix = "noveltea.compile")
public record CompileProperties(
        String storagePath, String stagingPath, Duration downloadTtl, Duration serverRetention) {

    public CompileProperties {
        storagePath = orDefault(storagePath, "/var/lib/noveltea/exports");
        stagingPath = orDefault(stagingPath, "/var/lib/noveltea/staging");
        downloadTtl = downloadTtl == null ? Duration.ofHours(1) : downloadTtl;
        serverRetention = serverRetention; // null means keep indefinitely
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
