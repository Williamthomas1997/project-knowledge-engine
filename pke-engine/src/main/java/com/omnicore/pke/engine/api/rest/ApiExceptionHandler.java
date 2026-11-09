package com.omnicore.pke.engine.api.rest;

import com.omnicore.pke.engine.kernel.ConflictException;
import com.omnicore.pke.engine.kernel.InvalidIdentifierException;
import com.omnicore.pke.engine.kernel.NotFoundException;
import com.omnicore.pke.engine.kernel.ValidationFailedException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps engine exceptions to RFC 9457 problem details. Validation problems list every violation. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(ValidationFailedException.class)
    ProblemDetail validation(ValidationFailedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setTitle("Invalid request");
        problem.setProperty("violations", e.violations().stream()
                .map(v -> Map.of("path", v.path(), "message", v.message())).toList());
        return problem;
    }

    @ExceptionHandler(InvalidIdentifierException.class)
    ProblemDetail identifier(InvalidIdentifierException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid identifier");
        problem.setProperty("violations", List.of(Map.of("path", e.kind(), "message", e.getMessage())));
        return problem;
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Not found");
        return problem;
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Conflict");
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Request body is not valid JSON for this endpoint");
        problem.setTitle("Unreadable request");
        return problem;
    }
}
