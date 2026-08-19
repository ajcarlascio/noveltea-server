package com.noveltea.snapshot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param keepAutomaticPerDocument how many editor-triggered captures to retain. Manual
 *     snapshots are never pruned: the author asked for those.
 */
@ConfigurationProperties(prefix = "noveltea.snapshots")
public record SnapshotProperties(Integer keepAutomaticPerDocument) {

    public SnapshotProperties {
        keepAutomaticPerDocument =
                keepAutomaticPerDocument == null || keepAutomaticPerDocument < 1
                        ? 25
                        : keepAutomaticPerDocument;
    }
}
