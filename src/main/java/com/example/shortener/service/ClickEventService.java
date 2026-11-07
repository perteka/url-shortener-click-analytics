package com.example.shortener.service;

import com.example.shortener.model.ClickEvent;
import com.example.shortener.repository.ClickEventRepository;
import com.example.shortener.repository.ShortUrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class ClickEventService {
    private static final Logger log = LoggerFactory.getLogger(ClickEventService.class);

    private final ClickEventRepository clicks;
    private final ShortUrlRepository shortUrls;

    public ClickEventService(ClickEventRepository clicks, ShortUrlRepository shortUrls) {
        this.clicks = clicks;
        this.shortUrls = shortUrls;
    }

    @Async("clickEventExecutor")
    @TransactionalEventListener(phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(RedirectEvent event) {
        clicks.save(new ClickEvent(
                shortUrls.getReferenceById(event.shortUrlId()),
                event.referrer(),
                event.referrerHost(),
                event.deviceType(),
                event.clickedAt()
        ));
        log.debug("Recorded click for short URL id={}", event.shortUrlId());
    }
}
