package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Band;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.MetaAds;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Priors;
import com.imin.iminapi.audienceplan.config.LogicLoader;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/** The shipped logic files, and copies with one prior changed. */
final class PlanFixtures {

    static final AudiencePlanLogic LOGIC = shipped();

    private PlanFixtures() {}

    static AudiencePlanLogic withReach(MetaAds meta, Optional<Band> instagram) {
        Priors p = LOGIC.priors();
        Priors changed = new Priors(p.version(), p.classes(), p.priorStrengthInvitations(), p.genreFit(), p.noShowBefore(),
                p.noShowShowUpIfBuy(), p.ticketsPerOrder(), p.showUpPaid(), p.showUpFreeRsvp(), meta, instagram,
                p.tribeSize());
        return new AudiencePlanLogic(LOGIC.logic(), changed, LOGIC.genres());
    }

    private static AudiencePlanLogic shipped() {
        try (InputStream logic = resource("audienceplan/logic-v1.yaml");
             InputStream priors = resource("audienceplan/priors-v1.yaml");
             InputStream genres = resource("audienceplan/genres-v1.yaml")) {
            return LogicLoader.parse(logic, priors, genres);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InputStream resource(String path) {
        InputStream in = PlanFixtures.class.getClassLoader().getResourceAsStream(path);
        if (in == null) throw new IllegalStateException("missing " + path);
        return in;
    }
}
