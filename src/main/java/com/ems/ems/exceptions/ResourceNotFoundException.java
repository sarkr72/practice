package com.ems.ems.exceptions;

/**
 * Thrown by the service layer when a domain entity cannot be located by its
 * identifier. The {@code GlobalExceptionHandler} translates this to an HTTP
 * 404 response.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException forResource(String resourceName, Object id) {
        return new ResourceNotFoundException(resourceName + " not found with id: " + id);
    }
}
