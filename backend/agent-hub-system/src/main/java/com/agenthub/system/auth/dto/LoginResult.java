package com.agenthub.system.auth.dto;

import com.agenthub.system.user.dto.UserProfile;

public record LoginResult(UserProfile profile) {
}
