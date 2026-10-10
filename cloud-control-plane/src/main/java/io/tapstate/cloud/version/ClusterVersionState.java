package io.tapstate.cloud.version;

/**
 * Listing state of a published Cluster version.
 * <ul>
 *   <li>{@link #ACTIVE} — listed and selectable for new subscriptions (上架)</li>
 *   <li>{@link #OFFLINE} — delisted; absent from the list and default
 *       selection, but Clusters already pinned to it still deploy it (下架)</li>
 * </ul>
 */
public enum ClusterVersionState {
    ACTIVE,
    OFFLINE
}
