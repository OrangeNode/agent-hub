package com.agenthub.server.auth;

import com.agenthub.system.user.UserProfile;

public record LoginResponse(String token, UserProfile profile) {
}
