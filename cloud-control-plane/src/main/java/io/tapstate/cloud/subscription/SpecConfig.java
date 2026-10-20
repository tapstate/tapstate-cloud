package io.tapstate.cloud.subscription;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Platform-side subscription specification (channel-agnostic).
 * <p>
 * SpecConfig is the source of truth for what sizes are offered, their
 * resource footprint, and their trial length. Payment channels (Stripe,
 * AWS Marketplace) each maintain their own pricing in a separate collection
 * keyed by {@code key}, so pricing can vary per channel without changing
 * the platform-level spec definition.
 * <p>
 * A SpecConfig is created from {@code classpath:spec-config.json} at
 * bootstrap and loaded into {@code spec_config} collection. When a
 * Subscription is created the SpecConfig is <b>snapshotted</b> onto the
 * Subscription document (and further onto the Cluster) — after that
 * the running subscription never reads the catalog again, so catalog
 * updates are transparent to existing subscribers.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpecConfig {

    /** Canonical spec key, e.g. "2c8g", "4c16g", "8c32g". */
    private String key;

    /** Human-readable display name shown on the Console spec card. */
    private String displayName;

    /** Virtual CPU cores allocated to the instance. */
    private int cpuCores;

    /** Instance memory in GiB. */
    private int memoryGb;

    /** Trial duration in calendar days — 0 means no trial, subscription bills from day 1. */
    private int trialDays;

    /** Atlas MDB storage allocation in GiB. */
    private int mdbSizeGb;

    /** {@code true} when this spec can be used for new subscriptions. */
    private boolean enabled;

    /** Whether this spec grants a free trial. */
    public boolean hasTrial() {
        return trialDays > 0;
    }
}
