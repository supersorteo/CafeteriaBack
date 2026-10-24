package Cafeteria.shared;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import Cafeteria.shared.ApiException.FieldError;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ProblemDetail apiException(ApiException e) {
        ProblemDetail problem = problem(e.getStatus(), e.getCode(), e.getMessage());
        if (!e.getFieldErrors().isEmpty()) {
            problem.setProperty("fieldErrors", e.getFieldErrors());
        }
        return problem;
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail optimisticLock() {
        return problem(HttpStatus.CONFLICT, "version_conflict",
                "El registro fue modificado por otra persona. Recarga e inténtalo de nuevo.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity() {
        return problem(HttpStatus.CONFLICT, "integrity_violation", "La operación viola una restricción de datos.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException e) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new FieldError(f.getField(), f.getDefaultMessage()))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation_error", "Datos inválidos");
        problem.setProperty("fieldErrors", errors);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable() {
        return problem(HttpStatus.BAD_REQUEST, "malformed_request", "Cuerpo de la petición inválido");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ProblemDetail missingPart() {
        return problem(HttpStatus.BAD_REQUEST, "malformed_request", "Falta el archivo en la petición");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge() {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large", "El archivo supera el tamaño máximo permitido");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail typeMismatch(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid_parameter", "Parámetro inválido: " + e.getName());
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(detail);
        problem.setProperty("code", code);
        return problem;
    }
}
