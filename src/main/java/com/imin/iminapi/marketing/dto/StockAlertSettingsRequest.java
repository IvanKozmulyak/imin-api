package com.imin.iminapi.marketing.dto;

/** PUT body: threshold null = auto (else 1..30, never above the tier's auto rule), lead 24|48|72, mode ask|auto; turning auto on needs autoConfirmed. */
public record StockAlertSettingsRequest(Boolean lastTicketsOn, Integer lastTicketsThreshold, Boolean priceUpOn,
                                        Integer priceUpLeadHours, String mode, Boolean autoConfirmed) {}
