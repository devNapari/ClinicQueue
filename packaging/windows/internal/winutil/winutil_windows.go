//go:build windows

// Package winutil wraps the few Windows-only things the installer needs: message boxes,
// running helper programs without a console window, and PowerShell scripts.
package winutil

import (
	"fmt"
	"os"
	"os/exec"
	"strings"
	"syscall"
	"unsafe"
)

// MessageBox flags and results.
const (
	MBOK           = 0x0
	MBOKCancel     = 0x1
	MBYesNo        = 0x4
	MBIconError    = 0x10
	MBIconQuestion = 0x20
	MBIconInfo     = 0x40

	IDOK  = 1
	IDYes = 6
)

const (
	createNoWindow      = 0x08000000
	createNewProcessGrp = 0x00000200
	detachedProcess     = 0x00000008
)

var messageBoxW = syscall.NewLazyDLL("user32.dll").NewProc("MessageBoxW")

// MessageBox shows a native Windows dialog and returns the button the person chose.
func MessageBox(title, text string, flags uint32) int {
	t, _ := syscall.UTF16PtrFromString(title)
	x, _ := syscall.UTF16PtrFromString(text)
	r, _, _ := messageBoxW.Call(0, uintptr(unsafe.Pointer(x)), uintptr(unsafe.Pointer(t)), uintptr(flags))
	return int(r)
}

// RunHidden runs a program with no console window and returns its error output on failure.
func RunHidden(name string, args ...string) error {
	cmd := exec.Command(name, args...)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("%s failed: %w: %s", name, err, strings.TrimSpace(string(out)))
	}
	return nil
}

// RunPowerShell runs a script. It goes through a temporary .ps1 file (with a UTF-8 byte order
// mark so accented user names survive) because that avoids every command-line quoting problem.
func RunPowerShell(script string) error {
	f, err := os.CreateTemp("", "clinicqueue-*.ps1")
	if err != nil {
		return err
	}
	path := f.Name()
	defer os.Remove(path)
	if _, err := f.Write(append([]byte{0xEF, 0xBB, 0xBF}, []byte(script)...)); err != nil {
		f.Close()
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	return RunHidden("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", path)
}

// PSQuote quotes a value as a PowerShell single-quoted string.
func PSQuote(s string) string {
	return "'" + strings.ReplaceAll(s, "'", "''") + "'"
}

// IsLocked reports whether the file exists but cannot be opened for writing, which is how
// Windows treats a program that is currently running.
func IsLocked(path string) bool {
	if _, err := os.Stat(path); err != nil {
		return false
	}
	f, err := os.OpenFile(path, os.O_WRONLY, 0)
	if err != nil {
		return true
	}
	f.Close()
	return false
}

// StartDetached starts a program that keeps running after the installer exits.
func StartDetached(dir, exe string, args ...string) error {
	cmd := exec.Command(exe, args...)
	cmd.Dir = dir
	cmd.SysProcAttr = &syscall.SysProcAttr{CreationFlags: detachedProcess | createNewProcessGrp}
	return cmd.Start()
}

// DeleteFolderSoon removes a folder a few seconds from now, after this program has exited
// (a running program cannot delete its own file). The caller must have checked the path.
func DeleteFolderSoon(dir string) error {
	cmd := exec.Command("cmd.exe")
	cmd.SysProcAttr = &syscall.SysProcAttr{
		CmdLine:       fmt.Sprintf(`cmd.exe /c ping 127.0.0.1 -n 4 >nul & rmdir /s /q "%s"`, dir),
		HideWindow:    true,
		CreationFlags: createNoWindow | createNewProcessGrp,
	}
	return cmd.Start()
}
