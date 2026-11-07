package com.example.shortener.service;

import com.example.shortener.dto.CreateShortUrlRequest;
import com.example.shortener.dto.PageResponse;
import com.example.shortener.dto.PatchShortUrlRequest;
import com.example.shortener.dto.ShortUrlResponse;
import com.example.shortener.dto.UpdateShortUrlRequest;
import com.example.shortener.model.ApiKey;
import com.example.shortener.model.ShortUrl;
import com.example.shortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.example.shortener.support.TestFixtures.FIXED_CLOCK;
import static com.example.shortener.support.TestFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ShortUrlServiceTest {
    private final ShortUrlRepository urls = mock(ShortUrlRepository.class);
    private final CacheService cache = mock(CacheService.class);
    private final ShortUrlMapper mapper = mock(ShortUrlMapper.class);
    private final ShortUrlWriter writer = mock(ShortUrlWriter.class);

    private ShortUrlService service() {
        return new ShortUrlService(urls, cache, mapper, writer, FIXED_CLOCK);
    }

    private ApiKey owner() {
        ApiKey owner = mock(ApiKey.class);
        when(owner.getId()).thenReturn(1L);
        return owner;
    }

    @Test
    void createsCustomCode() {
        ApiKey owner = owner();
        ShortUrl saved = mock(ShortUrl.class);
        ShortUrlResponse response = response("spring1", "https://spring.io");
        when(writer.persist("spring1", "https://spring.io", 1L, null)).thenReturn(saved);
        when(saved.getShortCode()).thenReturn("spring1");
        when(saved.getId()).thenReturn(10L);
        when(saved.getOriginalUrl()).thenReturn("https://spring.io");
        when(saved.getExpiresAt()).thenReturn(null);
        when(mapper.toResponse(saved)).thenReturn(response);

        ShortUrlResponse result = service().create(
                new CreateShortUrlRequest("https://spring.io", "spring1", null), owner);

        assertEquals(response, result);
        verify(cache).put("spring1", 10L, "https://spring.io", null);
    }

    @Test
    void createsGeneratedCodeWhenNoCustomCodeIsGiven() {
        ApiKey owner = owner();
        ShortUrl saved = mock(ShortUrl.class);
        when(urls.existsByShortCode(anyString())).thenReturn(false);
        when(writer.persist(anyString(), eq("https://spring.io"), eq(1L), isNull())).thenReturn(saved);
        when(saved.getShortCode()).thenReturn("abc1234");
        when(saved.getId()).thenReturn(11L);
        when(saved.getOriginalUrl()).thenReturn("https://spring.io");
        when(saved.getExpiresAt()).thenReturn(null);
        when(mapper.toResponse(saved)).thenReturn(response("abc1234", "https://spring.io"));

        service().create(new CreateShortUrlRequest("https://spring.io", null, null), owner);

        verify(writer).persist(org.mockito.ArgumentMatchers.argThat(code -> code.length() == 7),
                eq("https://spring.io"), eq(1L), isNull());
    }

    @Test
    void retriesWhenGeneratedCodeCollidesInDatabase() {
        ApiKey owner = owner();
        ShortUrl saved = mock(ShortUrl.class);
        when(urls.existsByShortCode(anyString())).thenReturn(false);
        when(writer.persist(anyString(), eq("https://spring.io"), eq(1L), isNull()))
                .thenThrow(new DataIntegrityViolationException("collision"))
                .thenReturn(saved);
        when(saved.getShortCode()).thenReturn("abc1234");
        when(saved.getId()).thenReturn(12L);
        when(saved.getOriginalUrl()).thenReturn("https://spring.io");
        when(saved.getExpiresAt()).thenReturn(null);
        when(mapper.toResponse(saved)).thenReturn(response("abc1234", "https://spring.io"));

        service().create(new CreateShortUrlRequest("https://spring.io", null, null), owner);

        verify(writer, times(2)).persist(anyString(), eq("https://spring.io"), eq(1L), isNull());
    }

    @Test
    void reportsConflictForDuplicateCustomCode() {
        when(writer.persist(eq("spring1"), anyString(), eq(1L), isNull()))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThrows(ConflictException.class, () -> service().create(
                new CreateShortUrlRequest("https://spring.io", "spring1", null), owner()));
    }

    @Test
    void rejectsInvalidUrls() {
        ApiKey owner = owner();
        assertThrows(BadRequestException.class, () -> service().create(
                new CreateShortUrlRequest("ftp://example.com", null, null), owner));
        assertThrows(BadRequestException.class, () -> service().create(
                new CreateShortUrlRequest("https://user:password@example.com", null, null), owner));
        assertThrows(BadRequestException.class, () -> service().create(
                new CreateShortUrlRequest("https://example.com/a b", null, null), owner));
        verifyNoInteractions(writer);
    }

    @Test
    void rejectsPastExpiryAndReservedCode() {
        ApiKey owner = owner();
        assertThrows(BadRequestException.class, () -> service().create(
                new CreateShortUrlRequest("https://spring.io", null, NOW.minusSeconds(1)), owner));
        assertThrows(BadRequestException.class, () -> service().create(
                new CreateShortUrlRequest("https://spring.io", "health", null), owner));
        assertThrows(BadRequestException.class, () -> service().create(
                new CreateShortUrlRequest("https://spring.io", "bad code!", null), owner));
    }

    @Test
    void rejectsCreateWithoutOwner() {
        assertThrows(UnauthorizedException.class, () -> service().create(
                new CreateShortUrlRequest("https://spring.io", null, null), null));
    }

    @Test
    void failsAfterGeneratedCodeAttemptsAreExhausted() {
        when(urls.existsByShortCode(anyString())).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> service().create(
                new CreateShortUrlRequest("https://spring.io", null, null), owner()));
    }

    @Test
    void updatesUrlAndClearsExpiry() {
        ApiKey owner = owner();
        ShortUrl entity = mock(ShortUrl.class);
        when(entity.isActive()).thenReturn(true);
        when(entity.getExpiresAt()).thenReturn(NOW.plusSeconds(60));
        when(urls.findByShortCodeAndApiKeyId("abc1234", 1L)).thenReturn(Optional.of(entity));
        when(mapper.toResponse(entity)).thenReturn(response("abc1234", "https://new.example"));

        service().update("abc1234",
                new UpdateShortUrlRequest("https://new.example", null, true), owner);

        verify(entity).update("https://new.example", null);
        verify(urls).save(entity);
        verify(cache).evictAfterCommit("abc1234");
    }

    @Test
    void rejectsUpdateThatWouldKeepAnActiveUrlExpired() {
        ApiKey owner = owner();
        ShortUrl entity = mock(ShortUrl.class);
        when(entity.isActive()).thenReturn(true);
        when(entity.getExpiresAt()).thenReturn(NOW.minusSeconds(1));
        when(urls.findByShortCodeAndApiKeyId("abc1234", 1L)).thenReturn(Optional.of(entity));

        assertThrows(BadRequestException.class, () -> service().update("abc1234",
                new UpdateShortUrlRequest("https://new.example", null, false), owner));
        verify(entity, never()).update(anyString(), any());
    }

    @Test
    void patchesUrlAndDeactivates() {
        ApiKey owner = owner();
        ShortUrl entity = mock(ShortUrl.class);
        when(entity.isActive()).thenReturn(true);
        when(entity.getOriginalUrl()).thenReturn("https://example.com");
        when(entity.getExpiresAt()).thenReturn(null);
        when(urls.findByShortCodeAndApiKeyId("abc1234", 1L)).thenReturn(Optional.of(entity));
        when(mapper.toResponse(entity)).thenReturn(response("abc1234", "https://new.example"));

        service().patch("abc1234",
                new PatchShortUrlRequest("https://new.example", null, null, false), owner);

        verify(entity).update("https://new.example", null);
        verify(entity).deactivate(NOW);
        verify(cache).evictAfterCommit("abc1234");
    }

    @Test
    void rejectsReactivationWithExpiredExpiry() {
        ApiKey owner = owner();
        ShortUrl entity = mock(ShortUrl.class);
        when(entity.isActive()).thenReturn(false);
        when(entity.getOriginalUrl()).thenReturn("https://example.com");
        when(entity.getExpiresAt()).thenReturn(NOW.minusSeconds(1));
        when(urls.findByShortCodeAndApiKeyId("abc1234", 1L)).thenReturn(Optional.of(entity));

        assertThrows(BadRequestException.class, () -> service().patch("abc1234",
                new PatchShortUrlRequest(null, null, null, true), owner));
    }

    @Test
    void rejectsConflictingPatchExpiryFields() {
        ApiKey owner = owner();
        ShortUrl entity = mock(ShortUrl.class);
        when(entity.isActive()).thenReturn(true);
        when(urls.findByShortCodeAndApiKeyId("abc1234", 1L)).thenReturn(Optional.of(entity));

        assertThrows(BadRequestException.class, () -> service().patch("abc1234",
                new PatchShortUrlRequest(null, NOW.plusSeconds(60), true, null), owner));
    }

    @Test
    void softDeletesOnlyWhenActive() {
        ApiKey owner = owner();
        ShortUrl active = mock(ShortUrl.class);
        when(active.isActive()).thenReturn(true);
        when(urls.findByShortCodeAndApiKeyId("active1", 1L)).thenReturn(Optional.of(active));

        service().delete("active1", owner);

        verify(active).deactivate(NOW);
        verify(urls).save(active);
        verify(cache).evictAfterCommit("active1");

        ShortUrl inactive = mock(ShortUrl.class);
        when(inactive.isActive()).thenReturn(false);
        when(urls.findByShortCodeAndApiKeyId("gone1", 1L)).thenReturn(Optional.of(inactive));
        service().delete("gone1", owner);
        verify(inactive, never()).deactivate(any());
        verify(urls, never()).save(inactive);
    }

    @Test
    void returnsPageOfOwnedUrls() {
        ApiKey owner = owner();
        ShortUrl entity = mock(ShortUrl.class);
        ShortUrlResponse response = response("abc1234", "https://example.com");
        when(mapper.toResponse(entity)).thenReturn(response);
        Page<ShortUrl> page = new PageImpl<>(List.of(entity),
                PageRequest.of(1, 20, Sort.by(Sort.Direction.DESC, "createdAt")), 21);
        when(urls.findAllByApiKeyId(eq(1L), any(PageRequest.class))).thenReturn(page);

        PageResponse<ShortUrlResponse> result = service().list(owner, 1, 20);

        assertEquals(List.of(response), result.content());
        assertEquals(1, result.page());
        assertEquals(20, result.size());
        assertEquals(21, result.totalElements());
        assertEquals(2, result.totalPages());
    }

    @Test
    void validatesPagination() {
        ApiKey owner = owner();
        assertThrows(BadRequestException.class, () -> service().list(owner, -1, 20));
        assertThrows(BadRequestException.class, () -> service().list(owner, 0, 0));
        assertThrows(BadRequestException.class, () -> service().list(owner, 0, 101));
    }

    @Test
    void doesNotExposeAnotherKeysUrl() {
        ApiKey owner = owner();
        when(urls.findByShortCodeAndApiKeyId("abc1234", 1L)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service().get("abc1234", owner));
    }

    private ShortUrlResponse response(String code, String url) {
        return new ShortUrlResponse(code, "http://localhost:8080/" + code, url, true, NOW, null);
    }
}
