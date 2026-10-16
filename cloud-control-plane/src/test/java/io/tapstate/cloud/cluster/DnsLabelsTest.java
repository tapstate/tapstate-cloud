package io.tapstate.cloud.cluster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DnsLabelsTest {

    @ParameterizedTest
    @CsvSource(delimiterString = "=>", value = {
        "My Cluster => my-cluster",
        "Lifecycle-Cluster => lifecycle-cluster",
        "  Foo_Bar.123  => foo-bar-123",
        "ABC => abc",
        "a---b => a-b",
        "a..b => a-b",
        "a__b => a-b",
        "-leading-trailing- => leading-trailing",
        "生产_01 => 01",
        "Évasion Café => vasion-caf",
        "rocket-x => rocket-x",
        "123 => 123",
    })
    void normalizeCollapsesIllegalRunsIntoHyphensAndTrimsEdges(String input, String expected) {
        assertThat(DnsLabels.normalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "---", "我的集群", "___"})
    void rejectsBlankOrUnnormalizableLabels(String input) {
        assertThat(DnsLabels.isValidLabel(DnsLabels.normalize(input))).isFalse();
    }

    @Test
    void cjkOnlyNameNormalizesToEmptyAndIsRejected() {
        assertThat(DnsLabels.normalize("我的集群")).isEmpty();
        assertThat(DnsLabels.isValidLabel(DnsLabels.normalize("我的集群"))).isFalse();
    }

    @Test
    void whitespaceIsASeparatorNotAnIllegalCharacter() {
        // "a b" is two ASCII letter groups joined by one separator — a valid label.
        assertThat(DnsLabels.normalize("a b")).isEqualTo("a-b");
        assertThat(DnsLabels.isValidLabel("a-b")).isTrue();
    }

    @Test
    void acceptsSingleCharacterAndBoundaryLengthLabels() {
        assertThat(DnsLabels.isValidLabel("a")).isTrue();
        assertThat(DnsLabels.isValidLabel("a".repeat(63))).isTrue();
        // RFC 1035 label limit.
        assertThat(DnsLabels.isValidLabel("a".repeat(64))).isFalse();
        // Edges must be alphanumeric.
        assertThat(DnsLabels.isValidLabel("-abc")).isFalse();
        assertThat(DnsLabels.isValidLabel("abc-")).isFalse();
        // A dot would move the value into a different zone — not one label.
        assertThat(DnsLabels.isValidLabel("a.b")).isFalse();
        assertThat(DnsLabels.isValidLabel(null)).isFalse();
    }

    @Test
    void normalizationIsStableForAlreadyValidLabels() {
        String label = "production-pipeline-42";
        assertThat(DnsLabels.normalize(label)).isEqualTo(label);
    }
}
