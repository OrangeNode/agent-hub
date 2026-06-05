CREATE INDEX IF NOT EXISTS idx_chat_message_conversation_user_role_created_id
    ON chat_message (conversation_id, user_id, role, created_at, id);
