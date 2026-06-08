//go:build !unix && !windows

package agent

import "os/exec"

func configureCommand(cmd *exec.Cmd) {
}

func terminateCommand(cmd *exec.Cmd) {
	if cmd.Process != nil {
		_ = cmd.Process.Kill()
	}
}
