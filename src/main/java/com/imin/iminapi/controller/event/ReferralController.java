package com.imin.iminapi.controller.event;

import com.imin.iminapi.referral.ReferralBoardService;
import com.imin.iminapi.referral.ReferralPerkService;
import com.imin.iminapi.referral.dto.PerkGivenResponse;
import com.imin.iminapi.referral.dto.PutReferralPerkRequest;
import com.imin.iminapi.referral.dto.ReferralBoardResponse;
import com.imin.iminapi.referral.dto.ReferralPerkDto;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Crew links on the Promote tab: the leaderboard, the event's perk and the "given" mark. Any member of the event's
 * org; another org's event is 404. The perk and "given" endpoints answer 404 while perks are off platform-wide.
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}")
public class ReferralController {

    private final ReferralBoardService board;
    private final ReferralPerkService perks;

    public ReferralController(ReferralBoardService board, ReferralPerkService perks) {
        this.board = board;
        this.perks = perks;
    }

    @GetMapping("/referrals")
    public ReferralBoardResponse board(@PathVariable UUID eventId,
                                       @RequestParam(name = "limit", required = false) Integer limit,
                                       @CurrentUser AuthPrincipal principal) {
        return board.board(principal, eventId, limit);
    }

    /** Applies to new unlocks only; buyers who already unlocked keep the label they were shown. */
    @PutMapping("/referral-perk")
    public ReferralPerkDto putPerk(@PathVariable UUID eventId, @Valid @RequestBody PutReferralPerkRequest body,
                                   @CurrentUser AuthPrincipal principal) {
        return perks.put(principal, eventId, body);
    }

    /** Stop new unlocks; existing unlocks and links stay. */
    @DeleteMapping("/referral-perk")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stopPerk(@PathVariable UUID eventId, @CurrentUser AuthPrincipal principal) {
        perks.stop(principal, eventId);
    }

    @PostMapping("/referrals/{orderId}/perk-given")
    public PerkGivenResponse markGiven(@PathVariable UUID eventId, @PathVariable UUID orderId,
                                       @CurrentUser AuthPrincipal principal) {
        return perks.markGivenByOrganizer(principal, eventId, orderId);
    }

    /**
     * Undo restores "unlocked" when the perk is still marked given at {@code givenAt} (the mark being undone);
     * 409 STALE_WRITE when it was marked given again since. The unlock itself is never removed.
     */
    @DeleteMapping("/referrals/{orderId}/perk-given")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void undoGiven(@PathVariable UUID eventId, @PathVariable UUID orderId,
                          @RequestParam(name = "givenAt", required = false) Instant givenAt,
                          @CurrentUser AuthPrincipal principal) {
        perks.undoGivenByOrganizer(principal, eventId, orderId, givenAt);
    }
}
