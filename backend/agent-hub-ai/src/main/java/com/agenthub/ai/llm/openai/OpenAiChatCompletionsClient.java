package com.agenthub.ai.llm.openai;

import java.net.http.HttpClient;

public class OpenAiChatCompletionsClient extends OpenAiCompatibleClient {

    public OpenAiChatCompletionsClient() {
        super();
    }

    public OpenAiChatCompletionsClient(HttpClient httpClient) {
        super(httpClient);
    }
}
