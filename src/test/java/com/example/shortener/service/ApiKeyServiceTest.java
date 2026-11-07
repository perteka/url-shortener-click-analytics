package com.example.shortener.service;

import com.example.shortener.config.AppProperties;
import com.example.shortener.dto.ApiKeyResponse;
import com.example.shortener.dto.CreatedApiKeyResponse;
import com.example.shortener.model.ApiKey;
import com.example.shortener.repository.ApiKeyRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Optional;

import static com.example.shortener.support.TestFixtures.NOW;
import static com.example.shortener.support.TestFixtures.properties;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApiKeyServiceTest {
    private final ApiKeyRepository repository = mock(ApiKeyRepository.class);
    private final ApiKeyHasher hasher = mock(ApiKeyHasher.class);
    private final AppProperties properties = properties();

    private ApiKeyService service(Environment environment) {
        return new ApiKeyService(repository, hasher, properties, environment);
    }

    private Environment devEnvironment() {
        return new MockEnvironment();
    }

    @Test
    void requiresAValidActiveApiKey() {
        ApiKey key = mock(ApiKey.class);
        when(hasher.hash("raw-key")).thenReturn("hash");
        when(hasher.hash("unknown")).thenReturn("unknown-hash");
        when(repository.findByKeyHashAndActiveTrue("hash")).thenReturn(Optional.of(key));

        ApiKeyService service = service(devEnvironment());

        assertSame(key, service.requireActive("raw-key"));
        assertThrows(UnauthorizedException.class, () -> service.requireActive(null));
        assertThrows(UnauthorizedException.class, () -> service.requireActive("  "));
        assertThrows(UnauthorizedException.class, () -> service.requireActive("unknown"));
    }

    @Test
    void createsKeyAndReturnsRawSecretOnlyOnce() {
        when(hasher.hash(anyString())).thenReturn("hashed");
        when(repository.save(any(ApiKey.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreatedApiKeyResponse response = service(devEnvironment()).createKey("  my key  ");

        assertEquals("my key", response.name());
        assertEquals(43, response.apiKey().length());
        ArgumentCaptor<ApiKey> saved = ArgumentCaptor.forClass(ApiKey.class);
        verify(repository).save(saved.capture());
        assertEquals("hashed", saved.getValue().getKeyHash());
        assertEquals("my key", saved.getValue().getName());
        assertFalse(saved.getValue().isAdmin());
    }

    @Test
    void rejectsBlankOrOversizedKeyNames() {
        ApiKeyService service = service(devEnvironment());

        assertThrows(BadRequestException.class, () -> service.createKey(" "));
        assertThrows(BadRequestException.class, () -> service.createKey("x".repeat(101)));
    }

    @Test
    void listsKeysWithoutExposingHashes() {
        ApiKey key = mock(ApiKey.class);
        when(key.getId()).thenReturn(5L);
        when(key.getName()).thenReturn("reporting");
        when(key.isActive()).thenReturn(true);
        when(key.isAdmin()).thenReturn(false);
        when(key.getCreatedAt()).thenReturn(NOW);
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(key));

        List<ApiKeyResponse> keys = service(devEnvironment()).listKeys();

        assertEquals(List.of(new ApiKeyResponse(5L, "reporting", true, false, NOW)), keys);
    }

    @Test
    void revokesNonAdminKey() {
        ApiKey key = mock(ApiKey.class);
        when(key.isActive()).thenReturn(true);
        when(key.isAdmin()).thenReturn(false);
        when(repository.findById(2L)).thenReturn(Optional.of(key));

        service(devEnvironment()).revokeKey(2L, 1L);

        verify(key).deactivate();
        verify(repository).save(key);
        verify(repository, never()).lockActiveAdminKeys();
    }

    @Test
    void refusesToRevokeTheLastAdminKey() {
        ApiKey admin = mock(ApiKey.class);
        when(admin.isActive()).thenReturn(true);
        when(admin.isAdmin()).thenReturn(true);
        when(repository.findById(2L)).thenReturn(Optional.of(admin));
        when(repository.lockActiveAdminKeys()).thenReturn(List.of(admin));

        assertThrows(ConflictException.class, () -> service(devEnvironment()).revokeKey(2L, 1L));

        verify(admin, never()).deactivate();
        verify(repository, never()).save(admin);
    }

    @Test
    void revokesAdminWhenAnotherAdminRemains() {
        ApiKey admin = mock(ApiKey.class);
        ApiKey other = mock(ApiKey.class);
        when(admin.isActive()).thenReturn(true);
        when(admin.isAdmin()).thenReturn(true);
        when(repository.findById(2L)).thenReturn(Optional.of(admin));
        when(repository.lockActiveAdminKeys()).thenReturn(List.of(admin, other));

        service(devEnvironment()).revokeKey(2L, 1L);

        verify(admin).deactivate();
        verify(repository).save(admin);
    }

    @Test
    void reportsMissingKeyOnRevoke() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service(devEnvironment()).revokeKey(99L, 1L));
    }

    @Test
    void createsBootstrapAdminKeyWhenItDoesNotExist() {
        when(hasher.hash(properties.apiKey())).thenReturn("bootstrap-hash");
        when(repository.findByKeyHash("bootstrap-hash")).thenReturn(Optional.empty());
        when(repository.save(any(ApiKey.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service(devEnvironment()).run();

        ArgumentCaptor<ApiKey> saved = ArgumentCaptor.forClass(ApiKey.class);
        verify(repository).save(saved.capture());
        assertEquals("bootstrap-hash", saved.getValue().getKeyHash());
        assertEquals("bootstrap", saved.getValue().getName());
        assertTrue(saved.getValue().isAdmin());
    }

    @Test
    void promotesExistingBootstrapKey() {
        ApiKey existing = new ApiKey("bootstrap-hash", "bootstrap", false);
        when(hasher.hash(properties.apiKey())).thenReturn("bootstrap-hash");
        when(repository.findByKeyHash("bootstrap-hash")).thenReturn(Optional.of(existing));

        service(devEnvironment()).run();

        assertTrue(existing.isAdmin());
        verify(repository).save(existing);
    }

    @Test
    void doesNotResurrectRevokedBootstrapKey() {
        ApiKey existing = new ApiKey("bootstrap-hash", "bootstrap", true);
        existing.deactivate();
        when(hasher.hash(properties.apiKey())).thenReturn("bootstrap-hash");
        when(repository.findByKeyHash("bootstrap-hash")).thenReturn(Optional.of(existing));

        service(devEnvironment()).run();

        verify(repository, never()).save(any(ApiKey.class));
    }

    @Test
    void failsFastInProductionWithWeakSecrets() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThrows(IllegalStateException.class, () -> service(environment).run());
        verifyNoInteractions(repository);
    }
}
