package com.hackathon.shippingverifier;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
@RestController
public class SessionController {
    @GetMapping("/api/session/csrf")
    public ResponseEntity<?> csrf(HttpServletRequest request) {
        Object value=request.getAttribute(CsrfToken.class.getName());
        if(value instanceof CsrfToken token)return ResponseEntity.ok().header("Cache-Control","no-store")
            .body(Map.of("enabled",true,"header",token.getHeaderName(),"token",token.getToken()));
        return ResponseEntity.ok().header("Cache-Control","no-store").body(Map.of("enabled",false));
    }
    @GetMapping("/health")
    public Map<String,String> health(){return Map.of("status","UP");}
}
