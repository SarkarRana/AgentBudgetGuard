package io.agentbudget.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallIdTest {

    @Test
    void carriesTheCallersOwnIdentifier() {
        assertThat(CallId.of("order-42-attempt-1").value()).isEqualTo("order-42-attempt-1");
        assertThat(CallId.of("order-42-attempt-1")).hasToString("order-42-attempt-1");
    }

    @Test
    void equalsByValueSoALedgerCanKeyOnIt() {
        assertThat(CallId.of("same")).isEqualTo(CallId.of("same"));
        assertThat(CallId.of("same")).hasSameHashCodeAs(CallId.of("same"));
        assertThat(CallId.of("same")).isNotEqualTo(CallId.of("other"));
    }

    @Test
    void generatesADistinctIdentifierWhenTheCallerSuppliesNone() {
        assertThat(CallId.random()).isNotEqualTo(CallId.random());
    }

    @Test
    void rejectsAnAbsentOrBlankIdentifier() {
        assertThatThrownBy(() -> CallId.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CallId.of("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }
}
