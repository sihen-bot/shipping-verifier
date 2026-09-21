package com.hackathon.shippingverifier;

import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component @Profile("cloud")
public class StorageAvailabilityFilter extends OncePerRequestFilter {
    private final DurableData data;
    public StorageAvailabilityFilter(DurableData data) { this.data=data; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        if(request.getRequestURI().startsWith("/api/") && !request.getRequestURI().equals("/api/admin/storage")
                && !request.getRequestURI().equals("/api/session/csrf") && !data.ready()) {
            response.setStatus(503);response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Data storage needs recovery. Restart the service to restore saved database records.\"}");return;
        }
        chain.doFilter(request,response);
    }
}
