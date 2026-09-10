package com.imin.iminapi.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.status())
                .body(ApiError.of(ex.code(), ex.getMessage(), ex.fields()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                fields.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors().forEach(ge -> {
            String name = ge.getCode() == null ? ge.getObjectName()
                    : Character.toLowerCase(ge.getCode().charAt(0)) + ge.getCode().substring(1);
            fields.putIfAbsent(name, ge.getDefaultMessage());
        });
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.FIELD_INVALID, "Validation failed", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.INVALID_REQUEST, "Malformed request body"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex) {
        // A DB constraint violation is a client-data problem (or a duplicate), never a server
        // fault — but without this handler it fell through to handleAny → 500. Map it to a clean
        // 409 for a unique-key clash and 400 for every other constraint (not-null, check, FK…).
        String sqlState = sqlStateOf(ex);
        if ("23505".equals(sqlState)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiError.of(ErrorCode.DUPLICATE, "This conflicts with an existing record"));
        }
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.FIELD_INVALID, "Request violates a data constraint"));
    }

    /** Walk the cause chain for the underlying SQLState (e.g. 23505 = unique_violation). */
    private static String sqlStateOf(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException se) return se.getSQLState();
        }
        return null;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.INVALID_REQUEST,
                        "Invalid value for parameter '" + ex.getName() + "'"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(ErrorCode.FORBIDDEN, "Access denied"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> handleMaxUpload(MaxUploadSizeExceededException ex) {
        // Without this handler an oversize multipart body falls through to handleAny → 500 INTERNAL.
        // Map it to 413 with the standard envelope so the FE can show a clean "file too large" error.
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiError.of(ErrorCode.FIELD_INVALID, "Uploaded file is too large",
                        java.util.Map.of("file", "exceeds the maximum allowed size")));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    ResponseEntity<ApiError> handleNoHandler(NoHandlerFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(ErrorCode.NOT_FOUND, "Route not found"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(ErrorCode.NOT_FOUND, "Not found"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex) {
        ErrorCode code = switch (ex.getStatusCode().value()) {
            case 401 -> ErrorCode.AUTH_MISSING;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 409 -> ErrorCode.INVALID_STATE;
            default  -> ErrorCode.INTERNAL;
        };
        return ResponseEntity.status(ex.getStatusCode())
                .body(ApiError.of(code, ex.getReason() != null ? ex.getReason() : ex.getMessage()));
    }

    // ---- Framework MVC exceptions the Throwable catch-all below used to swallow ----
    //
    // This advice is @Order(HIGHEST_PRECEDENCE) and handleAny matches Throwable, so
    // ExceptionHandlerExceptionResolver picks it before Spring's own
    // DefaultHandlerExceptionResolver is ever consulted — the framework's 405/415/400
    // mappings never ran. None of these four extends ResponseStatusException, so
    // handleResponseStatus did not catch them either, and each one answered 500 INTERNAL
    // plus a log.error("Unhandled exception") for what is an ordinary client mistake.
    // Declaring them here (same advice, more specific type) is what wins: within one
    // @ControllerAdvice the closest exception match beats Throwable, so no ordering
    // change is needed and handleAny stays the genuine last resort. The same reasoning
    // already forced the DataIntegrityViolationException, MaxUploadSizeExceededException,
    // NoHandlerFoundException and NoResourceFoundException handlers above — this closes
    // the rest of the set rather than adding one more special case later.
    //
    // Codes stay inside the existing ErrorCode vocabulary both frontends already parse:
    // INVALID_REQUEST for a malformed request line, FIELD_INVALID plus a `fields` entry
    // where there is an actual field to name.

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        ResponseEntity.BodyBuilder res = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        // Allow is required on a 405 (RFC 9110 §15.5.6) and Spring's own mapping sets it.
        if (ex.getSupportedHttpMethods() != null && !ex.getSupportedHttpMethods().isEmpty()) {
            res.allow(ex.getSupportedHttpMethods().toArray(new org.springframework.http.HttpMethod[0]));
        }
        return res.body(ApiError.of(ErrorCode.INVALID_REQUEST,
                "Method " + ex.getMethod() + " is not supported for this endpoint"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        ResponseEntity.BodyBuilder res = ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        if (!ex.getSupportedMediaTypes().isEmpty()) {
            res.header(org.springframework.http.HttpHeaders.ACCEPT,
                    org.springframework.http.MediaType.toString(ex.getSupportedMediaTypes()));
        }
        return res.body(ApiError.of(ErrorCode.INVALID_REQUEST, "Unsupported content type"));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.FIELD_INVALID, "Required part is missing",
                        Map.of(ex.getRequestPartName(), "is required")));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.FIELD_INVALID, "Required parameter is missing",
                        Map.of(ex.getParameterName(), "is required")));
    }

    @ExceptionHandler(Throwable.class)
    ResponseEntity<ApiError> handleAny(Throwable ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(ErrorCode.INTERNAL, "Internal server error"));
    }
}
