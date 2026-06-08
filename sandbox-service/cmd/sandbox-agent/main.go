package main

import (
	"log"
	"net/http"
	"os"

	"agent-hub/sandbox-service/internal/agent"
)

func main() {
	workspace := getenvDefault("SANDBOX_WORKSPACE", "/workspace")
	addr := getenvDefault("SANDBOX_AGENT_ADDR", ":8081")

	server := agent.NewServer(agent.NewExecutor(64*1024), agent.NewFileService(workspace))
	if err := http.ListenAndServe(addr, server.Router()); err != nil {
		log.Fatal(err)
	}
}

func getenvDefault(key string, fallback string) string {
	value := os.Getenv(key)
	if value == "" {
		return fallback
	}
	return value
}
