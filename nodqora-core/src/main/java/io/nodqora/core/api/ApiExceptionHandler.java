// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** ADR-0060: errors are RFC 9457 {@code application/problem+json}, unversioned and unenveloped. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(UnknownEnvironmentException.class)
    public ProblemDetail unknownEnvironment(UnknownEnvironmentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Unknown environment");
        problem.setProperty("environmentKey", e.environmentKey());
        return problem;
    }
}
