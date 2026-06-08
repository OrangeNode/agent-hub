package workspace

import "testing"

func TestResolveAllowsWorkspacePath(t *testing.T) {
	p := NewPathPolicy("/workspace")
	path, err := p.Resolve("/workspace/input/data.csv")
	if err != nil {
		t.Fatalf("Resolve() error = %v", err)
	}
	if path != "/workspace/input/data.csv" {
		t.Fatalf("path = %q", path)
	}
}

func TestResolveRejectsTraversal(t *testing.T) {
	p := NewPathPolicy("/workspace")
	if _, err := p.Resolve("/workspace/../etc/passwd"); err == nil {
		t.Fatal("expected traversal to be rejected")
	}
}

func TestResolveInputOnlyRejectsArtifacts(t *testing.T) {
	p := NewPathPolicy("/workspace")
	if _, err := p.ResolveInput("/workspace/artifacts/report.csv"); err == nil {
		t.Fatal("expected non-input target to be rejected")
	}
}
