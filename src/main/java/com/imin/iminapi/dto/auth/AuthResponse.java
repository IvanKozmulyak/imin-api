package com.imin.iminapi.dto.auth;

import com.imin.iminapi.dto.OrganizationDto;
import com.imin.iminapi.dto.UserDto;

public record AuthResponse(String token, UserDto user, OrganizationDto org) {}
