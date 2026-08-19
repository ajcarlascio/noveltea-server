package com.noveltea.retention;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long the things nobody reads are kept.
 *
 * @param changeLogRetention how long a delete row stays available to offline clients. A
 *     device offline longer than this must resync from scratch, so this is effectively
 *     "how long may a device be away and still sync cheaply".
 * @param tombstoneRetention how long a soft-deleted binder item's row is kept after its
 *     delete has propagated
 * @param artifactGrace how long an expired export file is kept before deletion
 * @param enabled set false to stop the scheduled sweep; the service can still be called
 *     directly, which is how it is tested
 */
@ConfigurationProperties(prefix = "noveltea.retention")
public record RetentionProperties(
        Boolean enabled,
        Duration changeLogRetention,
        Duration tombstoneRetention,
        Duration artifactGrace,
        Duration expiredCredentialRetention,
        Integer batchSize) {

    public RetentionProperties {
        enabled = enabled == null || enabled;
        changeLogRetention = changeLogRetention == null ? Duration.ofDays(90) : changeLogRetention;
        tombstoneRetention = tombstoneRetention == null ? Duration.ofDays(180) : tombstoneRetention;
        artifactGrace = artifactGrace == null ? Duration.ofDays(1) : artifactGrace;
        expiredCredentialRetention =
                expiredCredentialRetention == null ? Duration.ofDays(30) : expiredCredentialRetention;
        batchSize = batchSize == null || batchSize < 1 ? 5000 : batchSize;
    }
}
