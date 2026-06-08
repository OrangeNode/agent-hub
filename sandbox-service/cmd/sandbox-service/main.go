package main

import (
	"log"
	"net/http"

	"agent-hub/sandbox-service/internal/auth"
	"agent-hub/sandbox-service/internal/config"
	"agent-hub/sandbox-service/internal/host"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatal(err)
	}

	handler := auth.RequireToken(cfg.InternalToken)(host.NewRouter())
	if err := http.ListenAndServe(cfg.ListenAddr, handler); err != nil {
		log.Fatal(err)
	}
}
