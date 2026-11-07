package com.example.shortener.service;

import com.example.shortener.config.AppProperties;
import com.example.shortener.dto.ApiKeyResponse;
import com.example.shortener.dto.CreatedApiKeyResponse;
import com.example.shortener.model.ApiKey;
import com.example.shortener.repository.ApiKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

@Service
public class ApiKeyService implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository repository;
    private final ApiKeyHasher hasher;
    private final AppProperties properties;
    private final Environment environment;

    public ApiKeyService(ApiKeyRepository repository, ApiKeyHasher hasher, AppProperties properties, Environment environment) {
        this.repository = repository;
        this.hasher = hasher;
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void run(String... args) {
        validateProductionSecrets();
        String hash = hasher.hash(properties.apiKey());
        repository.findByKeyHash(hash).ifPresentOrElse(key -> {
            if (!key.isActive()) {
                log.warn("Bootstrap API key (id={}) is inactive; set API_KEY to a new value or restore it manually",
                        key.getId());
                return;
            }
            if (!key.isAdmin()) {
                key.promote();
                repository.save(key);
            }
            log.info("Bootstrap API key is ready (id={})", key.getId());
        }, () -> {
            try {
                ApiKey key = repository.save(new ApiKey(hash, "bootstrap", true));
                log.info("Created bootstrap API key (id={})", key.getId());
            } catch (DataIntegrityViolationException exception) {
                log.debug("Bootstrap API key was created by another instance", exception);
            }
        });
    }

    public ApiKey requireActive(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new UnauthorizedException("Invalid API key");
        }
        return repository.findByKeyHashAndActiveTrue(hasher.hash(rawKey))
                .orElseThrow(() -> new UnauthorizedException("Invalid API key"));
    }

    @Transactional
    public CreatedApiKeyResponse createKey(String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("API key name is required");
        }
        String normalizedName = name.trim();
        if (normalizedName.length() > 100) {
            throw new BadRequestException("API key name must be at most 100 characters");
        }
        String rawKey = generateRawKey();
        ApiKey saved = repository.save(new ApiKey(hasher.hash(rawKey), normalizedName, false));
        log.info("Created API key id={} name={}", saved.getId(), saved.getName());
        return new CreatedApiKeyResponse(saved.getId(), saved.getName(), rawKey);
    }

    @Transactional(readOnly = true)
    public List<ApiKeyResponse> listKeys() {
        return repository.findAllByOrderByIdAsc().stream()
                .map(key -> new ApiKeyResponse(key.getId(), key.getName(), key.isActive(), key.isAdmin(), key.getCreatedAt()))
                .toList();
    }

    @Transactional
    public void revokeKey(Long id, Long currentKeyId) {
        ApiKey key = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("API key not found"));
        if (!key.isActive()) {
            return;
        }
        if (key.isAdmin() && repository.lockActiveAdminKeys().size() <= 1) {
            throw new ConflictException("The last active admin key cannot be revoked");
        }
        key.deactivate();
        repository.save(key);
        log.info("API key {} revoked by key {}", id, currentKeyId);
    }

    private void validateProductionSecrets() {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        String apiKey = properties.apiKey();
        String pepper = properties.apiKeyPepper();
        if (apiKey == null || apiKey.length() < 32 || apiKey.contains("local-development")) {
            throw new IllegalStateException("API_KEY must be a strong random value of at least 32 characters in prod");
        }
        if (pepper == null || pepper.length() < 32 || pepper.contains("local-development")) {
            throw new IllegalStateException("API_KEY_PEPPER must be a strong random value of at least 32 characters in prod");
        }
    }

    private String generateRawKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
