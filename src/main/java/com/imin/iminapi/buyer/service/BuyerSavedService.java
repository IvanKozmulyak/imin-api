package com.imin.iminapi.buyer.service;

import com.imin.iminapi.buyer.model.BuyerSavedEvent;
import com.imin.iminapi.buyer.model.BuyerSavedEventId;
import com.imin.iminapi.buyer.repository.BuyerSavedEventRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Saved events for a signed-in buyer.
 *
 * <p>The merge is <b>union, never delete</b> (PUBLIC_PAGE_API.md §20.7). A
 * client posts the ids in its localStorage; every id that still resolves to an
 * event is upserted and the rest are dropped in silence, because a deleted
 * event is not the buyer's error. The client keeps its local copy afterwards as
 * an offline mirror, so signing out never costs somebody a list they built over
 * months.
 *
 * <p>A single PUT is different and 404s on an unresolvable id: that is a
 * deliberate act about one specific event, not a bulk reconciliation of a
 * months-old device list.
 *
 * <p>"Resolves" here means the row exists — not that it is publicly visible.
 * {@code /buyer/saved} returns ids and nothing else, so a saved draft or
 * past event leaks no information; the pages that render these ids each apply
 * their own visibility rules. Tightening this to the
 * {@code NotifyReleaseSender} publicly-resolvable predicate would silently
 * evict a buyer's save the moment an organizer unpublished for an edit.
 */
@Service
public class BuyerSavedService {

    /**
     * An unbounded client list is an unbounded loop here. 500 is far above any
     * real localStorage saved list and far below anything that costs a request
     * budget.
     */
    static final int MAX_MERGE_IDS = 500;

    private final BuyerSavedEventRepository saved;
    private final EventRepository events;

    public BuyerSavedService(BuyerSavedEventRepository saved, EventRepository events) {
        this.saved = saved;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<BuyerSavedEvent> list(UUID accountId) {
        return saved.findByBuyerAccountIdOrderByCreatedAtDesc(accountId);
    }

    @Transactional
    public void save(UUID accountId, UUID eventId) {
        if (!events.existsById(eventId)) throw ApiException.notFound("Event");
        if (saved.existsById(new BuyerSavedEventId(accountId, eventId))) return;  // idempotent
        saved.save(new BuyerSavedEvent(accountId, eventId));
    }

    /** Idempotent: removing something that was never saved is a no-op, not a 404. */
    @Transactional
    public void remove(UUID accountId, UUID eventId) {
        saved.deleteByBuyerAccountIdAndEventId(accountId, eventId);
    }

    @Transactional
    public List<BuyerSavedEvent> merge(UUID accountId, List<UUID> eventIds) {
        if (eventIds.size() > MAX_MERGE_IDS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "At most " + MAX_MERGE_IDS + " ids may be merged at once");
        }
        for (UUID id : eventIds) {
            if (id == null) continue;
            if (!events.existsById(id)) continue;                                 // silently dropped
            if (saved.existsById(new BuyerSavedEventId(accountId, id))) continue;
            saved.save(new BuyerSavedEvent(accountId, id));
        }
        return saved.findByBuyerAccountIdOrderByCreatedAtDesc(accountId);
    }
}
