package io.tapstate.cloud.cluster;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Convert Cluster display names into a single RFC 1123 DNS label used as the
 * left-most label of a Cluster public domain.
 * <p>
 * Normalization rules (deterministic, locale-independent):
 * <ol>
 *   <li>trim surrounding whitespace and lowercase ASCII</li>
 *   <li>every maximal run of characters outside {@code [a-z0-9]} becomes one
 *       hyphen — spaces, underscores, dots, CJK ideographs and punctuation all
 *       collapse the same way</li>
 *   <li>strip leading and trailing hyphens</li>
 * </ol>
 * Examples: {@code "My Cluster"} &rarr; {@code my-cluster},
 * {@code "Lifecycle-Cluster"} &rarr; {@code lifecycle-cluster},
 * {@code "生产_01"} &rarr; {@code 01}, {@code "集群"} &rarr; {@code ""}
 * (rejected at creation).
 * <p>
 * A valid label is 1&ndash;{@value #MAX_LABEL_LENGTH} characters of
 * {@code [a-z0-9-]} with alphanumeric edges, so it matches a single wildcard
 * certificate label and never contains a dot that would alter the zone.
 */
public final class DnsLabels {

    /** Maximum length of one DNS label (RFC 1035). */
    public static final int MAX_LABEL_LENGTH = 63;

    private static final Pattern NON_LABEL_RUN = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_HYPHENS = Pattern.compile("^-+|-+$");
    private static final Pattern VALID_LABEL =
        Pattern.compile("[a-z0-9](?:[a-z0-9-]{0," + (MAX_LABEL_LENGTH - 2) + "}[a-z0-9])?");

    private DnsLabels() {
    }

    /**
     * Normalize a Cluster display name into a candidate DNS label. The result
     * is syntactically safe to embed in a domain, but may be empty or too long;
     * validate it with {@link #isValidLabel(String)} before use.
     */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String label = NON_LABEL_RUN.matcher(name.trim().toLowerCase(Locale.ROOT)).replaceAll("-");
        return EDGE_HYPHENS.matcher(label).replaceAll("");
    }

    /** Whether the candidate is a non-empty, length-bounded RFC 1123 label. */
    public static boolean isValidLabel(String label) {
        return label != null && VALID_LABEL.matcher(label).matches();
    }
}
