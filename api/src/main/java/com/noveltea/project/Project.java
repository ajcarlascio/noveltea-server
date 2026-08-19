package com.noveltea.project;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

public record Project(
        UUID id,
        UUID ownerId,
        String title,
        JsonNode settings,
        int documentCount,
        long wordCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime deletedAt) {}
