package io.github.alirezaamini.balance.api;

import io.github.alirezaamini.balance.domain.exception.AccountAlreadyExistsException;
import io.github.alirezaamini.balance.domain.exception.AccountNotFoundException;
import io.github.alirezaamini.balance.domain.exception.BalanceOverflowException;
import io.github.alirezaamini.balance.domain.exception.IdempotencyConflictException;
import io.github.alirezaamini.balance.domain.exception.InsufficientFundsException;
import io.github.alirezaamini.balance.domain.exception.TransactionNotFoundException;
import io.github.alirezaamini.balance.domain.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps failures to RFC 9457 problem details. Bean validation and malformed JSON
 * are handled by the
 * {@link ResponseEntityExceptionHandler} base class.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ValidationException.class)
    ProblemDetail handleValidation(ValidationException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e);
    }

    @ExceptionHandler({ AccountNotFoundException.class, TransactionNotFoundException.class })
    ProblemDetail handleNotFound(RuntimeException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e);
    }

    @ExceptionHandler(AccountAlreadyExistsException.class)
    ProblemDetail handleAccountExists(AccountAlreadyExistsException e) {
        return problem(HttpStatus.CONFLICT, "Account already exists", e);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail handleIdempotencyConflict(IdempotencyConflictException e) {
        return problem(HttpStatus.CONFLICT, "Transaction id reused", e);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail handleInsufficientFunds(InsufficientFundsException e) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient funds", e);
        problem.setProperty("transactionId", e.transactionId());
        return problem;
    }

    @ExceptionHandler(BalanceOverflowException.class)
    ProblemDetail handleOverflow(BalanceOverflowException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Balance limit exceeded", e);
    }

    @ExceptionHandler({ PessimisticLockingFailureException.class, TransientDataAccessException.class })
    ResponseEntity<ProblemDetail> handleTransient(RuntimeException e) {
        log.warn("Transient database failure, request can be retried", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Temporary failure, nothing was applied. Retry with the same transactionId.");
        problem.setTitle("Temporarily unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem);
    }

    private static ProblemDetail problem(HttpStatus status, String title, RuntimeException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle(title);
        return problem;
    }
}
