package io.tapstate.cloud.version;

/**
 * Release channel of a published Cluster version.
 * <p>
 * The enum constant is the persisted/serialized value; {@link #getLabel()} is
 * the human-facing symbol (alpha, beta, RC, stable).
 */
public enum ClusterReleaseCode {

    /** Internal test release. */
    ALPHA("α"),

    /** Public beta release. */
    BETA("β"),

    /** Release candidate. */
    RC("RC"),

    /** General-availability stable release. */
    STABLE("Stable");

    private final String label;

    ClusterReleaseCode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
