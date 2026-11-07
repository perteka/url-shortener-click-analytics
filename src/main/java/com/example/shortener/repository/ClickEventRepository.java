package com.example.shortener.repository;

import com.example.shortener.model.ClickEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {
    @Query(value = """
            select to_char((clicked_at at time zone 'UTC')::date, 'YYYY-MM-DD') as day,
                   count(*) as clicks
            from click_events
            where short_url_id = :id
              and clicked_at >= :from
            group by 1
            order by 1
            """, nativeQuery = true)
    List<Object[]> countByDay(@Param("id") Long id, @Param("from") Instant from);

    @Query(value = """
            select coalesce(referrer_host, 'direct') as source,
                   count(*) as clicks
            from click_events
            where short_url_id = :id
              and clicked_at >= :from
            group by 1
            order by clicks desc
            limit :limit
            """, nativeQuery = true)
    List<Object[]> countByReferrer(@Param("id") Long id,
                                   @Param("from") Instant from,
                                   @Param("limit") int limit);

    @Query("""
            select c.deviceType, count(c)
            from ClickEvent c
            where c.shortUrl.id = :id and c.clickedAt >= :from
            group by c.deviceType
            order by count(c) desc
            """)
    List<Object[]> countByDevice(@Param("id") Long id, @Param("from") Instant from);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            delete from click_events
            where id in (
                select id from click_events
                where clicked_at < :before
                order by clicked_at
                limit :limit
            )
            """, nativeQuery = true)
    int deleteOlderThan(@Param("before") Instant before, @Param("limit") int limit);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ClickEvent c where c.shortUrl.id in :ids")
    int deleteForShortUrls(@Param("ids") List<Long> ids);
}
