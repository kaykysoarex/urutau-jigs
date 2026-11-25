package br.com.urutau.jigs.config;

import jakarta.validation.ConstraintViolationException;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorBody> status(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(new ErrorBody(Objects.requireNonNullElse(exception.getReason(), "Não foi possível concluir a operação.")));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException exception) {
        FieldError first = exception.getBindingResult().getFieldError();
        return ResponseEntity.badRequest().body(new ErrorBody(first == null ? "Confira os campos informados."
                : Objects.requireNonNullElse(first.getDefaultMessage(), "Confira os campos informados.")));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            ConstraintViolationException.class, HandlerMethodValidationException.class})
    ResponseEntity<ErrorBody> invalidInput() {
        return ResponseEntity.badRequest().body(new ErrorBody("Confira o formato e os limites dos campos informados."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorBody> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorBody("Os dados mudaram durante a operação. Atualize a tela e tente novamente."));
    }

    record ErrorBody(String message) {}
}
