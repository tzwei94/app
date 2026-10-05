package dev.banking.user.domain;

import java.util.UUID;

/** The only user fields exposed by directory responses. */
public record UserProfile(UUID id, String username, String displayName) {}
