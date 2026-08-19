package com.noveltea.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.Map;

/** One error shape for the whole API, so clients need one branch, not many. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String error,
        String message,
        String path,
        OffsetDateTime timestamp,
        Map<String, Object> details) {

    public static ApiError of(String error, String message, String path) {
        return new ApiError(error, message, path, OffsetDateTime.now(), null);
    }

    public static ApiError of(String error, String message, String path, Map<String, Object> details) {
        return new ApiError(error, message, path, OffsetDateTime.now(), details);
    }
}
