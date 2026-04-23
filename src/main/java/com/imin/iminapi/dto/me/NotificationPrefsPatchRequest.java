package com.imin.iminapi.dto.me;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationPrefsPatchRequest(
        Boolean ticketSold, Boolean squadFormed, Boolean predictorShift,
        Boolean fillMilestone, Boolean postEventReport, Boolean campaignEnded,
        Boolean payoutArrived) {}
