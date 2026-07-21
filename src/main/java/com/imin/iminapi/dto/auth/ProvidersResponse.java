package com.imin.iminapi.dto.auth;

/** Which social sign-in providers are configured/enabled. Drives FE button visibility. */
public record ProvidersResponse(boolean google, boolean apple) {}
