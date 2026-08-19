package com.noveltea.web;

import com.noveltea.auth.AuthExceptions.AccessDenied;
import com.noveltea.auth.AuthExceptions.EmailAlreadyRegistered;
import com.noveltea.auth.AuthExceptions.InvalidCredentials;
import com.noveltea.binder.BinderExceptions.BinderCycle;
import com.noveltea.binder.BinderExceptions.BinderItemNotFound;
import com.noveltea.binder.BinderExceptions.CrossProjectMove;
import com.noveltea.merge.MergeExceptions.NotAConflictCopy;
import com.noveltea.merge.MergeExceptions.StaleOriginal;
import com.noveltea.project.ProjectExceptions.ProjectNotDeleted;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps every exception the API can raise to a status and a stable error code.
 *
 * <p>Two rules. **Nothing internal leaks**: unexpected exceptions are logged with their
 * stack trace and answered with a generic message, because exception text routinely
 * contains SQL, table names and parameter values. And **absence beats forbidden**: a
 * resource the caller may not see is reported as missing, since a 403 confirms it exists.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ------------------------------------------------------------------ auth

    @ExceptionHandler(InvalidCredentials.class)
    public ResponseEntity<ApiError> invalidCredentials(InvalidCredentials e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of("invalid_credentials", e.getMessage(), request.getRequestURI()));
    }

    /**
     * Deliberately 404, not 403. See the class comment: a 403 tells an unauthorised caller
     * that the resource exists.
     */
    @ExceptionHandler(AccessDenied.class)
    public ResponseEntity<ApiError> accessDenied(AccessDenied e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("not_found", "not found", request.getRequestURI()));
    }

    @ExceptionHandler(EmailAlreadyRegistered.class)
    public ResponseEntity<ApiError> emailTaken(EmailAlreadyRegistered e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("email_registered", "that email is already registered",
                        request.getRequestURI()));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiError> springAccessDenied(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("forbidden", "forbidden", request.getRequestURI()));
    }

    // --------------------------------------------------------------- domain

    @ExceptionHandler({BinderItemNotFound.class, NotAConflictCopy.class})
    public ResponseEntity<ApiError> notFound(RuntimeException e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("not_found", e.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler({BinderCycle.class, CrossProjectMove.class})
    public ResponseEntity<ApiError> invalidMove(RuntimeException e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("invalid_move", e.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(ProjectNotDeleted.class)
    public ResponseEntity<ApiError> notDeleted(ProjectNotDeleted e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("project_not_deleted", e.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(StaleOriginal.class)
    public ResponseEntity<ApiError> stale(StaleOriginal e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("stale_original", e.getMessage(), request.getRequestURI(),
                        Map.of("currentVersion", e.currentVersion())));
    }

    // ---------------------------------------------------------- bad requests

    @ExceptionHandler({
        IllegalArgumentException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingRequestHeaderException.class,
        MethodArgumentNotValidException.class
    })
    public ResponseEntity<ApiError> badRequest(Exception e, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("bad_request", safeMessage(e), request.getRequestURI()));
    }

    /**
     * A NullPointerException reaching here is a bug in this codebase, never the caller's
     * fault. It is logged loudly and reported as a server error rather than a 400, so it
     * cannot be mistaken for input validation.
     */
    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<ApiError> nullPointer(NullPointerException e, HttpServletRequest request) {
        log.error("NullPointerException handling {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("internal_error", "internal error", request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled {} on {} {}", e.getClass().getName(), request.getMethod(),
                request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("internal_error", "internal error", request.getRequestURI()));
    }

    /** Only messages we authored are echoed; framework text can carry internals. */
    private static String safeMessage(Exception e) {
        if (e instanceof IllegalArgumentException && e.getMessage() != null) {
            return e.getMessage();
        }
        return "malformed request";
    }
}
