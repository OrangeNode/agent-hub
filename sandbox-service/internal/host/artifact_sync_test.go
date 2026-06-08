package host

import (
	"testing"
)

func TestArtifactSyncerDiffPlansChangesAndMarkSyncedAdvancesState(t *testing.T) {
	syncer := NewArtifactSyncer("bucket")
	first, err := syncer.Diff("conversation-1", []Artifact{
		{RelativePath: "reports/result.txt", SHA256: "sha-1", Size: 12},
	})
	if err != nil {
		t.Fatalf("Diff(first) error = %v", err)
	}
	if len(first) != 1 {
		t.Fatalf("first changes = %d, want 1", len(first))
	}
	if first[0].Version != 1 {
		t.Fatalf("version = %d, want 1", first[0].Version)
	}
	if first[0].StorageKey != "sandbox-artifacts/conversation-1/sha-1/reports/result.txt" {
		t.Fatalf("storage key = %q", first[0].StorageKey)
	}

	second, err := syncer.Diff("conversation-1", []Artifact{
		{RelativePath: "reports/result.txt", SHA256: "sha-1", Size: 12},
	})
	if err != nil {
		t.Fatalf("Diff(second) error = %v", err)
	}
	if len(second) != 1 {
		t.Fatalf("uncommitted artifact should repeat, got %d", len(second))
	}
	if second[0].Version != 1 {
		t.Fatalf("uncommitted version = %d, want 1", second[0].Version)
	}

	if err := syncer.MarkSynced("conversation-1", first[0]); err != nil {
		t.Fatalf("MarkSynced() error = %v", err)
	}
	afterMark, err := syncer.Diff("conversation-1", []Artifact{
		{RelativePath: "reports/result.txt", SHA256: "sha-1", Size: 12},
	})
	if err != nil {
		t.Fatalf("Diff(afterMark) error = %v", err)
	}
	if len(afterMark) != 0 {
		t.Fatalf("synced artifacts should not repeat, got %d", len(afterMark))
	}

	third, err := syncer.Diff("conversation-1", []Artifact{
		{RelativePath: "reports/result.txt", SHA256: "sha-2", Size: 18},
	})
	if err != nil {
		t.Fatalf("Diff(third) error = %v", err)
	}
	if len(third) != 1 {
		t.Fatalf("third changes = %d, want 1", len(third))
	}
	if third[0].Version != 2 {
		t.Fatalf("changed version = %d, want 2", third[0].Version)
	}
	if third[0].StorageKey != "sandbox-artifacts/conversation-1/sha-2/reports/result.txt" {
		t.Fatalf("changed storage key = %q", third[0].StorageKey)
	}
}

func TestArtifactSyncerRejectsEscapingRelativePath(t *testing.T) {
	syncer := NewArtifactSyncer("bucket")

	_, err := syncer.Diff("conversation-1", []Artifact{
		{RelativePath: `..\secret.txt`, SHA256: "sha-1", Size: 1},
	})

	if err == nil {
		t.Fatal("expected escaping path to be rejected")
	}
}
