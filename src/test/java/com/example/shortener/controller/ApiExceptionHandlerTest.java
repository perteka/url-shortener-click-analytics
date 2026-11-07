package com.example.shortener.controller;

import com.example.shortener.dto.CreateShortUrlRequest;
import com.example.shortener.service.ConflictException;
import com.example.shortener.service.ForbiddenException;
import com.example.shortener.service.NotFoundException;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

import static com.example.shortener.support.TestFixtures.FIXED_CLOCK;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerTest {
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Clock clock = FIXED_CLOCK;
        mockMvc = MockMvcBuilders.standaloneSetup(new FaultyController())
                .setControllerAdvice(new ApiExceptionHandler(clock))
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void returnsStructuredNotFoundError() throws Exception {
        mockMvc.perform(get("/missing").header(RequestIdFilter.HEADER, "request-1"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(RequestIdFilter.HEADER, "request-1"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Short URL not found"))
                .andExpect(jsonPath("$.path").value("/missing"))
                .andExpect(jsonPath("$.requestId").value("request-1"));
    }

    @Test
    void returnsRetryAfterForRateLimits() throws Exception {
        mockMvc.perform(get("/limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    void returnsFieldErrorsForValidationFailures() throws Exception {
        mockMvc.perform(post("/validate")
                        .contentType("application/json")
                        .content("{\"url\":\"ftp://example.com\",\"customCode\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.url").exists())
                .andExpect(jsonPath("$.fieldErrors.customCode").exists());
    }

    @Test
    void returnsBadRequestForMalformedJson() throws Exception {
        mockMvc.perform(post("/validate")
                        .contentType("application/json")
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed or missing JSON request body"));
    }

    @Test
    void mapsConflictAndForbidden() throws Exception {
        mockMvc.perform(get("/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("already exists"));
        mockMvc.perform(get("/forbidden"))
                .andExpect(status().isForbidden());
    }

    @Test
    void returnsMethodNotAllowedWithAllowHeader() throws Exception {
        mockMvc.perform(post("/missing"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"));
    }

    @Test
    void hidesUnexpectedExceptionDetails() throws Exception {
        mockMvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.message", not(containsString("secret"))));
    }

    @RestController
    static class FaultyController {
        @GetMapping("/missing")
        ResponseEntity<Void> missing() {
            throw new NotFoundException("Short URL not found");
        }

        @GetMapping("/conflict")
        ResponseEntity<Void> conflict() {
            throw new ConflictException("already exists");
        }

        @GetMapping("/forbidden")
        ResponseEntity<Void> forbidden() {
            throw new ForbiddenException("not allowed");
        }

        @GetMapping("/limited")
        ResponseEntity<Void> limited() {
            throw new RateLimitExceededException(42);
        }

        @GetMapping("/boom")
        ResponseEntity<Void> boom() {
            throw new IllegalStateException("secret detail");
        }

        @PostMapping("/validate")
        ResponseEntity<Void> validate(@Valid @RequestBody CreateShortUrlRequest request) {
            return ResponseEntity.ok().build();
        }
    }
}
