package agent

import (
	"context"
	"errors"
	"io"
	"os/exec"
	"strings"
	"sync"
	"time"
)

type ExecRequest struct {
	Command        string `json:"command"`
	WorkingDir     string `json:"workingDir"`
	TimeoutSeconds int    `json:"timeoutSeconds"`
}

type ExecEvent struct {
	Type    string `json:"type"`
	Content string `json:"content"`
}

type ExecResult struct {
	ExitCode        int           `json:"exitCode"`
	TimedOut        bool          `json:"timedOut"`
	Duration        time.Duration `json:"duration"`
	StdoutExcerpt   string        `json:"stdoutExcerpt"`
	StderrExcerpt   string        `json:"stderrExcerpt"`
	StdoutTruncated bool          `json:"stdoutTruncated"`
	StderrTruncated bool          `json:"stderrTruncated"`
	Error           string        `json:"error,omitempty"`
}

type Executor struct {
	maxExcerptBytes int
}

func NewExecutor(maxExcerptBytes int) *Executor {
	return &Executor{maxExcerptBytes: maxExcerptBytes}
}

func (e *Executor) Exec(ctx context.Context, req ExecRequest, emit func(ExecEvent)) ExecResult {
	startedAt := time.Now()
	result := ExecResult{ExitCode: -1}
	argv := strings.Fields(req.Command)
	if len(argv) == 0 {
		result.Duration = time.Since(startedAt)
		result.Error = "命令不能为空"
		return result
	}

	execCtx := ctx
	cancel := func() {}
	if req.TimeoutSeconds > 0 {
		execCtx, cancel = context.WithTimeout(ctx, time.Duration(req.TimeoutSeconds)*time.Second)
	}
	defer cancel()

	cmd := exec.CommandContext(execCtx, argv[0], argv[1:]...)
	if strings.TrimSpace(req.WorkingDir) != "" {
		cmd.Dir = req.WorkingDir
	}

	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return failedExecResult(startedAt, err)
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return failedExecResult(startedAt, err)
	}
	if err := cmd.Start(); err != nil {
		return failedExecResult(startedAt, err)
	}

	stdoutExcerpt := newExcerptCollector(e.maxExcerptBytes)
	stderrExcerpt := newExcerptCollector(e.maxExcerptBytes)
	var wg sync.WaitGroup
	wg.Add(2)
	go streamPipe(&wg, stdout, stdoutExcerpt, "stdout_delta", emit)
	go streamPipe(&wg, stderr, stderrExcerpt, "stderr_delta", emit)

	waitErr := cmd.Wait()
	wg.Wait()

	result.Duration = time.Since(startedAt)
	result.TimedOut = errors.Is(execCtx.Err(), context.DeadlineExceeded)
	result.StdoutExcerpt = stdoutExcerpt.String()
	result.StderrExcerpt = stderrExcerpt.String()
	result.StdoutTruncated = stdoutExcerpt.Truncated()
	result.StderrTruncated = stderrExcerpt.Truncated()
	if cmd.ProcessState != nil {
		result.ExitCode = cmd.ProcessState.ExitCode()
	}
	if waitErr != nil && !result.TimedOut {
		result.Error = waitErr.Error()
	}
	return result
}

func failedExecResult(startedAt time.Time, err error) ExecResult {
	return ExecResult{
		ExitCode: -1,
		Duration: time.Since(startedAt),
		Error:    err.Error(),
	}
}

func streamPipe(wg *sync.WaitGroup, reader io.Reader, excerpt *excerptCollector, eventType string, emit func(ExecEvent)) {
	defer wg.Done()
	buffer := make([]byte, 4096)
	for {
		n, err := reader.Read(buffer)
		if n > 0 {
			chunk := string(buffer[:n])
			excerpt.Write(buffer[:n])
			if emit != nil {
				emit(ExecEvent{Type: eventType, Content: chunk})
			}
		}
		if err != nil {
			return
		}
	}
}

type excerptCollector struct {
	maxBytes  int
	data      []byte
	truncated bool
}

func newExcerptCollector(maxBytes int) *excerptCollector {
	return &excerptCollector{maxBytes: maxBytes}
}

func (c *excerptCollector) Write(chunk []byte) {
	if c.maxBytes <= 0 {
		if len(chunk) > 0 {
			c.truncated = true
		}
		return
	}
	remaining := c.maxBytes - len(c.data)
	if remaining <= 0 {
		c.truncated = true
		return
	}
	if len(chunk) > remaining {
		c.data = append(c.data, chunk[:remaining]...)
		c.truncated = true
		return
	}
	c.data = append(c.data, chunk...)
}

func (c *excerptCollector) String() string {
	return string(c.data)
}

func (c *excerptCollector) Truncated() bool {
	return c.truncated
}
