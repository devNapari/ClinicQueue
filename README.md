# ClinicQueue

A virtual queue for clinics. Patients can join remotely, receive an arrival reminder when their predicted wait is about an hour, and check in when they reach the hospital. Doctors, lab technicians, and dispensary staff manage their own service queues; nurses can register patients who walk in. Providers call only checked-in patients. The wait estimate learns from real service times.

Written in plain Java (JDK only): no Maven, no framework, no internet needed to build or run.

## Run it

Requires JDK 17 or newer (built and tested on 21).

```bash
./build.sh
java -jar out/clinicqueue.jar
```

Open http://localhost:8080/ . Patients can join a service queue or return to an existing ticket. Clinic staff sign in with a PIN:

- **Department PINs**: `CONSULTATION_PIN`, `LAB_PIN`, and `PHARMACY_PIN` each open only that provider's queue. `NURSE_PIN` opens a walk-in registration form for all services, without queue-management actions.
- If none of those four variables are set, the app uses legacy shared-PIN demo mode (`STAFF_PIN`, default `1234`), which can access every service. Do not use this shared demo PIN mode for clinic operations.

Settings: environment variables `PORT` (default 8080), `SEED_DEMO=false`, `STAFF_PIN` (legacy mode), or all four unique role PINs (`CONSULTATION_PIN`, `LAB_PIN`, `PHARMACY_PIN`, `NURSE_PIN`). If you set any role PIN, all four must be set and different. In legacy mode, `staff-pin.txt` in the folder you start it from (or next to the installed app) sets the shared PIN. Options include `--port=N`, `--no-demo`, and `--desktop` (opens ClinicQueue in its own window, see below).

Remote tickets stay in `WAITING` when the arrival reminder is sent. The reminder asks patients to come to the hospital; it does not mean a provider is ready to see them. Patients tap **I'm at the hospital** on their ticket page to check in. A provider's **Call next patient** selects the next checked-in patient and starts the existing 10-minute no-show period. The one-hour time is an estimate and can change as the queue changes.

The nurse PIN opens a registration-only screen. Nurses choose any service, enter the patient's name, and may enter a phone number. Walk-ins are registered as already arrived. A phone-less patient gets a ticket code shown to the nurse and cannot receive texts or view a personal ticket page.

## Desktop app (its own window)

`--desktop` opens ClinicQueue in its own window instead of a browser tab. It tries three things in order:

1. **A native JavaFX window** (the installed Windows app). The pages are shown by JavaFX's built-in WebView, so no browser is involved. The window asks before quitting, answers the pages' `alert()`/`confirm()` boxes, and the "Copy link" button copies to the real clipboard. Closing the window quits ClinicQueue. Needs the desktop build (below).
2. **Microsoft Edge or Chrome in "app mode"** (no tabs, no address bar), with a separate profile folder so it never touches the person's everyday browser. Used by the plain JDK-only build, and when JavaFX is not available.
3. **The older small control window**, using the default browser, if neither of the above works.

The start screen shows the Wi-Fi address patients' phones can use (only on the computer running ClinicQueue). Set `CLINICQUEUE_BROWSER` to a browser's full path to choose a different one for option 2.

## Windows installer with JavaFX (recommended)

On Windows, with JDK 17+ installed (21 recommended) and internet for the first run, run:

```
packaging\windows\build-desktop-installer.bat
```

It downloads JavaFX 21 once (into `out\`), builds the app, bundles a Java runtime with JavaFX inside it, and produces **`dist\ClinicQueue-1.1.0.exe`**, a Setup program that installs for the current user (no administrator rights), adds Start menu and desktop shortcuts, and can be removed under Settings > Apps. Making the Setup program needs the free WiX Toolset 3.x (https://github.com/wixtoolset/wix3/releases). Without WiX the script still builds `dist\ClinicQueue\ClinicQueue.exe`, a folder you can copy to any Windows 10/11 PC; `build-desktop-installer.bat portable` builds only that. If the automatic JavaFX download fails, the script explains how to download it by hand and point `JAVAFX_JMODS` at it.

Set the four role-specific environment variables in Windows before starting the installed app to enable restricted department access. With no role PINs configured, the app remains in legacy shared-PIN demo mode. `staff-pin.txt` changes only that legacy shared PIN.

Run it from source with JavaFX (any OS): compile `src/main/java` and `src/desktop/java` with `--module-path <javafx lib or jmods> --add-modules javafx.controls,javafx.web`, copy `src/main/resources/static` next to the classes, and start `com.clinicqueue.Main --desktop` with the same module options.

## Windows installer (.exe) built on Linux or macOS (no JavaFX)

This older installer does not include JavaFX, so its app opens in the Edge/Chrome app window (option 2 above). `dist/ClinicQueue-Setup.exe` installs ClinicQueue for the current user with its own Java runtime, so the target computer needs nothing else. It adds desktop and Start menu shortcuts and an entry under Settings > Apps for uninstalling. `Setup.exe /S` installs silently.

Rebuild it (Linux or macOS, needs JDK 17+, Go 1.21+, Python 3 and internet for the first run):

```bash
./packaging/windows/build-installer.sh
```

Installer source is in `packaging/windows/` (`go test ./internal/...` there runs its tests). The installer is not code-signed, so Windows SmartScreen shows "Windows protected your PC" on first run: choose More info, then Run anyway. Removing that warning needs a paid code-signing certificate.

Run the tests: `./run-tests.sh` (32 tests: predictor, queue rules, notifications, department access, walk-ins, JSON, the full HTTP API, and the app-window launcher).

Windows: run `build.bat`, then `java -cp out\classes com.clinicqueue.Main`.
IDE (IntelliJ / Eclipse): create a plain Java project, mark `src/main/java` as sources and `src/main/resources` as resources, then run `com.clinicqueue.Main`.

The "text messages" are mocked: they print to the console and show in the dashboard's message log. See "Next steps" for plugging in a real SMS provider.

## Demo script (about 5 minutes)

1. Open the start screen and join **Pharmacy** from a phone. The ticket shows its place and estimate; the phone receives an arrival reminder if the estimate is within an hour.
2. Tap **I'm at the hospital** on the ticket before the provider calls next.
3. Sign in with the Pharmacy PIN (or the legacy shared demo PIN), then press **Call next patient**, **Start**, **Fast-forward 10 min**, and **Finish**. The system learns from the completed service.
4. The patient's phone changes to **It is your turn** only after the actual call-in. The mock text appears in the message log.
5. In department-PIN mode, sign out and use the Nurse PIN to register an on-site walk-in in any service queue; its ticket is already checked in.
6. Press **Add 5 patients** to simulate a rush and watch the estimates grow. Call a patient, then fast-forward 10 minutes without starting them: they are released as a no-show.

## How the wait estimate works

For each service the system keeps the real length of past consultations.

- Expected length = smoothed average of the last 20 visits, blended with the clinic's default so the first patients still get sensible numbers.
- It is adjusted for time of day: if 9:00 visits usually run longer than average, 9:00 estimates are longer (limited to between 0.7x and 1.5x, and only once there are at least 3 visits for that hour).
- A new patient's wait = the expected length of everyone ahead, divided by the number of staff serving that queue. A patient already being seen only counts for their remaining time.

Code: `WaitTimePredictor.java`. The tests prove each rule.

## Design decisions

- Patients cannot choose their own priority. Only staff can mark someone as priority or emergency, with a reason (stops people gaming the queue).
- A patient's ticket page never shows names or phone numbers, only their own position, estimate, and check-in state.
- Called patients who do not show up within 10 minutes are released automatically.
- Patients with phone numbers receive one automatic arrival reminder when the predicted wait reaches 60 minutes or less. A provider can send it manually once before check-in.
- Department PINs limit providers to their own service; the nurse PIN only registers walk-ins. PINs are shared within a role, not individual accounts.
- Staff and demo actions need a PIN. Flat static file names only, so path traversal is blocked.

## Project layout

| File | Role |
|---|---|
| `QueueService.java` | All queue rules: joining, priority order, calling, no-shows, notifications, stats |
| `StaffAccess.java` | Role PIN authentication and department access |
| `WaitTimePredictor.java` | Learns durations and estimates waits |
| `Server.java` | REST API and static pages on the JDK's built-in HTTP server |
| `Notifier.java`, `MockSmsNotifier.java` | SMS interface and the demo implementation |
| `AdjustableClock.java` | Clock with a fast-forward offset for demos |
| `DemoData.java` | Sample clinic, history and patients |
| `Json.java` | Minimal JSON writer and request parser |
| `DesktopWindow.java` | Opens the app in its own window: JavaFX if present, else Edge/Chrome app mode, else a small control window |
| `src/desktop/.../FxWindow.java` | The JavaFX WebView window. The only class that needs JavaFX, so it is built only by the desktop build |
| `static/` | Start screen (`index.html`), patient page (`join.html`), staff desk (`staff.html`), shared styles and helpers |
| `src/test/.../AllTests.java` | Dependency-free test suite |

## API

This computer only: `GET /api/lan` (Wi-Fi address for phones).
Public: `GET /api/services`, `POST /api/queue/join`, `GET /api/queue/{id}`, `POST /api/queue/{id}/cancel|arrive`.
Staff (header `X-Staff-Pin`): `GET /api/staff/session`, service-limited `GET /api/staff/queue/{service}`, `POST /api/staff/call-next/{service}`, `POST /api/staff/entries/{id}/start|finish|noshow|cancel|arrival-reminder`, `GET /api/staff/stats`, `GET /api/staff/sms`.
Nurse (header `X-Staff-Pin`): `POST /api/nurse/walkins` (service ID, name, optional phone).
Legacy demo PIN: `POST /api/demo/rush`, `/api/demo/advance`, `/api/demo/reset`.

## Next steps (honest limits)

- **Data is in memory.** Restarting clears the queue. Add a database (PostgreSQL or SQLite through JDBC) behind `QueueService`.
- **SMS is mocked.** Arrival reminders and call-in texts are logged but not delivered to mobile phones. Implement `Notifier` with Africa's Talking, Hubtel, or Twilio for real delivery. Those gateways expect international numbers, so convert local numbers (for example `024...` to `+23324...`) before sending.
- **Reminder scheduling is in memory.** The one-hour reminder check runs while the app is open. Because tickets are not persisted, a restart clears both the queue and pending reminders.
- **Shared PINs are demo-grade.** Role-specific PINs restrict services but are shared by everyone in that department and do not provide an audit trail. Use real staff accounts and hashed passwords for a pilot.
- **Single clinic.** Multiple clinics need a clinic id on services, entries and staff.
- **Updates use polling** (every 3 seconds), which is simple and works on weak connections. WebSockets are an option later.
- **Privacy.** This holds health-related contact data. A real pilot needs a data-protection review (in Ghana, the Data Protection Act) and a retention rule.
- **Framework.** If your course expects Spring Boot, the structure maps directly: `QueueService` becomes a `@Service`, `Server` becomes `@RestController` classes, and the entities become JPA `@Entity` classes.

## Pitch sketch

- **Pain:** people lose hours sitting in clinics without knowing when they will be seen, and some leave untreated. Clinics cannot see their own bottlenecks.
- **Customer:** clinics pay a monthly subscription; patients use it free.
- **Value to the clinic:** calmer waiting rooms, fewer walk-outs and no-shows, and data on peak hours for staff planning.
- **Pilot:** one private clinic or your campus clinic. Measure real waiting time before and after, and put that number on your final slide.
