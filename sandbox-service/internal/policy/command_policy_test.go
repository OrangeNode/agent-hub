package policy

import "testing"

func TestValidateCommandAllowsCommonCommands(t *testing.T) {
	policy := NewCommandPolicy(200, 60)
	for _, command := range []string{
		"python3 script.py",
		"ls /workspace",
		"cat /workspace/output/a.txt",
		"sed -f/workspace/scripts/filter.sed /workspace/input.txt",
		"grep -f/workspace/patterns /workspace/input.txt",
		"npm --prefix=/workspace/app install",
		`python3 -c "print(1)"`,
		`bash -lc "python3 main.py && cat /workspace/artifacts/output.txt"`,
		"cat <<'EOF' > /workspace/artifacts/input.txt\n3 1 2\nEOF",
	} {
		req := CommandRequest{Command: command, WorkingDir: "/workspace", TimeoutSeconds: 30}
		if err := policy.Validate(req); err != nil {
			t.Fatalf("Validate(%q) error = %v", command, err)
		}
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

func TestValidateCommandRejectsCommandOverLengthLimit(t *testing.T) {
	policy := NewCommandPolicy(3, 60)
	err := policy.Validate(CommandRequest{Command: "pwd -L", WorkingDir: "/workspace", TimeoutSeconds: 30})
	if err == nil {
		t.Fatal("expected command over maxCommandLength to be rejected")
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
	for _, command := range []string{"sudo whoami", "systemctl restart ssh", "mount /dev/sda /tmp/x", "s'u'do whoami", "sys''ctl -a"} {
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
	for _, command := range []string{
		"rm -rf /",
		"cat /etc/passwd",
		`cat \etc\passwd`,
		"cat /e'tc'/passwd",
		"ls /proc",
		"go build -o=/etc/app",
		"mvn -Dmaven.repo.local=/root/.m2",
		"npm --prefix=/var/run install",
		"go build -o/tmp/app",
		"javac -d/tmp/classes",
		"sed -f/etc/passwd",
	} {
		err := policy.Validate(CommandRequest{Command: command, WorkingDir: "/workspace", TimeoutSeconds: 30})
		if err == nil {
			t.Fatalf("expected %q to be rejected", command)
		}
	}
}

func TestValidateCommandRejectsRelativePathTraversal(t *testing.T) {
	policy := NewCommandPolicy(200, 60)
	for _, command := range []string{
		"cat ../etc/passwd",
		"find ..",
		"cp ../etc/passwd /workspace/x",
		"cat subdir/../../etc/passwd",
		"sed -f../etc/passwd",
		"grep -f../patterns",
		"npm --prefix=../out install",
		"grep -f=../x",
	} {
		err := policy.Validate(CommandRequest{Command: command, WorkingDir: "/workspace", TimeoutSeconds: 30})
		if err == nil {
			t.Fatalf("expected %q to be rejected", command)
		}
	}
}

func TestValidateCommandRejectsPathLikeEntrypoint(t *testing.T) {
	policy := NewCommandPolicy(200, 60)
	for _, command := range []string{"/workspace/python3 script.py", "/tmp/npm install", `..\node app.js`} {
		err := policy.Validate(CommandRequest{Command: command, WorkingDir: "/workspace", TimeoutSeconds: 30})
		if err == nil {
			t.Fatalf("expected %q to be rejected", command)
		}
	}
}

func TestValidateCommandRejectsUnknownShellEntrypoint(t *testing.T) {
	policy := NewCommandPolicy(200, 60)
	for _, command := range []string{"zsh -c ls", "fish -c ls"} {
		err := policy.Validate(CommandRequest{Command: command, WorkingDir: "/workspace", TimeoutSeconds: 30})
		if err == nil {
			t.Fatalf("expected %q to be rejected", command)
		}
	}
}

func TestValidateCommandRejectsUnknownCommand(t *testing.T) {
	policy := NewCommandPolicy(200, 60)
	err := policy.Validate(CommandRequest{Command: "curl https://example.com", WorkingDir: "/workspace", TimeoutSeconds: 30})
	if err == nil {
		t.Fatal("expected command outside allowlist to be rejected")
	}
}

func TestValidateCommandRejectsEntrypointWithDifferentCase(t *testing.T) {
	policy := NewCommandPolicy(200, 60)
	for _, command := range []string{"Python3 script.py", "NPM install"} {
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
