//go:build windows

// Command uninstall removes ClinicQueue: shortcuts, the Installed apps entry, and the install
// folder. It lives inside the install folder, so the folder is deleted just after it exits.
package main

import (
	"os"
	"path/filepath"
	"strings"

	"clinicqueue-installer/internal/install"
	"clinicqueue-installer/internal/winutil"
)

const title = "Uninstall ClinicQueue"

func main() {
	self, err := os.Executable()
	if err != nil {
		winutil.MessageBox(title, "Could not find where ClinicQueue is installed:\n\n"+err.Error(), winutil.MBOK|winutil.MBIconError)
		os.Exit(1)
	}
	dir := filepath.Dir(self)
	local := os.Getenv("LOCALAPPDATA")
	exists := func(p string) bool { _, err := os.Stat(p); return err == nil }

	// Never delete a folder unless it is exactly the ClinicQueue install folder.
	if !install.SafeToDelete(dir, local, exists) {
		winutil.MessageBox(title, "This uninstaller is not in the ClinicQueue install folder, so it will not delete anything.\n\n"+dir, winutil.MBOK|winutil.MBIconError)
		os.Exit(1)
	}

	msg := "Remove ClinicQueue from this computer?\n\nThe queue is kept only while the program runs, so nothing else is lost."
	if winutil.MessageBox(title, msg, winutil.MBYesNo|winutil.MBIconQuestion) != winutil.IDYes {
		return
	}
	if winutil.IsLocked(install.JavaW(dir)) {
		winutil.MessageBox(title, "ClinicQueue is running. Quit it from its window, then run Uninstall again.", winutil.MBOK|winutil.MBIconError)
		os.Exit(1)
	}

	_ = winutil.RunPowerShell(install.RemoveShortcutsScript())
	_ = winutil.RunHidden("reg.exe", install.UnregisterArgs()...)

	if err := winutil.DeleteFolderSoon(dir); err != nil {
		winutil.MessageBox(title, "Could not remove the program folder:\n\n"+err.Error()+"\n\nYou can delete it yourself:\n"+strings.TrimSpace(dir), winutil.MBOK|winutil.MBIconError)
		os.Exit(1)
	}
	winutil.MessageBox(title, "ClinicQueue has been removed.", winutil.MBOK|winutil.MBIconInfo)
}
