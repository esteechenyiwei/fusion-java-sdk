package io.github.jpmorganchase.fusion.api.exception;

import io.github.jpmorganchase.fusion.FusionException;

/**
 * A custom exception to provide useful information on the response of an API call
 */
public class APICallException extends FusionException {

    private static final String UNKNOWN = "Unknown";
    private final int responseCode;
    private final String responseDetail;

    public APICallException(int responseCode, String responseDetail) {
        this.responseCode = responseCode;
        this.responseDetail = responseDetail;
    }

    /**
     * Returns the HTTP response code
     * @return a response code
     */
    public int getResponseCode() {
        return this.responseCode;
    }

    /**
     * Get a meaningful response message
     * @return a description for the exception
     */
    public String getMessage() {

        return switch (this.responseCode) {
            case 400 -> getBadRequestMessage();
            case 401 -> "The bearer token is missing or an invalid bearer token was provided";
            case 403 -> "Not permitted. Check credentials are correct or you are subscribed to the dataset";
            case 404 -> "The requested resource does not exist.";
            case 415 -> "Unsupported media type. Confirm the correct method is being invoked for the operation.";
            case 500 -> "Internal API error. There was an error processing the request.";
            case 504 -> "Request timed out. Please try again.";
            default -> UNKNOWN;
        };
    }

    private String getBadRequestMessage() {
        if (UNKNOWN.equalsIgnoreCase(responseDetail)) {
            return "Bad Request. Please verify the correct data has been provided.";
        }
        return responseDetail;
    }
}
