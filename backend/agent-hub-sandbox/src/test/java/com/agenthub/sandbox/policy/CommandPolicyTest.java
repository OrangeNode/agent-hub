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
    void shouldAllowDocumentToolchainAndProbeCommands() {
        assertThatCode(() -> policy.validate("dotnet --info", "/workspace", 30))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("pandoc --version", "/workspace", 30))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("soffice --version", "/workspace", 30))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("libreoffice --version", "/workspace", 30))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("which dotnet", "/workspace", 30))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("command -v dotnet", "/workspace", 30))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("test -f /workspace/skills/docx/scripts/docx", "/workspace", 30))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("file /workspace/artifacts/report.docx", "/workspace", 30))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("stat /workspace/artifacts/report.docx", "/workspace", 30))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldAllowNonSensitiveDevelopmentCommandsWithoutStrictEntrypointWhitelist() {
        assertThatCode(() -> policy.validate("uv pip install -r requirements.txt", "/workspace", 30))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("zsh -c \"echo ok\"", "/workspace", 30))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validate(
                "cp /workspace/artifacts/Program.cs /tmp/docx-work/Program.cs", "/workspace", 30))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("dotnet build /tmp/docx-work/Docx.csproj", "/workspace", 30))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validate(
                "bash /workspace/skills/docx/scripts/docx build /workspace/artifacts/report.docx 2>&1",
                "/workspace",
                30))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldAllowTemporaryBuildWorkingDirectory() {
        assertThatCode(() -> policy.validate("dotnet build Docx.csproj", "/tmp/docx-work", 30))
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
    void shouldNormalizeWorkspaceWorkPathAndRejectArtifactsForWorkFiles() {
        assertThat(policy.validateWorkspaceWorkPath("docx/Program.cs"))
                .isEqualTo("/workspace/work/docx/Program.cs");
        assertThat(policy.validateWorkspaceWorkPath("/workspace/work/docx/Program.cs"))
                .isEqualTo("/workspace/work/docx/Program.cs");
        assertThat(policy.validateWorkspaceWorkPath("/tmp/docx-work/Program.cs"))
                .isEqualTo("/tmp/docx-work/Program.cs");
        assertThatThrownBy(() -> policy.validateWorkspaceWorkPath("/tmp/other/Program.cs"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PATH_DENIED);
        assertThatThrownBy(() -> policy.validateWorkspaceWorkPath("/workspace/artifacts/Program.cs"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PATH_DENIED);
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
    void shouldAllowCommandBuiltinWhenContentIsSafe() {
        assertThatCode(() -> policy.validate("command sh -lc \"echo ok\"", "/workspace", 30))
                .doesNotThrowAnyException();
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
        assertThatThrownBy(() -> policy.validate("cat /proc/self/environ", "/workspace", 30))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DENIED);
        assertThatThrownBy(() -> policy.validate("cat /root/.ssh/id_rsa", "/workspace", 30))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DENIED);
        assertThatThrownBy(() -> policy.validate("cat /home/app/.ssh/id_rsa", "/workspace", 30))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DENIED);
        assertThatThrownBy(() -> policy.validate("stat /dev/kvm", "/workspace", 30))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DENIED);
        assertThatThrownBy(() -> policy.validate("cat unix:///var/run/docker.sock", "/workspace", 30))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DENIED);
    }

    @Test
    void shouldRejectEscapeOrHostControlCommands() {
        assertThatThrownBy(() -> policy.validate("docker ps", "/workspace", 30))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DENIED);
        assertThatThrownBy(() -> policy.validate("kubectl get pods", "/workspace", 30))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DENIED);
        assertThatThrownBy(() -> policy.validate("nsenter --target 1 --mount sh", "/workspace", 30))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DENIED);
        assertThatThrownBy(() -> policy.validate("unshare --mount sh", "/workspace", 30))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DENIED);
        assertThatThrownBy(() -> policy.validate("chroot /workspace/rootfs sh", "/workspace", 30))
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
