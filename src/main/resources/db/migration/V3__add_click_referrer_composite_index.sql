-- Composite index for referrer grouping queries that filter by short URL and group by referrer host.

create index if not exists idx_click_events_short_url_referrer_host
    on click_events (short_url_id, referrer_host);
