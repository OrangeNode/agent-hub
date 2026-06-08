package policy

import "testing"

func TestValidateCommandAllowsCommonCommands(t *testing.T) {
	policy := NewCommandPolicy(200, 60)
	req := CommandRequest{Command: "python3 main.py", WorkingDir: "/workspace", TimeoutSeconds: 30}
	if err := policy.Validate(req); err != nil {
		t.Fatalf("Validate() error = %v", err)
	}
}

func TestValidateCommandRejectsBlankCommand(t *testing.T) {
	policy := NewCommandPolicy(200, 60)
	for _, command := range []string{"", "   ", "\t\n"} {
		err := policy.Validate(CommandRequest{Command: command, WorkingDir: "/workspace", TimeoutSeconds: 30})
		if err == nil {
			t.Fatalf("expected %q to be rejected", command)
		}
	}
}

func TestValidateCommandRejectsNonPositiveTimeout(t *testing.T) {
	policy := NewCommandPolicy(200, 60)
	for _, timeoutSeconds := range []int{0, -1} {
		err := policy.Validate(CommandRequest{Command: "pwd", WorkingDir: "/workspace", TimeoutSeconds: timeoutSeconds})
		if err == nil {
			t.Fatalf("expected timeout %d to be rejected", timeoutSeconds)
		}
	}
}

func TestValidateCommandRejectsTimeoutOverLimit(t *testing.T) {
	policy := NewCommandPolicy(200, 60)
	err := policy.Validate(CommandRequest{Command: "pwd", WorkingDir: "/workspace", TimeoutSeconds: 61})
	if err == nil {
		t.Fatal("expected timeout over maxTimeout to be rejected")
	}
}

func TestValidateCommandRejectsDangerousKeywords(t *testing.T) {
	policy := NewCommandPolicy(200, 60)
	for _, command := range []string{"sudo whoami", "systemctl restart ssh", "mount /dev/sda /tmp/x"} {
		err := policy.Validate(CommandRequest{Command: command, WorkingDir: "/workspace", TimeoutSeconds: 30})
		if err == nil {
			t.Fatalf("expected %q to be rejected", command)
		}
	}
}

func TestValidateCommandRejectsDangerousKeywordsWithShellSeparators(t *testing.T) {
	policy := NewCommandPolicy(200, 60)
	for _, command := range []string{"sudo\twhoami", "reboot; echo ok", "systemctl&&true", "service\nssh restart"} {
		err := policy.Validate(CommandRequest{Command: command, WorkingDir: "/workspace", TimeoutSeconds: 30})
		if err == nil {
			t.Fatalf("expected %q to be rejected", command)
		}
	}
}

func TestValidateCommandRejectsDangerousPaths(t *testing.T) {
	policy := NewCommandPolicy(200, 60)
	for _, command := range []string{"rm -rf /", "cat /etc/passwd", "ls /proc"} {
		err := policy.Validate(CommandRequest{Command: command, WorkingDir: "/workspace", TimeoutSeconds: 30})
		if err == nil {
			t.Fatalf("expected %q to be rejected", command)
		}
	}
}

func TestValidateCommandRejectsOutOfWorkspaceWorkingDir(t *testing.T) {
	policy := NewCommandPolicy(200, 60)
	for _, workingDir := range []string{"/tmp", "/workspace2", "/workspace/../tmp"} {
		err := policy.Validate(CommandRequest{Command: "pwd", WorkingDir: workingDir, TimeoutSeconds: 30})
		if err == nil {
			t.Fatalf("expected %q working dir to be rejected", workingDir)
		}
	}
}
