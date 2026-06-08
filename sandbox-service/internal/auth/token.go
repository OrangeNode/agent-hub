package auth

import (
	"net/http"
)

func RequireToken(token string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Header.Get("Authorization") != "Bearer "+token {
				http.Error(w, "未授权", http.StatusUnauthorized)
				return
			}

			next.ServeHTTP(w, r)
		})
	}
}
