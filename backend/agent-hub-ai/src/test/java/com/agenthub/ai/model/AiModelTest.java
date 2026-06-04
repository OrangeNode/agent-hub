package com.agenthub.ai.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.ai.model.entity.AiModel;
import org.junit.jupiter.api.Test;

class AiModelTest {

    @Test
    void shouldAccessImageCapabilityFields() {
        AiModel model = new AiModel();

        model.setImageInputSupported(true);
        model.setImageTokenEstimate(1500);

        assertThat(model.getImageInputSupported()).isTrue();
        assertThat(model.getImageTokenEstimate()).isEqualTo(1500);
    }
}
