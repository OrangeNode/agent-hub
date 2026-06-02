package com.agenthub.system.auth.controller.response;

import com.agenthub.system.user.dto.UserProfile;

public record LoginResponse(String token, UserProfile profile) {
}
