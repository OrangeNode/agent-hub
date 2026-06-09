package auth

import (
	"crypto/subtle"
	"net/http"
	"strings"
)

func RequireToken(token string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path == "/health" {
				next.ServeHTTP(w, r)
				return
			}

			bearerToken, ok := parseBearerToken(r.Header.Get("Authorization"))
			if !ok || subtle.ConstantTimeCompare([]byte(bearerToken), []byte(token)) != 1 {
				http.Error(w, "未授权", http.StatusUnauthorized)
				return
			}

			next.ServeHTTP(w, r)
		})
	}
}

func parseBearerToken(header string) (string, bool) {
	scheme, value, ok := strings.Cut(strings.TrimSpace(header), " ")
	if !ok || !strings.EqualFold(scheme, "Bearer") {
		return "", false
	}

	value = strings.TrimSpace(value)
	if value == "" || strings.Contains(value, " ") {
		return "", false
	}
	return value, true
}
