package ma.safefarm.controller;
import org.springframework.http.*;import org.springframework.web.bind.annotation.*;import java.util.*;
@RestControllerAdvice public class ApiErrorHandler {
 @ExceptionHandler(NoSuchElementException.class) ResponseEntity<?> missing(Exception e){return ResponseEntity.status(404).body(Map.of("error",e.getMessage()));}
 @ExceptionHandler(Exception.class) ResponseEntity<?> error(Exception e){return ResponseEntity.status(502).body(Map.of("error",e.getMessage()==null?"Server error":e.getMessage()));}
}
