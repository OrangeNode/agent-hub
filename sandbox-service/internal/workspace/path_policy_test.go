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

func TestResolveRelativePathUsesWorkspaceRoot(t *testing.T) {
	p := NewPathPolicy("/workspace")
	path, err := p.Resolve("reports/out.txt")
	if err != nil {
		t.Fatalf("Resolve() error = %v", err)
	}
	if path != "/workspace/reports/out.txt" {
		t.Fatalf("path = %q", path)
	}
}

func TestResolveEmptyPathUsesWorkspaceRoot(t *testing.T) {
	p := NewPathPolicy("/workspace")
	path, err := p.Resolve("")
	if err != nil {
		t.Fatalf("Resolve() error = %v", err)
	}
	if path != "/workspace" {
		t.Fatalf("path = %q", path)
	}
}

func TestResolveNormalizesBackslash(t *testing.T) {
	p := NewPathPolicy("/workspace")
	path, err := p.Resolve(`\workspace\input\data.csv`)
	if err != nil {
		t.Fatalf("Resolve() error = %v", err)
	}
	if path != "/workspace/input/data.csv" {
		t.Fatalf("path = %q", path)
	}
}

func TestResolveAllowsDocxTempWorkPath(t *testing.T) {
	p := NewPathPolicy("/workspace")
	path, err := p.Resolve("/tmp/docx-work/Program.cs")
	if err != nil {
		t.Fatalf("Resolve() error = %v", err)
	}
	if path != "/tmp/docx-work/Program.cs" {
		t.Fatalf("path = %q", path)
	}
}

func TestResolveRejectsOtherTempPath(t *testing.T) {
	p := NewPathPolicy("/workspace")
	if _, err := p.Resolve("/tmp/other/Program.cs"); err == nil {
		t.Fatal("expected other temp path to be rejected")
	}
}

func TestResolveRejectsTraversal(t *testing.T) {
	p := NewPathPolicy("/workspace")
	if _, err := p.Resolve("/workspace/../etc/passwd"); err == nil {
		t.Fatal("expected traversal to be rejected")
	}
	if _, err := p.Resolve("../secret.txt"); err == nil {
		t.Fatal("expected relative traversal to be rejected")
	}
}

func TestResolveInputAllowsInputDirectory(t *testing.T) {
	p := NewPathPolicy("/workspace")
	for _, target := range []string{"/workspace/input", "/workspace/input/data.csv"} {
		path, err := p.ResolveInput(target)
		if err != nil {
			t.Fatalf("ResolveInput(%q) error = %v", target, err)
		}
		if path != target {
			t.Fatalf("path = %q", path)
		}
	}
}

func TestResolveInputRelativePathUsesInputDirectory(t *testing.T) {
	p := NewPathPolicy("/workspace")
	path, err := p.ResolveInput("data.csv")
	if err != nil {
		t.Fatalf("ResolveInput() error = %v", err)
	}
	if path != "/workspace/input/data.csv" {
		t.Fatalf("path = %q", path)
	}
}

func TestResolveInputRejectsEmptyPath(t *testing.T) {
	p := NewPathPolicy("/workspace")
	if _, err := p.ResolveInput(""); err == nil {
		t.Fatal("expected empty upload path to be rejected")
	}
}

func TestResolveInputOnlyRejectsArtifacts(t *testing.T) {
	p := NewPathPolicy("/workspace")
	if _, err := p.ResolveInput("/workspace/artifacts/report.csv"); err == nil {
		t.Fatal("expected non-input target to be rejected")
	}
}
