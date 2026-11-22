//go:build windows

// Command setup is the ClinicQueue installer. It carries the app and a Java runtime inside
// itself, installs for the current user (no administrator rights), adds shortcuts, and
// registers an uninstaller under Settings > Apps.
package main

import (
	_ "embed"
	"fmt"
	"os"
	"strings"

	"clinicqueue-installer/internal/extract"
	"clinicqueue-installer/internal/install"
	"clinicqueue-installer/internal/winutil"
)

//go:embed payload.zip
var payload []byte

const title = "ClinicQueue Setup"

func main() {
	silent := false
	for _, a := range os.Args[1:] {
		switch strings.ToLower(a) {
		case "/s", "--silent":
			silent = true
		default:
			fail(silent, "Unknown option: "+a+"\n\nUse /S for a silent install.")
		}
	}

	local := os.Getenv("LOCALAPPDATA")
	if local == "" {
		fail(silent, "Windows did not say where to install per-user programs (LOCALAPPDATA is not set).")
	}
	dir := install.Dir(local)

	if !silent {
		msg := fmt.Sprintf("Install ClinicQueue %s?\n\nIt will be installed for your account in:\n%s\n\nNo administrator rights are needed.", install.Version, dir)
		if winutil.MessageBox(title, msg, winutil.MBOKCancel|winutil.MBIconQuestion) != winutil.IDOK {
			return
		}
	}

	if winutil.IsLocked(install.JavaW(dir)) {
		fail(silent, "ClinicQueue is running. Quit it from its window, then run Setup again.")
	}

	if err := extract.ZipBytes(payload, dir); err != nil {
		fail(silent, "Could not copy the program files:\n\n"+err.Error())
	}
	if err := winutil.RunPowerShell(install.ShortcutScript(dir)); err != nil {
		fail(silent, "The files were copied, but the shortcuts could not be created:\n\n"+err.Error())
	}
	for _, args := range install.RegisterArgs(dir) {
		if err := winutil.RunHidden("reg.exe", args...); err != nil {
			fail(silent, "The program was installed, but could not be listed under Installed apps:\n\n"+err.Error())
		}
	}

	if silent {
		return
	}
	done := "ClinicQueue is installed.\n\nStart it now? You can also open it from the desktop or Start menu shortcut."
	if winutil.MessageBox(title, done, winutil.MBYesNo|winutil.MBIconInfo) == winutil.IDYes {
		if err := winutil.StartDetached(dir, install.JavaW(dir), "-jar", install.Jar(dir), "--desktop"); err != nil {
			winutil.MessageBox(title, "Could not start ClinicQueue:\n\n"+err.Error(), winutil.MBOK|winutil.MBIconError)
		}
	}
}

func fail(silent bool, message string) {
	if silent {
		fmt.Fprintln(os.Stderr, message)
	} else {
		winutil.MessageBox(title, message, winutil.MBOK|winutil.MBIconError)
	}
	os.Exit(1)
}
