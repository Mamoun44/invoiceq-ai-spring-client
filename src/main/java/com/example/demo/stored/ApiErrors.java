package com.example.demo.stored;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String,String>> conflict(DataIntegrityViolationException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message","Record conflicts with an existing record or database constraint."));
    }
}

