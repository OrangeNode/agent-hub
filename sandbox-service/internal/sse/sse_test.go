package sse

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestWriteEventWritesStdoutDeltaAndFlushes(t *testing.T) {
	writer := &flushRecorder{ResponseRecorder: httptest.NewRecorder()}

	WriteEvent(writer, "stdout_delta", map[string]string{"content": "hello"})

	body := writer.Body.String()
	if !strings.Contains(body, "event: stdout_delta\n") {
		t.Fatalf("missing event line, body = %q", body)
	}
	if !strings.Contains(body, `data: {"content":"hello"}`+"\n\n") {
		t.Fatalf("missing JSON data line, body = %q", body)
	}
	if writer.flushes != 1 {
		t.Fatalf("flushes = %d, want 1", writer.flushes)
	}
}

type flushRecorder struct {
	*httptest.ResponseRecorder
	flushes int
}

func (r *flushRecorder) Flush() {
	r.flushes++
}

var _ http.Flusher = (*flushRecorder)(nil)
