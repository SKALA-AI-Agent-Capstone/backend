package org.skala.capstone;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> status(ResponseStatusException error){
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message",error.getReason()==null?"REQUEST_FAILED":error.getReason()));
    }
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<Map<String,String>> invalid(Exception error){return ResponseEntity.badRequest().body(Map.of("message","INVALID_REQUEST"));}
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    ResponseEntity<Map<String,String>> duplicate(Exception error){return ResponseEntity.status(409).body(Map.of("message","ALREADY_EXISTS"));}
}
