package com.elioo.healthcare.core.filters;

import lombok.Getter;

@Getter
public enum HeaderNames {
    REQUEST_RECEIVED_TIME_IN_MS("Request-Received-Time-In-Ms"),
    REQUEST_SENT_TIME_IN_MS("Request-Sent-Time-In-Ms"),
    RESPONSE_RECEIVED_TIME_IN_MS("Response-Received-Time-In-Ms"),
    RESPONSE_TRANSMISSION_TIME_IN_MS("Response-Transmission-Time-In-Ms"),
    RESPONSE_PROCESSING_TIME_IN_MS("Response-Processing-Time-In-Ms"),
    RESPONSE_SENT_TIME_IN_MS("Response-Sent-Time-In-Ms"),
    TRACE_ID("Trace-Id"),
    SPAN_ID("Span-Id"),
    ;

    private final String value;

    HeaderNames(String value) {
        this.value = value;
    }
}
