package com.naraesigning.roster;

import com.naraesigning.web.RequestCorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = RosterController.class)
@ConditionalOnProperty("spring.datasource.url")
final class RosterApiAdvice {
    @ExceptionHandler(RosterInputException.class)
    ResponseEntity<RosterErrorResponse> invalid(RosterInputException exception, HttpServletRequest request) {
        RequestCorrelationFilter.errorCode(request, "ROSTER_INVALID");
        return ResponseEntity.badRequest().body(new RosterErrorResponse("ROSTER_INVALID", exception.errors()));
    }

    @ExceptionHandler(RosterUnavailableException.class)
    ResponseEntity<Map<String, String>> unavailable(HttpServletRequest request) {
        RequestCorrelationFilter.errorCode(request, "ROSTER_UNAVAILABLE");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "ROSTER_UNAVAILABLE"));
    }
}

record RosterErrorResponse(String code, List<RosterValidationError> errors) {}
