package com.imin.iminapi.audience.service;

import java.util.UUID;

/** A confirmation email row was stored; the mailer sends it after the sign-up commits. */
public record ConsentConfirmationRequested(UUID tokenId) {}
