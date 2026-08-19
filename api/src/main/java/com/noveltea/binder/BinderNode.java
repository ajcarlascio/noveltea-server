package com.noveltea.binder;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One row of the binder tree. Clients assemble the hierarchy from the flat list. */
public record BinderNode(
        UUID id,
        UUID parentId,
        String type,
        String title,
        String orderKey,
        UUID labelId,
        UUID statusId,
        UUID trashedFromParentId,
        long version,
        OffsetDateTime updatedAt) {}
