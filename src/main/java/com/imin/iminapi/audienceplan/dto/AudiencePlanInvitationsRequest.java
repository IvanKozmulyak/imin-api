package com.imin.iminapi.audienceplan.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

/**
 * Plan segments to invite, keyed by class × genre fit of the current plan. Validated in the service after the beta
 * gate, so a disabled org sees the same 404 whatever it sends. {@code recreateMissingDrafts} rebuilds the draft of a
 * stored arm whose campaign was deleted, from the stored assignments.
 */
public record AudiencePlanInvitationsRequest(List<SegmentInvitation> segments, Boolean recreateMissingDrafts) {

    /**
     * {@code arms} ⊂ launch | d3 | early_bird_end | slump; {@code holdoutPct} 10..20, omitted = the logic file's
     * default.
     */
    public record SegmentInvitation(@JsonAlias("class") String classKey, String genreFit, List<String> arms,
                                    Integer holdoutPct) {}
}
