//go:build unix

package agent

import (
	"context"
	"strings"
	"testing"
)

func TestExecutorStreamsStdoutAndStderr(t *testing.T) {
	executor := NewExecutor(1024)
	var events []ExecEvent

	result := executor.Exec(context.Background(), ExecRequest{
		Command:        "printf hello",
		WorkingDir:     "",
		TimeoutSeconds: 5,
	}, func(event ExecEvent) {
		events = append(events, event)
	})

	if result.ExitCode != 0 {
		t.Fatalf("ExitCode = %d, want 0, stderr = %q", result.ExitCode, result.StderrExcerpt)
	}
	if result.StdoutExcerpt != "hello" {
		t.Fatalf("StdoutExcerpt = %q, want hello", result.StdoutExcerpt)
	}
	if !hasEvent(events, "stdout_delta", "hello") {
		t.Fatalf("expected stdout_delta hello event, got %#v", events)
	}
}

func TestExecutorCapturesStderr(t *testing.T) {
	executor := NewExecutor(1024)
	var events []ExecEvent

	result := executor.Exec(context.Background(), ExecRequest{
		Command:        "ls /definitely_missing_agent_hub_test_path",
		WorkingDir:     "",
		TimeoutSeconds: 5,
	}, func(event ExecEvent) {
		events = append(events, event)
	})

	if !strings.Contains(result.StderrExcerpt, "definitely_missing_agent_hub_test_path") {
		t.Fatalf("StderrExcerpt = %q, want missing path message", result.StderrExcerpt)
	}
	if !hasEvent(events, "stderr_delta", "definitely_missing_agent_hub_test_path") {
		t.Fatalf("expected stderr_delta missing path event, got %#v", events)
	}
}

func TestExecutorRunsShellComposition(t *testing.T) {
	executor := NewExecutor(1024)

	result := executor.Exec(context.Background(), ExecRequest{
		Command:        "cat <<'EOF' > output.txt\n3 1 2\nEOF\ncat output.txt",
		WorkingDir:     t.TempDir(),
		TimeoutSeconds: 5,
	}, nil)

	if result.ExitCode != 0 {
		t.Fatalf("ExitCode = %d, want 0, stderr = %q", result.ExitCode, result.StderrExcerpt)
	}
	if result.StdoutExcerpt != "3 1 2\n" {
		t.Fatalf("StdoutExcerpt = %q, want generated file content", result.StdoutExcerpt)
	}
}

func TestExecutorTimeout(t *testing.T) {
	executor := NewExecutor(1024)

	result := executor.Exec(context.Background(), ExecRequest{
		Command:        "sleep 2",
		WorkingDir:     "",
		TimeoutSeconds: 1,
	}, nil)

	if !result.TimedOut {
		t.Fatalf("TimedOut = false, want true")
	}
	if result.Duration <= 0 {
		t.Fatalf("Duration = %s, want positive", result.Duration)
	}
}

func TestExecutorEmptyCommandFails(t *testing.T) {
	executor := NewExecutor(1024)

	result := executor.Exec(context.Background(), ExecRequest{
		Command:        "   ",
		TimeoutSeconds: 1,
	}, nil)

	if result.ExitCode == 0 {
		t.Fatalf("ExitCode = 0, want failure")
	}
	if result.Error == "" {
		t.Fatal("expected error message")
	}
}

func TestExecutorTruncatesExcerpts(t *testing.T) {
	executor := NewExecutor(4)

	result := executor.Exec(context.Background(), ExecRequest{
		Command:        "printf hello",
		TimeoutSeconds: 5,
	}, nil)

	if result.StdoutExcerpt != "hell" {
		t.Fatalf("StdoutExcerpt = %q, want hell", result.StdoutExcerpt)
	}
	if !result.StdoutTruncated {
		t.Fatal("StdoutTruncated = false, want true")
	}
}

func hasEvent(events []ExecEvent, eventType string, content string) bool {
	for _, event := range events {
		if event.Type == eventType && strings.Contains(event.Content, content) {
			return true
		}
	}
	return false
}
