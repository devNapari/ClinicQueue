// Package install holds the installer's decisions: where files go, what shortcuts and registry
// entries to create, and when it is safe to delete a folder. It has no Windows-only code, so
// it is unit-tested on any machine. Paths are Windows paths on purpose.
package install

import (
	"fmt"
	"strings"
)

const (
	AppName   = "ClinicQueue"
	Publisher = "ClinicQueue"
	Version   = "1.1.0"

	uninstallKey = `HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\ClinicQueue`
)

// Dir is the per-user install folder. It needs no administrator rights.
func Dir(localAppData string) string {
	return strings.TrimRight(localAppData, `\/`) + `\Programs\ClinicQueue`
}

// Paths of the files the shortcuts and the uninstaller rely on, inside the install folder.
func JavaW(dir string) string       { return dir + `\runtime\bin\javaw.exe` }
func Jar(dir string) string         { return dir + `\app\clinicqueue.jar` }
func Icon(dir string) string        { return dir + `\clinicqueue.ico` }
func Uninstaller(dir string) string { return dir + `\Uninstall ClinicQueue.exe` }

// looksInstalled is true when dir contains the files of a real ClinicQueue install.
func LooksInstalled(exists func(path string) bool, dir string) bool {
	return exists(JavaW(dir)) && exists(Jar(dir))
}

// psq quotes a value as a PowerShell single-quoted string.
func psq(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }

// ShortcutScript creates the Start menu and desktop shortcuts. Folders are asked of Windows
// (not guessed) because OneDrive and company setups move the desktop.
func ShortcutScript(dir string) string {
	args := fmt.Sprintf(`-jar "%s" --desktop`, Jar(dir))
	return strings.Join([]string{
		`$ErrorActionPreference = 'Stop'`,
		`$shell = New-Object -ComObject WScript.Shell`,
		`function New-Link($path) {`,
		`  $l = $shell.CreateShortcut($path)`,
		`  $l.TargetPath = ` + psq(JavaW(dir)),
		`  $l.Arguments = ` + psq(args),
		`  $l.WorkingDirectory = ` + psq(dir),
		`  $l.IconLocation = ` + psq(Icon(dir)),
		`  $l.Description = 'Virtual queue for clinics'`,
		`  $l.Save()`,
		`}`,
		`New-Link (Join-Path ([Environment]::GetFolderPath('Programs')) 'ClinicQueue.lnk')`,
		`New-Link (Join-Path ([Environment]::GetFolderPath('Desktop')) 'ClinicQueue.lnk')`,
		``,
	}, "\r\n")
}

// RemoveShortcutsScript deletes the shortcuts made by ShortcutScript. Missing ones are fine.
func RemoveShortcutsScript() string {
	return strings.Join([]string{
		`$ErrorActionPreference = 'SilentlyContinue'`,
		`Remove-Item -LiteralPath (Join-Path ([Environment]::GetFolderPath('Programs')) 'ClinicQueue.lnk') -Force`,
		`Remove-Item -LiteralPath (Join-Path ([Environment]::GetFolderPath('Desktop')) 'ClinicQueue.lnk') -Force`,
		``,
	}, "\r\n")
}

// RegisterArgs returns the reg.exe argument lists that add ClinicQueue to
// Settings > Apps > Installed apps, so it can be uninstalled like any other program.
func RegisterArgs(dir string) [][]string {
	add := func(name, kind, value string) []string {
		return []string{"add", uninstallKey, "/v", name, "/t", kind, "/d", value, "/f"}
	}
	return [][]string{
		add("DisplayName", "REG_SZ", AppName),
		add("DisplayVersion", "REG_SZ", Version),
		add("Publisher", "REG_SZ", Publisher),
		add("InstallLocation", "REG_SZ", dir),
		add("DisplayIcon", "REG_SZ", Icon(dir)),
		add("UninstallString", "REG_SZ", `"`+Uninstaller(dir)+`"`),
		add("NoModify", "REG_DWORD", "1"),
		add("NoRepair", "REG_DWORD", "1"),
	}
}

// UnregisterArgs removes the Installed apps entry.
func UnregisterArgs() []string {
	return []string{"delete", uninstallKey, "/f"}
}

// SafeToDelete is the last check before the uninstaller removes a whole folder: it must be
// exactly <LocalAppData>\Programs\ClinicQueue (case-insensitive) and contain a real install.
func SafeToDelete(dir, localAppData string, exists func(path string) bool) bool {
	norm := func(p string) string { return strings.ToLower(strings.TrimRight(strings.ReplaceAll(p, "/", `\`), `\`)) }
	if localAppData == "" || dir == "" {
		return false
	}
	if norm(dir) != norm(Dir(localAppData)) {
		return false
	}
	return LooksInstalled(exists, dir)
}
