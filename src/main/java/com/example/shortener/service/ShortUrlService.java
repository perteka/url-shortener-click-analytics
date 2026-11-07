package com.example.shortener.service;

import com.example.shortener.dto.CreateShortUrlRequest;
import com.example.shortener.dto.PatchShortUrlRequest;
import com.example.shortener.dto.PageResponse;
import com.example.shortener.dto.ShortUrlResponse;
import com.example.shortener.dto.UpdateShortUrlRequest;
import com.example.shortener.model.ApiKey;
import com.example.shortener.model.ShortUrl;
import com.example.shortener.repository.ShortUrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class ShortUrlService {
    private static final Logger log = LoggerFactory.getLogger(ShortUrlService.class);
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final Pattern CUSTOM_CODE = Pattern.compile("[A-Za-z0-9_-]{4,16}");
    private static final Set<String> RESERVED_CODES = Set.of(
            "error", "api", "actuator", "health", "favicon", "swagger-ui", "v3"
    );
    private static final int MAX_GENERATED_CODE_ATTEMPTS = 10;

    private final ShortUrlRepository shortUrls;
    private final CacheService cache;
    private final ShortUrlMapper mapper;
    private final ShortUrlWriter writer;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ShortUrlService(ShortUrlRepository shortUrls,
                           CacheService cache,
                           ShortUrlMapper mapper,
                           ShortUrlWriter writer,
                           Clock clock) {
        this.shortUrls = shortUrls;
        this.cache = cache;
        this.mapper = mapper;
        this.writer = writer;
        this.clock = clock;
    }

    @Transactional
    public ShortUrlResponse create(CreateShortUrlRequest request, ApiKey owner) {
        if (owner == null) {
            throw new UnauthorizedException("Invalid API key");
        }
        String url = validateUrl(request.url());
        Instant expiresAt = validateExpiry(request.expiresAt());
        String customCode = normalizeCustomCode(request.customCode());

        if (customCode != null) {
            try {
                ShortUrl saved = writer.persist(customCode, url, owner.getId(), expiresAt);
                cache.put(saved.getShortCode(), saved.getId(), saved.getOriginalUrl(), saved.getExpiresAt());
                return mapper.toResponse(saved);
            } catch (DataIntegrityViolationException exception) {
                throw new ConflictException("Short code is already in use");
            }
        }

        for (int attempt = 0; attempt < MAX_GENERATED_CODE_ATTEMPTS; attempt++) {
            String code = generateCode();
            try {
                ShortUrl saved = writer.persist(code, url, owner.getId(), expiresAt);
                cache.put(saved.getShortCode(), saved.getId(), saved.getOriginalUrl(), saved.getExpiresAt());
                return mapper.toResponse(saved);
            } catch (DataIntegrityViolationException exception) {
                log.warn("Generated short code {} collided; retrying", code);
            }
        }
        throw new IllegalStateException("Unable to generate a unique short code");
    }

    @Transactional
    public ShortUrlResponse update(String code, UpdateShortUrlRequest request, ApiKey owner) {
        ShortUrl entity = owned(code, owner);
        Instant expiry = resolveExpiry(entity, request.expiresAt(), request.clearExpiry());
        Instant now = clock.instant();
        if (entity.isActive() && expiry != null && !expiry.isAfter(now)) {
            throw new BadRequestException("An active short URL must have a future expiry");
        }
        entity.update(validateUrl(request.url()), expiry);
        shortUrls.save(entity);
        cache.evictAfterCommit(code);
        return mapper.toResponse(entity);
    }

    @Transactional
    public ShortUrlResponse patch(String code, PatchShortUrlRequest request, ApiKey owner) {
        ShortUrl entity = owned(code, owner);
        Instant now = clock.instant();

        if (Boolean.TRUE.equals(request.clearExpiry()) && request.expiresAt() != null) {
            throw new BadRequestException("expiresAt and clearExpiry cannot be used together");
        }

        String url = request.url() == null ? entity.getOriginalUrl() : validateUrl(request.url());
        Instant expiry = entity.getExpiresAt();
        if (Boolean.TRUE.equals(request.clearExpiry())) {
            expiry = null;
        } else if (request.expiresAt() != null) {
            expiry = validateExpiry(request.expiresAt());
        }

        boolean active = request.active() == null ? entity.isActive() : request.active();
        if (active && expiry != null && !expiry.isAfter(now)) {
            throw new BadRequestException("An active short URL must have a future expiry");
        }

        entity.update(url, expiry);
        if (active) {
            entity.activate();
        } else {
            entity.deactivate(now);
        }
        shortUrls.save(entity);
        cache.evictAfterCommit(code);
        return mapper.toResponse(entity);
    }

    @Transactional
    public void delete(String code, ApiKey owner) {
        ShortUrl entity = owned(code, owner);
        if (entity.isActive()) {
            entity.deactivate(clock.instant());
            shortUrls.save(entity);
        }
        cache.evictAfterCommit(code);
    }

    @Transactional(readOnly = true)
    public ShortUrlResponse get(String code, ApiKey owner) {
        return mapper.toResponse(owned(code, owner));
    }

    @Transactional(readOnly = true)
    public PageResponse<ShortUrlResponse> list(ApiKey owner, int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page must be zero or greater");
        }
        if (size < 1 || size > 100) {
            throw new BadRequestException("size must be between 1 and 100");
        }
        Page<ShortUrl> result = shortUrls.findAllByApiKeyId(
                owner.getId(),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        );
        return PageResponse.from(result.map(mapper::toResponse));
    }

    private ShortUrl owned(String code, ApiKey owner) {
        if (owner == null) {
            throw new UnauthorizedException("Invalid API key");
        }
        return shortUrls.findByShortCodeAndApiKeyId(code, owner.getId())
                .orElseThrow(() -> new NotFoundException("Short URL not found"));
    }

    private String generateCode() {
        for (int attempt = 0; attempt < MAX_GENERATED_CODE_ATTEMPTS; attempt++) {
            StringBuilder code = new StringBuilder(7);
            for (int i = 0; i < 7; i++) {
                code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            String candidate = code.toString();
            if (!RESERVED_CODES.contains(candidate) && !shortUrls.existsByShortCode(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to generate a unique short code");
    }

    private String normalizeCustomCode(String customCode) {
        if (customCode == null) {
            return null;
        }
        String value = customCode.trim();
        if (!CUSTOM_CODE.matcher(value).matches()) {
            throw new BadRequestException("Custom code must be 4-16 letters, digits, _ or -");
        }
        if (RESERVED_CODES.contains(value.toLowerCase(Locale.ROOT))) {
            throw new BadRequestException("Custom code is reserved");
        }
        return value;
    }

    private Instant validateExpiry(Instant expiresAt) {
        if (expiresAt != null && !expiresAt.isAfter(clock.instant())) {
            throw new BadRequestException("Expiry must be in the future");
        }
        return expiresAt;
    }

    private Instant resolveExpiry(ShortUrl entity, Instant requestedExpiry, boolean clearExpiry) {
        if (clearExpiry && requestedExpiry != null) {
            throw new BadRequestException("expiresAt and clearExpiry cannot be used together");
        }
        if (clearExpiry) {
            return null;
        }
        return requestedExpiry == null ? entity.getExpiresAt() : validateExpiry(requestedExpiry);
    }

    private String validateUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new BadRequestException("URL is required");
        }
        String url = rawUrl.trim();
        if (url.length() > 2048 || url.chars().anyMatch(Character::isWhitespace)) {
            throw new BadRequestException("URL contains invalid characters or is too long");
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null
                    || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                throw new BadRequestException("URL must be an absolute http or https URL without credentials");
            }
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("URL is not a valid URI");
        }
        return url;
    }
}
