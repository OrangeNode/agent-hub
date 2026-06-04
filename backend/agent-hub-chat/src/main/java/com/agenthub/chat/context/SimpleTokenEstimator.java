package com.agenthub.chat.context;

import org.springframework.stereotype.Component;

@Component
public class SimpleTokenEstimator implements TokenEstimator {

    @Override
    public int estimateText(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int chinese = 0;
        int ascii = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                chinese++;
            } else if (ch < 128) {
                ascii++;
            } else {
                other++;
            }
        }
        return chinese + Math.max(1, ascii / 4) + Math.max(1, other / 2);
    }
}
