package com.imin.iminapi.predictor.calendar;

import com.imin.iminapi.predictor.model.ReferenceCalendarEntry;
import com.imin.iminapi.predictor.repository.ReferenceCalendarEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Replaces the stored rows of one batch scope, in one transaction per batch. */
@Component
public class ReferenceCalendarWriter {

    private static final Logger log = LoggerFactory.getLogger(ReferenceCalendarWriter.class);

    private final ReferenceCalendarEntryRepository repository;
    private final Clock clock;

    public ReferenceCalendarWriter(ReferenceCalendarEntryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Upserts the batch on the unique key, then deletes rows of its scope it no longer has.
     * An empty batch is refused so a blank upstream answer cannot wipe the scope. Returns rows written.
     */
    @Transactional
    public int replace(CalendarSource.Batch batch) {
        if (batch.rows().isEmpty()) {
            log.warn("ReferenceCalendarWriter: empty batch for {} {} {}..{}, keeping stored rows",
                    batch.sourceUrl(), batch.kinds(), batch.from(), batch.to());
            return 0;
        }
        Instant now = clock.instant();
        Map<String, ReferenceCalendarEntry> stored = new HashMap<>();
        for (ReferenceCalendarEntry e : repository.findBySourceUrlAndKindInAndCalendarDateBetween(
                batch.sourceUrl(), batch.kinds(), batch.from(), batch.to())) {
            stored.put(key(e), e);
        }
        Map<String, CalendarRow> incoming = new LinkedHashMap<>();
        for (CalendarRow r : batch.rows()) incoming.putIfAbsent(r.key(), r);

        List<ReferenceCalendarEntry> toSave = new ArrayList<>();
        for (CalendarRow r : incoming.values()) {
            ReferenceCalendarEntry e = stored.remove(r.key());
            if (e == null) {
                e = new ReferenceCalendarEntry();
                e.setCountry(r.country());
                e.setRegion(r.region());
                e.setCalendarDate(r.date());
                e.setKind(r.kind());
                e.setName(r.name());
            }
            e.setEndDate(r.endDate());
            e.setSourceUrl(r.sourceUrl());
            e.setSyncedAt(now);
            toSave.add(e);
        }
        repository.deleteAll(stored.values());
        repository.flush();
        repository.saveAll(toSave);
        return toSave.size();
    }

    private static String key(ReferenceCalendarEntry e) {
        return e.getCountry() + "|" + e.getRegion() + "|" + e.getCalendarDate() + "|" + e.getKind() + "|" + e.getName();
    }
}
