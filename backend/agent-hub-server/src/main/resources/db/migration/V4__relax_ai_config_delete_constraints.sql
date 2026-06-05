ALTER TABLE chat_message DROP CONSTRAINT fk_chat_message_model;
ALTER TABLE chat_message DROP CONSTRAINT fk_chat_message_provider;
ALTER TABLE chat_conversation DROP CONSTRAINT fk_chat_conversation_model;
ALTER TABLE chat_conversation DROP CONSTRAINT fk_chat_conversation_provider;
ALTER TABLE ai_model DROP CONSTRAINT fk_ai_model_provider;

ALTER TABLE ai_model
    ADD CONSTRAINT fk_ai_model_provider
    FOREIGN KEY (provider_id) REFERENCES ai_provider (id)
    ON DELETE CASCADE;

ALTER TABLE chat_conversation
    ADD CONSTRAINT fk_chat_conversation_model
    FOREIGN KEY (model_id) REFERENCES ai_model (id)
    ON DELETE SET NULL;

ALTER TABLE chat_conversation
    ADD CONSTRAINT fk_chat_conversation_provider
    FOREIGN KEY (provider_id) REFERENCES ai_provider (id)
    ON DELETE SET NULL;

ALTER TABLE chat_message
    ADD CONSTRAINT fk_chat_message_model
    FOREIGN KEY (model_id) REFERENCES ai_model (id)
    ON DELETE SET NULL;

ALTER TABLE chat_message
    ADD CONSTRAINT fk_chat_message_provider
    FOREIGN KEY (provider_id) REFERENCES ai_provider (id)
    ON DELETE SET NULL;
