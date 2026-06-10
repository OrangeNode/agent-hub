ALTER TABLE chat_message
    ADD COLUMN IF NOT EXISTS process_events TEXT;
