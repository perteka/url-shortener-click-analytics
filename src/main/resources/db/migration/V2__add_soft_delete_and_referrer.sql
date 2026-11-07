-- Soft-delete support for expired/deactivated links and normalized referrer hosts.
-- The IF NOT EXISTS clauses make this migration safe for databases that were
-- previously managed by Hibernate ddl-auto=update and are now baselined by Flyway.

alter table api_keys add column if not exists admin boolean not null default false;
alter table short_urls add column if not exists deactivated_at timestamp with time zone;
alter table click_events add column if not exists referrer_host varchar(255);

-- Rows deactivated before this migration have no timestamp; anchor them to the
-- migration time so the retention job can eventually remove them.
update short_urls
set deactivated_at = now()
where active = false
  and deactivated_at is null;

create index if not exists idx_short_urls_active_expires on short_urls (active, expires_at);
create index if not exists idx_short_urls_active_deactivated on short_urls (active, deactivated_at);
create index if not exists idx_click_referrer_host on click_events (referrer_host);
