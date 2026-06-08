package com.agenthub.sandbox.service;

public interface SandboxSessionService {

    String ensureSession(Long userId, Long conversationId);
}
