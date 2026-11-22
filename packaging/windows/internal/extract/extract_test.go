package extract

import (
	"archive/zip"
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

func makeZip(t *testing.T, files map[string]string) []byte {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, content := range files {
		w, err := zw.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := w.Write([]byte(content)); err != nil {
			t.Fatal(err)
		}
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func read(t *testing.T, path string) string {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func TestExtractsNestedFilesAndFolders(t *testing.T) {
	dest := filepath.Join(t.TempDir(), "ClinicQueue")
	data := makeZip(t, map[string]string{
		"app/clinicqueue.jar":   "jar-bytes",
		"runtime/bin/javaw.exe": "exe-bytes",
		"runtime/lib/modules":   "modules",
		"README.txt":            "hello",
	})
	if err := ZipBytes(data, dest); err != nil {
		t.Fatal(err)
	}
	for name, want := range map[string]string{
		"app/clinicqueue.jar":   "jar-bytes",
		"runtime/bin/javaw.exe": "exe-bytes",
		"runtime/lib/modules":   "modules",
		"README.txt":            "hello",
	} {
		if got := read(t, filepath.Join(dest, filepath.FromSlash(name))); got != want {
			t.Errorf("%s: got %q, want %q", name, got, want)
		}
	}
}

func TestOverwritesExistingFilesOnReinstall(t *testing.T) {
	dest := t.TempDir()
	if err := os.WriteFile(filepath.Join(dest, "README.txt"), []byte("old version, longer than the new one"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := ZipBytes(makeZip(t, map[string]string{"README.txt": "new"}), dest); err != nil {
		t.Fatal(err)
	}
	if got := read(t, filepath.Join(dest, "README.txt")); got != "new" {
		t.Errorf("got %q, want %q", got, "new")
	}
}

func TestRejectsPathTraversal(t *testing.T) {
	for _, evil := range []string{"../evil.txt", "app/../../evil.txt"} {
		parent := t.TempDir()
		dest := filepath.Join(parent, "ClinicQueue")
		err := ZipBytes(makeZip(t, map[string]string{evil: "pwned"}), dest)
		if err == nil {
			t.Errorf("%q: expected an error, got none", evil)
		}
		if _, statErr := os.Stat(filepath.Join(parent, "evil.txt")); statErr == nil {
			t.Errorf("%q: a file was written outside the install folder", evil)
		}
	}
}

func TestRejectsGarbage(t *testing.T) {
	if err := ZipBytes([]byte("this is not a zip file"), t.TempDir()); err == nil {
		t.Fatal("expected an error for invalid data")
	}
}
