package com.bank.risk.infrastructure.outbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN = "00f067aa0ba902b7";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void theTracingBridgesIdsInTheMdcBecomeAW3cTraceparent() {
        MDC.put(TraceContext.TRACE_ID_KEY, TRACE);
        MDC.put(TraceContext.SPAN_ID_KEY, SPAN);

        assertThat(TraceContext.currentTraceparent()).isEqualTo("00-" + TRACE + "-" + SPAN + "-01");
    }

    @Test
    void upperCaseIdsAreNormalised() {
        assertThat(TraceContext.traceparent(TRACE.toUpperCase(), SPAN.toUpperCase()))
            .isEqualTo("00-" + TRACE + "-" + SPAN + "-01");
    }

    @Test
    void noOrInvalidIdsGiveNoTraceparent() {
        assertThat(TraceContext.currentTraceparent()).isNull();
        assertThat(TraceContext.traceparent(TRACE, null)).isNull();
        assertThat(TraceContext.traceparent(null, SPAN)).isNull();
        assertThat(TraceContext.traceparent("abc", SPAN)).as("64-bit trace id").isNull();
        assertThat(TraceContext.traceparent(TRACE, "xyz0000000000000")).isNull();
        assertThat(TraceContext.traceparent("0".repeat(32), SPAN)).as("all-zero trace id is invalid").isNull();
        assertThat(TraceContext.traceparent(TRACE, "0".repeat(16))).as("all-zero span id is invalid").isNull();
    }
}
