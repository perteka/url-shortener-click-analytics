package com.example.shortener;

import com.example.shortener.dto.AnalyticsResponse;
import com.example.shortener.dto.ApiError;
import com.example.shortener.dto.ApiKeyResponse;
import com.example.shortener.dto.CreatedApiKeyResponse;
import com.example.shortener.dto.PageResponse;
import com.example.shortener.dto.ShortUrlResponse;
import com.example.shortener.service.CleanupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class UrlShortenerIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shortener")
            .withUsername("shortener")
            .withPassword("shortener");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CleanupService cleanupService;

    @Value("${app.api-key}")
    private String bootstrapKey;

    @Value("${local.server.port}")
    private int port;

    @Test
    void createsRedirectsAndReportsAnalytics() {
        String code = "itest01";
        ResponseEntity<ShortUrlResponse> created = createUrl(code, "https://example.com/target");
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertNotNull(created.getBody());
        assertEquals(code, created.getBody().shortCode());
        assertTrue(created.getBody().active());

        ResponseEntity<ShortUrlResponse> fetched = rest.exchange(
                "/api/urls/" + code, HttpMethod.GET, new HttpEntity<>(headers(bootstrapKey)), ShortUrlResponse.class);
        assertEquals(HttpStatus.OK, fetched.getStatusCode());
        assertEquals("https://example.com/target", fetched.getBody().originalUrl());

        HttpResponse<Void> redirect = redirect(code);
        assertEquals(302, redirect.statusCode());
        assertEquals("https://example.com/target", redirect.headers().firstValue("Location").orElseThrow());
        assertEquals("no-referrer", redirect.headers().firstValue("Referrer-Policy").orElseThrow());
        assertEquals("no-store", redirect.headers().firstValue("Cache-Control").orElseThrow());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ResponseEntity<AnalyticsResponse> analytics = analytics(code);
            assertEquals(HttpStatus.OK, analytics.getStatusCode());
            AnalyticsResponse body = analytics.getBody();
            assertNotNull(body);
            assertEquals(1, body.totalClicks());
            assertEquals(1L, body.byReferrer().get("news.example.org"));
            assertEquals(1L, body.byDevice().get("MOBILE"));
            String today = LocalDate.now(ZoneOffset.UTC).toString();
            assertEquals(1L, body.byDay().get(today));
        });

        ResponseEntity<ShortUrlResponse> patched = rest.exchange(
                "/api/urls/" + code, HttpMethod.PATCH,
                new HttpEntity<>("{\"active\":false}", jsonHeaders(bootstrapKey)), ShortUrlResponse.class);
        assertEquals(HttpStatus.OK, patched.getStatusCode());
        assertFalse(patched.getBody().active());
        assertEquals(404, redirect(code).statusCode());

        ResponseEntity<Void> deleted = rest.exchange(
                "/api/urls/" + code, HttpMethod.DELETE,
                new HttpEntity<>(headers(bootstrapKey)), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleted.getStatusCode());
    }

    @Test
    void managesApiKeysAndEnforcesAdminAccess() {
        ResponseEntity<CreatedApiKeyResponse> created = rest.exchange(
                "/api/keys", HttpMethod.POST,
                new HttpEntity<>("{\"name\":\"integration\"}", jsonHeaders(bootstrapKey)),
                CreatedApiKeyResponse.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertNotNull(created.getBody());
        assertNotNull(created.getBody().apiKey());
        assertEquals(43, created.getBody().apiKey().length());

        String rawKey = created.getBody().apiKey();
        Long keyId = created.getBody().id();

        ResponseEntity<ApiKeyResponse[]> list = rest.exchange(
                "/api/keys", HttpMethod.GET, new HttpEntity<>(headers(bootstrapKey)), ApiKeyResponse[].class);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        assertTrue(List.of(list.getBody()).stream().anyMatch(key -> key.id().equals(keyId)));

        ResponseEntity<String> forbidden = rest.exchange(
                "/api/keys", HttpMethod.GET, new HttpEntity<>(headers(rawKey)), String.class);
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());

        ResponseEntity<ShortUrlResponse> createdWithNewKey = rest.exchange(
                "/api/urls", HttpMethod.POST,
                new HttpEntity<>("{\"url\":\"https://example.com/key-test\",\"customCode\":\"keytest1\"}",
                        jsonHeaders(rawKey)),
                ShortUrlResponse.class);
        assertEquals(HttpStatus.CREATED, createdWithNewKey.getStatusCode());

        ResponseEntity<Void> revoked = rest.exchange(
                "/api/keys/" + keyId, HttpMethod.DELETE,
                new HttpEntity<>(headers(bootstrapKey)), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, revoked.getStatusCode());

        ResponseEntity<String> rejected = rest.exchange(
                "/api/urls", HttpMethod.GET, new HttpEntity<>(headers(rawKey)), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, rejected.getStatusCode());
    }

    @Test
    void protectsTheLastAdminKey() {
        ResponseEntity<ApiKeyResponse[]> list = rest.exchange(
                "/api/keys", HttpMethod.GET, new HttpEntity<>(headers(bootstrapKey)), ApiKeyResponse[].class);
        Long bootstrapId = List.of(list.getBody()).stream()
                .filter(ApiKeyResponse::admin)
                .findFirst()
                .orElseThrow()
                .id();

        ResponseEntity<ApiError> response = rest.exchange(
                "/api/keys/" + bootstrapId, HttpMethod.DELETE,
                new HttpEntity<>(headers(bootstrapKey)), ApiError.class);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().message().contains("last active admin"));
    }

    @Test
    void returnsStructuredErrorsAndCorrelationIds() {
        ResponseEntity<ApiError> unauthorized = rest.exchange(
                "/api/urls", HttpMethod.GET, HttpEntity.EMPTY, ApiError.class);
        assertEquals(HttpStatus.UNAUTHORIZED, unauthorized.getStatusCode());
        assertNotNull(unauthorized.getBody());
        assertNotNull(unauthorized.getHeaders().getFirst("X-Request-Id"));
        assertNotNull(unauthorized.getBody().requestId());

        ResponseEntity<ApiError> invalid = rest.exchange(
                "/api/urls", HttpMethod.POST,
                new HttpEntity<>("{\"url\":\"not-a-url\"}", jsonHeaders(bootstrapKey)), ApiError.class);
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
        assertNotNull(invalid.getBody());
        assertNotNull(invalid.getBody().fieldErrors());
        assertTrue(invalid.getBody().fieldErrors().containsKey("url"));

        ResponseEntity<ApiError> badPage = rest.exchange(
                "/api/urls?page=-1", HttpMethod.GET, new HttpEntity<>(headers(bootstrapKey)), ApiError.class);
        assertEquals(HttpStatus.BAD_REQUEST, badPage.getStatusCode());

        ResponseEntity<ApiError> missing = rest.exchange(
                "/api/urls/missing1", HttpMethod.GET, new HttpEntity<>(headers(bootstrapKey)), ApiError.class);
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
    }

    @Test
    void exposesReadinessAndMetricsWithoutRedis() {
        ResponseEntity<String> readiness = rest.getForEntity("/actuator/health/readiness", String.class);
        assertEquals(HttpStatus.OK, readiness.getStatusCode());
        assertTrue(readiness.getBody().contains("UP"));

        ResponseEntity<String> metrics = rest.getForEntity("/actuator/prometheus", String.class);
        assertEquals(HttpStatus.OK, metrics.getStatusCode());
        assertTrue(metrics.getBody().contains("jvm_"));
    }

    @Test
    void cleanupSoftDeletesThenHardDeletesExpiredUrls() {
        String code = "cleanup1";
        ResponseEntity<ShortUrlResponse> created = createUrl(code, "https://example.com/cleanup");
        assertEquals(HttpStatus.CREATED, created.getStatusCode());

        jdbcTemplate.update("update short_urls set expires_at = now() - interval '1 day' where short_code = ?", code);
        cleanupService.cleanup();

        Boolean active = jdbcTemplate.queryForObject(
                "select active from short_urls where short_code = ?", Boolean.class, code);
        assertEquals(Boolean.FALSE, active);
        Boolean deactivated = jdbcTemplate.queryForObject(
                "select deactivated_at is not null from short_urls where short_code = ?", Boolean.class, code);
        assertEquals(Boolean.TRUE, deactivated);

        jdbcTemplate.update(
                "update short_urls set deactivated_at = now() - interval '31 days' where short_code = ?", code);
        cleanupService.cleanup();

        Integer remaining = jdbcTemplate.queryForObject(
                "select count(*) from short_urls where short_code = ?", Integer.class, code);
        assertEquals(0, remaining);
    }

    private ResponseEntity<ShortUrlResponse> createUrl(String code, String url) {
        return rest.exchange(
                "/api/urls", HttpMethod.POST,
                new HttpEntity<>("{\"url\":\"" + url + "\",\"customCode\":\"" + code + "\"}",
                        jsonHeaders(bootstrapKey)),
                ShortUrlResponse.class);
    }

    private ResponseEntity<AnalyticsResponse> analytics(String code) {
        return rest.exchange(
                "/api/urls/" + code + "/analytics?days=7", HttpMethod.GET,
                new HttpEntity<>(headers(bootstrapKey)), AnalyticsResponse.class);
    }

    private HttpResponse<Void> redirect(String code) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/" + code))
                    .header("Referer", "https://news.example.org/article")
                    .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Mobile")
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception exception) {
            throw new IllegalStateException("Redirect request failed", exception);
        }
    }

    private HttpHeaders headers(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", apiKey);
        return headers;
    }

    private HttpHeaders jsonHeaders(String apiKey) {
        HttpHeaders headers = headers(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
