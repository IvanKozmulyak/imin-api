package com.imin.iminapi.model;

public enum UserRole {
    OWNER, ADMIN, MEMBER;

    public String wireValue() {
        return name().toLowerCase();
    }

    public static UserRole fromWire(String value) {
        return UserRole.valueOf(value.toUpperCase());
    }
}
