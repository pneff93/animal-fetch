package com.example.animalfetch;

/**
 * An expected error that maps directly to an HTTP status; {@link Router} turns it into a JSON error response
 * and shows the message to the client.
 * <ul>
 *   <li>400: invalid input (unknown animal in {@link Animal#parse}, bad {@code count} in {@link Router})</li>
 *   <li>404: no picture stored yet, or unknown route ({@link Router})</li>
 *   <li>502: an upstream picture API failed ({@link PictureService})</li>
 * </ul>
 * Any other exception is treated as unexpected and becomes a generic 500.
 */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
