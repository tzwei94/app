package dev.banking.common.api;

import java.util.Map;
import dev.banking.account.domain.BankingFailure;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.*;
import org.springframework.web.method.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
class Errors {
    @ExceptionHandler(BankingFailure.class) ResponseEntity<?> failure(BankingFailure e) {return ResponseEntity.status(e.status()).body(Map.of("code",e.code()));}
    @ExceptionHandler({MethodArgumentNotValidException.class,HandlerMethodValidationException.class,MissingRequestHeaderException.class,MethodArgumentTypeMismatchException.class,HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception e) {return ResponseEntity.badRequest().body(Map.of("code","invalid_request"));}
    @ExceptionHandler(Exception.class) ResponseEntity<?> unavailable(Exception e) {
        // Do not serialize exceptions: JDBC messages can contain financial values.
        org.slf4j.LoggerFactory.getLogger(Errors.class).error("banking request failed: {}",e.getClass().getSimpleName());
        return ResponseEntity.status(500).body(Map.of("code","internal_error"));
    }
}
