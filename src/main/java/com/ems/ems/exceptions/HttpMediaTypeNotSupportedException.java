package com.ems.ems.exceptions;

public class HttpMediaTypeNotSupportedException extends RuntimeException {
    public HttpMediaTypeNotSupportedException(String message) {
        super(message);
    }

    public HttpMediaTypeNotSupportedException(String resourceName, String fieldName, Object fieldValue) {
        super(String.format("%s not found with %s: '%s'", resourceName, fieldName, fieldValue));
    }
}
