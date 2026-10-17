package com.bank.risk.infrastructure.outbox;

import org.slf4j.MDC;

import java.util.regex.Pattern;

/**
 * W3C trace context of the current request, as the tracing bridge (Micrometer
 * Tracing / OpenTelemetry) publishes it in the logging MDC (traceId, spanId).
 * The outbox stores it with the event so the relay can send a traceparent
 * header and the trace crosses the broker. Absent or malformed ids give no
 * traceparent rather than an invented one.
 */
final class TraceContext {

    static final String TRACE_ID_KEY = "traceId";
    static final String SPAN_ID_KEY = "spanId";
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");
    private static final String SAMPLED = "01";

    private TraceContext() {
    }

    /** @return {@code 00-<trace-id>-<parent-id>-01}, or null when no valid trace is active */
    static String currentTraceparent() {
        return traceparent(MDC.get(TRACE_ID_KEY), MDC.get(SPAN_ID_KEY));
    }

    static String traceparent(String traceId, String spanId) {
        if (traceId == null || spanId == null) {
            return null;
        }
        String trace = traceId.toLowerCase();
        String span = spanId.toLowerCase();
        if (!TRACE_ID.matcher(trace).matches() || !SPAN_ID.matcher(span).matches()
                || trace.chars().allMatch(c -> c == '0') || span.chars().allMatch(c -> c == '0')) {
            return null;
        }
        return "00-" + trace + "-" + span + "-" + SAMPLED;
    }
}
