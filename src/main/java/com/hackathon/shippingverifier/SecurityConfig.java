package com.hackathon.shippingverifier;

import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

@Configuration
public class SecurityConfig {
    @Bean @Profile("!cloud")
    SecurityFilterChain local(HttpSecurity http)throws Exception {
        // Local mode binds to loopback; cloud mode requires authentication and CSRF.
        return http.authorizeHttpRequests(a->a.anyRequest().permitAll())
            .csrf(c->c.disable()).build();
    }
    @Bean @Profile("cloud")
    SecurityFilterChain cloud(HttpSecurity http)throws Exception {
        return http.authorizeHttpRequests(a->a.requestMatchers(org.springframework.http.HttpMethod.GET,
                "/health", "/", "/index.html", "/star-platinum.css",
                "/demo.html", "/demo.js", "/api/demo").permitAll().anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults()).csrf(Customizer.withDefaults()).build();
    }
    @Bean
    UserDetailsService users(Environment env) {
        boolean cloud=env.matchesProfiles("cloud");
        String user=env.getProperty("APP_USERNAME","");String password=env.getProperty("APP_PASSWORD","");
        if(cloud && (user.isBlank() || password.length()<16))
            throw new IllegalStateException("Cloud mode requires APP_USERNAME and an APP_PASSWORD of at least 16 characters.");
        if(!cloud)return new InMemoryUserDetailsManager();
        var encoder=PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(User.withUsername(user).password(encoder.encode(password)).roles("REVIEWER").build());
    }
}
