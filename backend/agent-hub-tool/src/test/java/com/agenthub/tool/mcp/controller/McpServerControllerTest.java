package com.agenthub.tool.mcp.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.common.api.ApiResponse;
import com.agenthub.tool.mcp.command.CreateMcpServerCommand;
import com.agenthub.tool.mcp.controller.request.CreateMcpServerRequest;
import com.agenthub.tool.mcp.controller.request.UpdateMcpToolEnabledRequest;
import com.agenthub.tool.mcp.dto.McpServerItem;
import com.agenthub.tool.mcp.dto.McpToolCallLogItem;
import com.agenthub.tool.mcp.dto.McpToolItem;
import com.agenthub.tool.mcp.service.McpServerService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class McpServerControllerTest {

    @Test
    void shouldCreateServerByMappingRequestToCommand() {
        McpServerService service = mock(McpServerService.class);
        McpServerController controller = new McpServerController(service);
        ArgumentCaptor<CreateMcpServerCommand> commandCaptor = ArgumentCaptor.forClass(CreateMcpServerCommand.class);
        McpServerItem item = serverItem();
        when(service.create(commandCaptor.capture())).thenReturn(item);

        ApiResponse<McpServerItem> response = controller.createServer(new CreateMcpServerRequest(
                "github",
                "GitHub",
                "GitHub MCP 服务",
                "https://mcp.example.com/mcp",
                "STREAMABLE_HTTP",
                "2025-11-25",
                "BEARER",
                "token",
                "{}",
                10,
                60,
                true,
                0
        ));

        assertThat(response.data()).isEqualTo(item);
        assertThat(commandCaptor.getValue().serverKey()).isEqualTo("github");
        assertThat(commandCaptor.getValue().authToken()).isEqualTo("token");
    }

    @Test
    void shouldUpdateToolEnabled() {
        McpServerService service = mock(McpServerService.class);
        McpServerController controller = new McpServerController(service);
        McpToolItem item = toolItem();
        when(service.updateToolEnabled(2L, false)).thenReturn(item);

        ApiResponse<McpToolItem> response = controller.updateToolEnabled(
                2L,
                new UpdateMcpToolEnabledRequest(false)
        );

        assertThat(response.data()).isEqualTo(item);
        verify(service).updateToolEnabled(2L, false);
    }

    @Test
    void shouldListCallLogsByQueryParameters() {
        McpServerService service = mock(McpServerService.class);
        McpServerController controller = new McpServerController(service);
        McpToolCallLogItem logItem = new McpToolCallLogItem(
                3L,
                "github",
                "mcp.github.search",
                "search",
                4L,
                5L,
                "COMPLETED",
                null,
                12L,
                null
        );
        when(service.callLogs(1L, 4L, "COMPLETED", 20, 5)).thenReturn(List.of(logItem));

        ApiResponse<List<McpToolCallLogItem>> response = controller.callLogs(
                1L,
                4L,
                "COMPLETED",
                20,
                5
        );

        assertThat(response.data()).containsExactly(logItem);
        verify(service).callLogs(eq(1L), eq(4L), eq("COMPLETED"), eq(20), eq(5));
    }

    private McpServerItem serverItem() {
        return new McpServerItem(
                1L,
                "github",
                "GitHub",
                "GitHub MCP 服务",
                "https://mcp.example.com/mcp",
                "STREAMABLE_HTTP",
                "2025-11-25",
                "BEARER",
                true,
                "{}",
                10,
                60,
                true,
                null,
                null,
                null,
                0,
                0
        );
    }

    private McpToolItem toolItem() {
        return new McpToolItem(
                2L,
                1L,
                "github",
                "search",
                "mcp.github.search",
                "搜索",
                "搜索仓库",
                "{}",
                false,
                null
        );
    }
}
