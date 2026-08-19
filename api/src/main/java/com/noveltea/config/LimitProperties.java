package com.noveltea.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable limits, in one place and overridable per environment.
 *
 * <p>These were previously scattered as private static ints inside services, where a
 * deployment could not change them and two services could disagree about the same bound.
 * Defaults live in the compact constructor so an unset property is still valid.
 */
@ConfigurationProperties(prefix = "noveltea.limits")
public record LimitProperties(
        Integer syncPageSize,
        Integer maxSyncPageSize,
        Integer maxPushBatchSize,
        Integer maxTitleLength,
        Integer maxNameLength,
        Integer maxDocumentBytes,
        Integer maxPendingCompilesPerUser,
        Integer maxSyncPageBytes,
        Integer maxRequestBytes) {

    public LimitProperties {
        syncPageSize = orDefault(syncPageSize, 200);
        maxSyncPageSize = orDefault(maxSyncPageSize, 500);
        maxPushBatchSize = orDefault(maxPushBatchSize, 500);
        maxTitleLength = orDefault(maxTitleLength, 500);
        maxNameLength = orDefault(maxNameLength, 200);
        // 8 MiB of ProseMirror JSON is a very long chapter; beyond that something is wrong
        // and we would rather refuse than let it through to the database.
        maxDocumentBytes = orDefault(maxDocumentBytes, 8 * 1024 * 1024);
        maxPendingCompilesPerUser = orDefault(maxPendingCompilesPerUser, 5);
        // A page stops at whichever comes first, rows or bytes. Mobile data is the reason:
        // 500 rows of full documents has no predictable size.
        maxSyncPageBytes = orDefault(maxSyncPageBytes, 4 * 1024 * 1024);
        // High on purpose. Some authors keep an entire novel in one document, and 200k
        // words is several megabytes of ProseMirror JSON.
        maxRequestBytes = orDefault(maxRequestBytes, 32 * 1024 * 1024);
    }

    private static Integer orDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
