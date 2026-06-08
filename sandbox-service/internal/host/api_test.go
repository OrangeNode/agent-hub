package host

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestNewRouterHealthReturnsUp(t *testing.T) {
	res := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/health", nil)

	NewRouter().ServeHTTP(res, req)

	if res.Code != http.StatusOK {
		t.Fatalf("code = %d", res.Code)
	}

	var body map[string]string
	if err := json.NewDecoder(res.Body).Decode(&body); err != nil {
		t.Fatalf("Decode() error = %v", err)
	}
	if body["status"] != "UP" {
		t.Fatalf("status = %q", body["status"])
	}
}
