package com.example.shortener.repository;

import com.example.shortener.model.ShortUrl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {
    Optional<ShortUrl> findByShortCode(String shortCode);

    Optional<ShortUrl> findByShortCodeAndApiKeyId(String shortCode, Long apiKeyId);

    Optional<ShortUrl> findByShortCodeAndActiveTrue(String shortCode);

    boolean existsByShortCode(String shortCode);

    Page<ShortUrl> findAllByApiKeyId(Long apiKeyId, Pageable pageable);

    List<ShortUrl> findAllByActiveTrueAndExpiresAtLessThanEqual(Instant now, Pageable pageable);

    List<ShortUrl> findAllByActiveFalseAndDeactivatedAtLessThanEqual(Instant cutoff, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ShortUrl s where s.id in :ids")
    void deleteAllByIdInBatch(Iterable<Long> ids);
}
