package com.imin.iminapi.marketing.unsubscribe;

/**
 * What the buyer-site unsubscribe page needs to render for a token, read-only.
 *
 * @param state         subscribed | unsubscribed | test
 * @param channel       email | sms, null for a legacy test token
 * @param organizerName the sending organizer's display name, null when unknown
 * @param locale        the campaign's language (en|es|fr|uk), null when unknown
 */
public record UnsubscribeDetails(String state, String channel, String organizerName, String locale) {}
