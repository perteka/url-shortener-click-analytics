package com.example.shortener.controller;

import com.example.shortener.dto.ApiKeyResponse;
import com.example.shortener.dto.CreateApiKeyRequest;
import com.example.shortener.dto.CreatedApiKeyResponse;
import com.example.shortener.service.ApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/keys")
public class ApiKeyController {
    private final ApiKeyService apiKeys;

    public ApiKeyController(ApiKeyService apiKeys) {
        this.apiKeys = apiKeys;
    }

    @GetMapping
    public List<ApiKeyResponse> list(HttpServletRequest request) {
        ApiKeyInterceptor.requireAdmin(request);
        return apiKeys.listKeys();
    }

    @PostMapping
    public ResponseEntity<CreatedApiKeyResponse> create(@Valid @RequestBody CreateApiKeyRequest request,
                                                        HttpServletRequest http) {
        ApiKeyInterceptor.requireAdmin(http);
        return ResponseEntity.status(HttpStatus.CREATED).body(apiKeys.createKey(request.name()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable Long id, HttpServletRequest http) {
        var current = ApiKeyInterceptor.requireAdmin(http);
        apiKeys.revokeKey(id, current.getId());
        return ResponseEntity.noContent().build();
    }
}
