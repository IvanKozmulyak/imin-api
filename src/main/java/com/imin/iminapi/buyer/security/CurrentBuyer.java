package com.imin.iminapi.buyer.security;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the authenticated {@link BuyerPrincipal} into a controller method,
 * mirroring {@link com.imin.iminapi.security.CurrentUser} for organizers.
 *
 * <p>The two are intentionally separate annotations over separate principal
 * types: a handler that asks for a buyer can never be handed an organizer, and
 * vice versa — the argument resolver returns {@code null} on a type mismatch
 * rather than coercing.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@AuthenticationPrincipal
public @interface CurrentBuyer {}
