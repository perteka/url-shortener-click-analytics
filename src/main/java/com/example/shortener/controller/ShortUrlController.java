package com.example.shortener.controller;

import com.example.shortener.dto.CreateShortUrlRequest;
import com.example.shortener.dto.PageResponse;
import com.example.shortener.dto.PatchShortUrlRequest;
import com.example.shortener.dto.ShortUrlResponse;
import com.example.shortener.dto.UpdateShortUrlRequest;
import com.example.shortener.model.ApiKey;
import com.example.shortener.service.AnalyticsService;
import com.example.shortener.service.ClientIpResolver;
import com.example.shortener.service.DeviceClassifier;
import com.example.shortener.service.RateLimitService;
import com.example.shortener.service.RedirectService;
import com.example.shortener.service.ShortUrlService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class ShortUrlController {
    private final ShortUrlService service;
    private final RedirectService redirectService;
    private final AnalyticsService analyticsService;
    private final DeviceClassifier deviceClassifier;
    private final RateLimitService rateLimiter;
    private final ClientIpResolver clientIpResolver;

    public ShortUrlController(ShortUrlService service,
                               RedirectService redirectService,
                               AnalyticsService analyticsService,
                               DeviceClassifier deviceClassifier,
                               RateLimitService rateLimiter,
                               ClientIpResolver clientIpResolver) {
        this.service = service;
        this.redirectService = redirectService;
        this.analyticsService = analyticsService;
        this.deviceClassifier = deviceClassifier;
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/api/urls")
    public ResponseEntity<ShortUrlResponse> create(@Valid @RequestBody CreateShortUrlRequest request, HttpServletRequest http) {
        ApiKey owner = ApiKeyInterceptor.apiKey(http);
        ShortUrlResponse response = service.create(request, owner);
        return ResponseEntity.created(URI.create(response.shortUrl())).body(response);
    }

    @GetMapping("/api/urls")
    public PageResponse<ShortUrlResponse> list(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size,
                                               HttpServletRequest http) {
        return service.list(ApiKeyInterceptor.apiKey(http), page, size);
    }

    @GetMapping("/api/urls/{code}")
    public ShortUrlResponse get(@PathVariable String code, HttpServletRequest http) {
        return service.get(code, ApiKeyInterceptor.apiKey(http));
    }

    @PutMapping("/api/urls/{code}")
    public ShortUrlResponse update(@PathVariable String code,
                                   @Valid @RequestBody UpdateShortUrlRequest request,
                                   HttpServletRequest http) {
        return service.update(code, request, ApiKeyInterceptor.apiKey(http));
    }

    @PatchMapping("/api/urls/{code}")
    public ShortUrlResponse patch(@PathVariable String code,
                                  @Valid @RequestBody PatchShortUrlRequest request,
                                  HttpServletRequest http) {
        return service.patch(code, request, ApiKeyInterceptor.apiKey(http));
    }

    @DeleteMapping("/api/urls/{code}")
    public ResponseEntity<Void> delete(@PathVariable String code, HttpServletRequest http) {
        service.delete(code, ApiKeyInterceptor.apiKey(http));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{code:[A-Za-z0-9_-]{4,16}}")
    public ResponseEntity<Void> redirect(@PathVariable String code,
                                         @RequestHeader(value = "Referer", required = false) String referrer,
                                         @RequestHeader(value = "User-Agent", required = false) String userAgent,
                                         HttpServletRequest http) {
        if (!rateLimiter.allowRedirect(clientIpResolver.resolve(http))) {
            throw new RateLimitExceededException(rateLimiter.redirectRetryAfterSeconds());
        }
        String url = redirectService.redirect(code, referrer, deviceClassifier.classify(userAgent));
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, url)
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
