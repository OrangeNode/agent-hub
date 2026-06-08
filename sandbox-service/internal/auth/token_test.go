package auth

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestRequireTokenAllowsMatchingBearerToken(t *testing.T) {
	called := false
	handler := RequireToken("secret")(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
		w.WriteHeader(http.StatusNoContent)
	}))
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	req.Header.Set("Authorization", "Bearer secret")
	res := httptest.NewRecorder()

	handler.ServeHTTP(res, req)

	if res.Code != http.StatusNoContent || !called {
		t.Fatalf("expected handler to run, code=%d called=%v", res.Code, called)
	}
}

func TestRequireTokenAllowsLowercaseBearerScheme(t *testing.T) {
	called := false
	handler := RequireToken("secret")(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
		w.WriteHeader(http.StatusNoContent)
	}))
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	req.Header.Set("Authorization", "bearer secret")
	res := httptest.NewRecorder()

	handler.ServeHTTP(res, req)

	if res.Code != http.StatusNoContent || !called {
		t.Fatalf("expected handler to run, code=%d called=%v", res.Code, called)
	}
}

func TestRequireTokenRejectsMissingToken(t *testing.T) {
	handler := RequireToken("secret")(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Fatal("handler should not run")
	}))
	res := httptest.NewRecorder()

	handler.ServeHTTP(res, httptest.NewRequest(http.MethodGet, "/health", nil))

	if res.Code != http.StatusUnauthorized {
		t.Fatalf("code = %d", res.Code)
	}
	if res.Body.String() != "未授权\n" {
		t.Fatalf("body = %q", res.Body.String())
	}
}

func TestRequireTokenRejectsWrongToken(t *testing.T) {
	handler := RequireToken("secret")(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Fatal("handler should not run")
	}))
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	req.Header.Set("Authorization", "Bearer wrong")
	res := httptest.NewRecorder()

	handler.ServeHTTP(res, req)

	if res.Code != http.StatusUnauthorized {
		t.Fatalf("code = %d", res.Code)
	}
	if res.Body.String() != "未授权\n" {
		t.Fatalf("body = %q", res.Body.String())
	}
}

func TestRequireTokenRejectsMalformedAuthorization(t *testing.T) {
	handler := RequireToken("secret")(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Fatal("handler should not run")
	}))
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	req.Header.Set("Authorization", "Basic secret")
	res := httptest.NewRecorder()

	handler.ServeHTTP(res, req)

	if res.Code != http.StatusUnauthorized {
		t.Fatalf("code = %d", res.Code)
	}
	if res.Body.String() != "未授权\n" {
		t.Fatalf("body = %q", res.Body.String())
	}
}
