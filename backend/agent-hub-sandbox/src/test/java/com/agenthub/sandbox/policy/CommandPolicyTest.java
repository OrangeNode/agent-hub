package com.agenthub.sandbox.policy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

class CommandPolicyTest {

    private final CommandPolicy policy = new CommandPolicy(200, 60);

    @Test
    void shouldAllowCommonCommand() {
        assertThatCode(() -> policy.validate("python3 main.py", "/workspace", 30))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectDangerousKeyword() {
        assertThatThrownBy(() -> policy.validate("sudo whoami", "/workspace", 30))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DENIED);
    }

    @Test
    void shouldRejectShellEntrypoint() {
        assertThatThrownBy(() -> policy.validate("bash -c ls", "/workspace", 30))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DENIED);
    }

    @Test
    void shouldRejectShellSeparator() {
        assertThatThrownBy(() -> policy.validate("python3 main.py; cat /etc/passwd", "/workspace", 30))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DENIED);
    }

    @Test
    void shouldRejectDangerousPath() {
        assertThatThrownBy(() -> policy.validate("cat /etc/passwd", "/workspace", 30))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DENIED);
    }

    @Test
    void shouldRejectPathTraversalArgument() {
        assertThatThrownBy(() -> policy.validate("cat ../secret.txt", "/workspace", 30))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DENIED);
    }

    @Test
    void shouldRejectAbsolutePathOutsideWorkspace() {
        assertThatThrownBy(() -> policy.validate("cat /var/log/app.log", "/workspace", 30))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DENIED);
    }

    @Test
    void shouldRejectWorkspaceEscape() {
        assertThatThrownBy(() -> policy.validate("python3 main.py", "/workspace/../root", 30))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PATH_DENIED);
    }
}
