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

Staff access
  For a quick demo, the shared PIN starts as 1234 and can access every queue. To change this legacy
  demo PIN, create a text file named staff-pin.txt in this folder containing only the new PIN
  (4 to 32 characters, no spaces).

  For department access, configure these four different Windows user or system environment
  variables before starting ClinicQueue:
    CONSULTATION_PIN  - general consultation providers
    LAB_PIN           - laboratory providers
    PHARMACY_PIN      - pharmacy/dispensary providers
    NURSE_PIN         - register walk-ins only
  If you configure any role PIN, configure all four. Provider PINs can manage only their service.
  The nurse PIN can register an on-site walk-in in any service but cannot call or start service.

Walk-ins and reminders
  Nurses mark walk-ins as already at the hospital. Phone number is optional; patients without one
  receive a ticket code from the nurse and cannot receive texts. Remotely joined patients must tap
  "I'm at the hospital" on their ticket before a provider can call them.
  Arrival reminders are simulated in the app's message log; real SMS delivery is not configured.
  The one-hour reminder uses an estimate, so the actual call time may change with the queue.

Sample data
  This build starts with sample patients so you can try it. The queue is kept in memory only and is
  cleared whenever the program is closed.

Uninstall
  Settings > Apps > Installed apps > ClinicQueue > Uninstall,
  or run "Uninstall ClinicQueue.exe" from this folder.

This folder
  %LOCALAPPDATA%\Programs\ClinicQueue
