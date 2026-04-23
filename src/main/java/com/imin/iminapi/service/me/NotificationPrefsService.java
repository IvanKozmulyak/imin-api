package com.imin.iminapi.service.me;

import com.imin.iminapi.dto.NotificationPreferencesDto;
import com.imin.iminapi.dto.me.NotificationPrefsPatchRequest;
import com.imin.iminapi.model.NotificationPreferences;
import com.imin.iminapi.repository.NotificationPreferencesRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationPrefsService {

    private final NotificationPreferencesRepository repo;

    public NotificationPrefsService(NotificationPreferencesRepository repo) { this.repo = repo; }

    @Transactional
    public NotificationPreferencesDto get(AuthPrincipal p) {
        NotificationPreferences row = repo.findById(p.userId()).orElseGet(() -> {
            NotificationPreferences fresh = new NotificationPreferences();
            fresh.setUserId(p.userId());
            return repo.save(fresh);
        });
        return NotificationPreferencesDto.from(row);
    }

    @Transactional
    public NotificationPreferencesDto patch(AuthPrincipal p, NotificationPrefsPatchRequest body) {
        NotificationPreferences row = repo.findById(p.userId()).orElseGet(() -> {
            NotificationPreferences fresh = new NotificationPreferences();
            fresh.setUserId(p.userId());
            return fresh;
        });
        if (body.ticketSold() != null) row.setTicketSold(body.ticketSold());
        if (body.squadFormed() != null) row.setSquadFormed(body.squadFormed());
        if (body.predictorShift() != null) row.setPredictorShift(body.predictorShift());
        if (body.fillMilestone() != null) row.setFillMilestone(body.fillMilestone());
        if (body.postEventReport() != null) row.setPostEventReport(body.postEventReport());
        if (body.campaignEnded() != null) row.setCampaignEnded(body.campaignEnded());
        if (body.payoutArrived() != null) row.setPayoutArrived(body.payoutArrived());
        return NotificationPreferencesDto.from(repo.save(row));
    }
}
