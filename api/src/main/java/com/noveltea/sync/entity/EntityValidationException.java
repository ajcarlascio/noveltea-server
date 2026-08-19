package com.noveltea.sync.entity;

/** A change that would have produced malformed data. Reported, never applied. */
public class EntityValidationException extends RuntimeException {
    public EntityValidationException(String message) {
        super(message);
    }
}
