package com.agenthub.sandbox.policy;

import static org.assertj.core.api.Assertions.assertThat;
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
        assertThatCode(() -> policy.validate("python3 /workspace/artifacts/quick_sort.py", "/workspace", 30))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("cat /workspace/input/input.txt", "/workspace", 30))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("python3 -c \"print(1)\"", "/workspace", 30))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validate(
                "bash -lc \"python3 main.py && cat /workspace/artifacts/output.txt\"", "/workspace", 30))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("""
                cat <<'EOF' > /workspace/artifacts/input.txt
                3 1 2
                EOF
                """, "/workspace", 30))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldNormalizeRelativeWorkspacePath() {
        assertThat(policy.validateWorkspacePath("reports/out.txt", "路径不在允许访问范围内"))
                .isEqualTo("/workspace/reports/out.txt");
    }

    @Test
    void shouldNormalizeRelativeOutputPathToArtifacts() {
        assertThat(policy.validateOutputPath("作文.txt"))
                .isEqualTo("/workspace/artifacts/作文.txt");
    }

    @Test
    void shouldNormalizeRelativeInputPathToInputDirectory() {
        assertThat(policy.validateInputPath("data.csv"))
                .isEqualTo("/workspace/input/data.csv");
    }

    @Test
    void shouldRejectInputPathOutsideInputDirectory() {
        assertThatThrownBy(() -> policy.validateInputPath("/workspace/artifacts/data.csv"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PATH_DENIED);
    }

    @Test
    void shouldRejectRelativeTraversalPath() {
        assertThatThrownBy(() -> policy.validateOutputPath("../secret.txt"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PATH_DENIED);
    }

    @Test
    void shouldRejectDangerousKeyword() {
        assertThatThrownBy(() -> policy.validate("sudo whoami", "/workspace", 30))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DENIED);
    }

    @Test
    void shouldRejectUnknownShellEntrypoint() {
        assertThatThrownBy(() -> policy.validate("zsh -c ls", "/workspace", 30))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DENIED);
    }

    @Test
    void shouldRejectDangerousShellChain() {
        assertThatThrownBy(() -> policy.validate("python3 main.py; cat /etc/passwd", "/workspace", 30))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DENIED);
    }

    @Test
    void shouldRejectDangerousInlinePythonCommand() {
        assertThatThrownBy(() -> policy.validate("python3 -c \"open('/etc/passwd').read()\"", "/workspace", 30))
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
