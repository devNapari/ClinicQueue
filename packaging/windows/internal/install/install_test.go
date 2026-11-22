package install

import (
	"strings"
	"testing"
)

const local = `C:\Users\Ama Mensah\AppData\Local`

func yes(string) bool { return true }
func no(string) bool  { return false }

func TestDirIsPerUserAndNeedsNoAdmin(t *testing.T) {
	want := `C:\Users\Ama Mensah\AppData\Local\Programs\ClinicQueue`
	if got := Dir(local); got != want {
		t.Errorf("got %q, want %q", got, want)
	}
	if got := Dir(local + `\`); got != want {
		t.Errorf("trailing slash: got %q, want %q", got, want)
	}
}

func TestShortcutScriptPointsAtBundledJava(t *testing.T) {
	dir := Dir(local)
	s := ShortcutScript(dir)
	for _, want := range []string{
		`$l.TargetPath = 'C:\Users\Ama Mensah\AppData\Local\Programs\ClinicQueue\runtime\bin\javaw.exe'`,
		`-jar "C:\Users\Ama Mensah\AppData\Local\Programs\ClinicQueue\app\clinicqueue.jar" --desktop`,
		`$l.WorkingDirectory = 'C:\Users\Ama Mensah\AppData\Local\Programs\ClinicQueue'`,
		`clinicqueue.ico`,
		`GetFolderPath('Programs')`,
		`GetFolderPath('Desktop')`,
	} {
		if !strings.Contains(s, want) {
			t.Errorf("script is missing %q\n%s", want, s)
		}
	}
}

func TestShortcutScriptSurvivesApostropheInUserName(t *testing.T) {
	dir := Dir(`C:\Users\O'Neil\AppData\Local`)
	s := ShortcutScript(dir)
	if !strings.Contains(s, `'C:\Users\O''Neil\AppData\Local\Programs\ClinicQueue\runtime\bin\javaw.exe'`) {
		t.Errorf("apostrophe was not escaped for PowerShell:\n%s", s)
	}
}

func TestRegisterArgsMakeItUninstallable(t *testing.T) {
	dir := Dir(local)
	joined := ""
	for _, a := range RegisterArgs(dir) {
		if a[0] != "add" || a[len(a)-1] != "/f" {
			t.Errorf("unexpected reg command: %v", a)
		}
		joined += strings.Join(a, " ") + "\n"
	}
	for _, want := range []string{
		`DisplayName /t REG_SZ /d ClinicQueue`,
		`UninstallString /t REG_SZ /d "C:\Users\Ama Mensah\AppData\Local\Programs\ClinicQueue\Uninstall ClinicQueue.exe"`,
		`InstallLocation`,
		`HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\ClinicQueue`,
	} {
		if !strings.Contains(joined, want) {
			t.Errorf("missing %q in:\n%s", want, joined)
		}
	}
	if strings.Contains(joined, "HKLM") {
		t.Error("must not write to HKLM (needs admin)")
	}
}

func TestSafeToDeleteOnlyTheRealInstallFolder(t *testing.T) {
	dir := Dir(local)
	if !SafeToDelete(dir, local, yes) {
		t.Error("the real install folder should be deletable")
	}
	if !SafeToDelete(strings.ToUpper(dir), local, yes) {
		t.Error("comparison should ignore case")
	}
	for _, bad := range []string{
		``, `C:\`, `C:\Users`, local, local + `\Programs`,
		`C:\Users\Ama Mensah\Documents`, `C:\Users\Ama Mensah\AppData\Local\Programs\ClinicQueue\runtime`,
		`C:\Users\Ama Mensah\AppData\Local\Programs\Other`, `D:\ClinicQueue`,
	} {
		if SafeToDelete(bad, local, yes) {
			t.Errorf("%q must never be deleted", bad)
		}
	}
	if SafeToDelete(dir, local, no) {
		t.Error("a folder without the app inside must not be deleted")
	}
	if SafeToDelete(dir, "", yes) {
		t.Error("unknown LocalAppData must not allow deletion")
	}
}
