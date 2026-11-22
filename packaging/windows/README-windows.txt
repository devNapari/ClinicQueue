ClinicQueue for Windows
=======================

Start it
  Open "ClinicQueue" from the desktop or the Start menu. ClinicQueue opens in its own window.
  Keep that window open while the clinic is open: closing it quits ClinicQueue.

  - Phones: the start screen shows an address such as http://192.168.1.20:8080/ for patients on the
    same Wi-Fi, with a Copy link button. If Windows asks about the firewall, choose "Allow" on
    private networks.
  - The window needs Microsoft Edge (part of Windows 10 and 11) or Google Chrome. If neither is
    found, ClinicQueue shows a small control window and uses your default web browser instead.

Staff PIN
  The staff PIN starts as 1234. To change it, create a text file named staff-pin.txt in this folder
  containing only the new PIN (4 to 32 characters, no spaces), then start ClinicQueue again.

Sample data
  This build starts with sample patients so you can try it. The queue is kept in memory only and is
  cleared whenever the program is closed.

Uninstall
  Settings > Apps > Installed apps > ClinicQueue > Uninstall,
  or run "Uninstall ClinicQueue.exe" from this folder.

This folder
  %LOCALAPPDATA%\Programs\ClinicQueue
