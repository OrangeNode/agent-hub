package com.agenthub.sandbox.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.sandbox.client.dto.SandboxEvent;
import com.agenthub.sandbox.client.dto.SandboxFileItem;
import java.util.List;
import org.junit.jupiter.api.Test;

class HttpSandboxClientTest {

    @Test
    void shouldParseSseBlocks() {
        List<SandboxEvent> events = HttpSandboxClient.parseSseBlocks(List.of(
                "event: stdout_delta",
                "data: {\"content\":\"hello\"}",
                "",
                "event: completed",
                "data: {\"executionId\":\"exec_1\",\"exitCode\":0}",
                ""
        ));

        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo("stdout_delta");
        assertThat(events.get(0).content()).isEqualTo("hello");
        assertThat(events.get(1).type()).isEqualTo("completed");
        assertThat(events.get(1).executionId()).isEqualTo("exec_1");
        assertThat(events.get(1).exitCode()).isZero();
    }

    @Test
    void shouldJoinMultilineSseData() {
        List<SandboxEvent> events = HttpSandboxClient.parseSseBlocks(List.of(
                "event: stderr_delta",
                "data: line1",
                "data: line2",
                ""
        ));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().content()).isEqualTo("line1\nline2");
    }

    @Test
    void shouldParseTimedOutEvent() {
        List<SandboxEvent> events = HttpSandboxClient.parseSseBlocks(List.of(
                "event: completed",
                "data: {\"executionId\":\"exec_1\",\"exitCode\":-1,\"timedOut\":true}",
                ""
        ));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().timedOut()).isTrue();
    }

    @Test
    void shouldMapGuestFileFields() {
        SandboxFileItem item = HttpSandboxClient.toSandboxFileItemForTest("""
                {"relativePath":"input/data.csv","size":10,"isDir":false}
                """);

        assertThat(item.name()).isEqualTo("data.csv");
        assertThat(item.path()).isEqualTo("/workspace/input/data.csv");
        assertThat(item.size()).isEqualTo(10L);
        assertThat(item.directory()).isFalse();
    }
}
