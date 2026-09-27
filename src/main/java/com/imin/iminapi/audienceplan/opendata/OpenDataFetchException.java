package com.imin.iminapi.audienceplan.opendata;

/** A source could not give a trustworthy figure; the caller keeps what it had and never invents one. */
public class OpenDataFetchException extends RuntimeException {
    public OpenDataFetchException(String message) { super(message); }
    public OpenDataFetchException(String message, Throwable cause) { super(message, cause); }
}
