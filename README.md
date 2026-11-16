# FoldMirror


[![GitHub Release](https://img.shields.io/github/v/release/rafiulrafi55/FoldMirror?style=flat&color=brightgreen)](https://github.com/rafiulrafi55/FoldMirror/releases)
[![Android Min SDK](https://img.shields.io/badge/Android-Min%2014%20(API%2034)-brightgreen?style=flat)](https://developer.android.com/tools/releases/platforms)
[![Android Target SDK](https://img.shields.io/badge/Android-15%20(API%2035)-brightgreen?style=flat)](https://developer.android.com/tools/releases/platforms)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple?style=flat&logo=kotlin)](https://kotlinlang.org/)
[![Platform](https://img.shields.io/badge/Platform-Android-green?style=flat&logo=android)](https://www.android.com/)

**Mirror your Galaxy Z Fold 3's main display onto its cover display while the device is unfolded.**

FoldMirror is an experimental Android application designed to enable simultaneous use of the inner and cover displays on Samsung Galaxy Z Fold devices. It uses Shizuku to execute privileged device-state commands without requiring root access and Android's MediaProjection API to capture the screen.

The project is primarily developed and tested on the **Samsung Galaxy Z Fold 3 running Android 15**.

---

## Table of Contents

- [Features](#features)
- [Compatibility](#compatibility)
- [How It Works](#how-it-works)
- [Requirements](#requirements)
- [Installation](#installation)
  - [Installing Shizuku](#1-install-and-configure-shizuku)
  - [Installing FoldMirror](#2-install-foldmirror)
  - [Granting Permissions](#3-grant-required-permissions)
- [Using FoldMirror](#using-foldmirror)
- [Building from Source](#building-from-source)
- [Project Structure](#project-structure)
- [Permissions](#permissions)
- [Troubleshooting](#troubleshooting)
- [Limitations](#limitations)
- [Contributing](#contributing)
- [License](#license)

---

## Features

- **Main-to-Cover Display Mirroring:** Displays the captured main-screen content on the cover display.
- **Concurrent Display Support:** Attempts to keep both displays active simultaneously using Samsung device-state commands.
- **Shizuku Integration:** Executes privileged system commands without requiring root access.
- **Fold-State Monitoring:** Monitors the device's fold state and stops mirroring when the device is folded.
- **Screen Capture:** Uses Android MediaProjection to capture the main display.
- **Automatic Cleanup:** Releases display and screen-capture resources when mirroring stops.
- **Diagnostic Information:** Displays device information, display details, and system-state information.
- **Live Log Terminal:** Provides diagnostic logs to help identify errors and monitor application activity.
- **Permission Handling:** Guides users through the required Shizuku and screen-capture permission requests.

---

## Compatibility

| Property | Details |
|---|---|
| Application | FoldMirror |
| Platform | Android |
| Primary Device | Samsung Galaxy Z Fold 3 |
| Tested Android Version | Android 15 |
| Minimum Android Version | Android 14 |
| Minimum SDK | API 34 |
| Target SDK | API 35 |
| Compile SDK | API 35 |
| Root Required | No |
| Privileged Access | Shizuku |
| Development Language | Kotlin |

**Testing status:** The primary target and tested device is the Samsung Galaxy Z Fold 3. Compatibility with other Samsung foldable devices and firmware versions has not been guaranteed.

---

## How It Works

FoldMirror combines several Android components to achieve simultaneous display functionality.

### 1. Shizuku Integration

Shizuku provides access to privileged Android system commands without requiring root access.

FoldMirror uses Shizuku to execute device-state commands through the Android shell, allowing it to request changes to Samsung's display and device-state configuration.

### 2. Device-State Management

The application requests a compatible device state to enable concurrent display functionality.

It also monitors the device's current fold state using system diagnostic information. When the device transitions away from the unfolded state, FoldMirror stops the mirroring session.

### 3. Screen Capture

FoldMirror uses Android's MediaProjection API to capture the screen.

The user must explicitly approve the Android screen-capture permission request before capturing can begin.

### 4. Cover Display Presentation

The application detects available displays through Android's display management APIs.

It uses the `Presentation` API to render the captured screen content on the secondary display, which is the cover display on the supported device.

### 5. Resource Management

When mirroring stops, the application releases the active presentation, detaches the display surface, stops the capture service, and requests restoration of the default device state.

---

## Requirements

Before installing FoldMirror, make sure you have:

- A compatible Samsung Galaxy Z Fold device.
- Android 14 or later.
- Shizuku installed and running.
- Wireless debugging enabled for non-root Shizuku setup.
- FoldMirror APK installed.
- An active Wi-Fi connection for the initial wireless debugging setup.

**Root access is not required.**

---

## Installation

### Installing FoldMirror on Samsung Galaxy Z Fold 3

Follow these instructions specifically for the Galaxy Z Fold 3.

### 1. Install and Configure Shizuku

FoldMirror requires Shizuku to execute privileged system commands.

Download Shizuku from one of the official sources:

- [Shizuku on GitHub](https://github.com/RikkaApps/Shizuku)
- [Official Shizuku Setup Guide](https://shizuku.rikka.app/guide/setup/)

Install the application on your Galaxy Z Fold 3.

#### Enable Developer Options

1. Open **Settings**.
2. Navigate to **About phone**.
3. Select **Software information**.
4. Tap **Build number** seven times.
5. Enter your PIN or password if requested.
6. Developer options should now be enabled.

#### Enable Wireless Debugging

1. Open **Settings**.
2. Navigate to **Developer options**.
3. Find **Wireless debugging**.
4. Enable Wireless debugging.
5. Keep your device connected to Wi-Fi.

#### Pair Shizuku

1. Open the Shizuku application.
2. Select the option to pair using Wireless debugging.
3. Tap **Pair device with pairing code**.
4. Android will display a pairing code.
5. Enter that code in the Shizuku pairing notification.
6. Wait for the pairing process to complete.

The exact menu labels may vary depending on your Android and One UI version.

#### Start Shizuku

1. Return to the Shizuku application.
2. Select **Start** under the Wireless debugging section.
3. Wait until Shizuku reports that its service is running.

**Important:** On a non-rooted device, Shizuku generally needs to be started again after a device reboot. Repeat the wireless debugging startup procedure when necessary.

### 2. Install FoldMirror

Download the latest available APK from the project's GitHub Releases page:

[Download FoldMirror Releases](https://github.com/rafiulrafi55/FoldMirror/releases)

1. Open the Releases page.
2. Download the latest [**FoldMirror**](https://github.com/rafiulrafi55/FoldMirror/releases) APK.
3. Open the downloaded APK.
4. Allow installation from the selected source if Android requests permission.
5. Complete the installation.

### 3. Grant Required Permissions

1. Make sure Shizuku is running.
2. Launch FoldMirror.
3. When prompted, grant FoldMirror permission to use Shizuku.
4. Open the Shizuku application if necessary and authorize FoldMirror.
5. Return to FoldMirror.

You should now be ready to start a mirroring session.

---

## Using FoldMirror

### Starting Mirroring

1. Make sure the Galaxy Z Fold 3 is unfolded.
2. Confirm that Shizuku is running.
3. Open FoldMirror.
4. Press **Start Mirroring**.
5. Approve the Android screen-capture permission request.
6. Wait for the application to initialize the secondary display.
7. The captured main-screen content should appear on the cover display.

### Stopping Mirroring

1. Open FoldMirror.
2. Press **Stop Mirroring**.
3. The application will terminate the active mirroring session.
4. The application will request restoration of the default Samsung device state.

### Diagnostic Information

FoldMirror includes diagnostic information to help identify device and display-related issues.

The diagnostic section can provide information such as:

- Device manufacturer and model.
- Android device information.
- Available display information.
- Display resolution and density.
- Display identifiers and flags.
- Current device-state information.

Use the **Refresh** option to update the diagnostic information.

### Live Logs

The built-in log terminal displays application events and diagnostic messages.

It can help identify issues involving:

- Shizuku connectivity.
- Permission requests.
- Device-state commands.
- Display detection.
- Screen-capture initialization.
- Presentation creation.
- Mirroring session termination.

---

## Building from Source

### Prerequisites

Install the following development tools:

- Android Studio.
- JDK 21 or compatible JDK required by the project.
- Android SDK.
- Android SDK Platform 35.
- Gradle 8.7.2 or the version specified by the Gradle Wrapper.

### Clone the Repository

Open PowerShell or a terminal and run:

```bash
git clone https://github.com/rafiulrafi55/FoldMirror.git
```

Navigate to the project directory:

```bash
cd FoldMirror
```

### Open the Project

1. Launch Android Studio.
2. Select **Open**.
3. Navigate to the cloned `FoldMirror` directory.
4. Select the project folder.
5. Allow Gradle synchronization to finish.
6. If Android Studio requests SDK components, install the required components.

### Build the Debug APK

On Windows:

```powershell
.\gradlew.bat assembleDebug
```

On Linux or macOS:

```bash
./gradlew assembleDebug
```

Wait for the build to complete successfully.

### Locate the Generated APK

The debug APK will be generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Install Using ADB

Connect your Android device with USB debugging enabled and execute:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Alternatively, transfer the APK to your phone and install it manually.

---

## Technical Stack

| Component | Technology |
|---|---|
| Programming Language | Kotlin |
| Android Platform | Android SDK |
| Minimum SDK | API 34 |
| Target SDK | API 35 |
| Build System | Gradle |
| Kotlin Version | 2.0.21 |
| Gradle Version | 8.7.2 |
| Privileged Command Execution | Shizuku |
| Screen Capture | MediaProjection API |
| Secondary Display Rendering | Presentation API |
| Display Information | DisplayManager |
| Background Processing | Android Foreground Service |

### Main Dependencies

| Dependency | Purpose |
|---|---|
| `dev.rikka.shizuku:api:13.1.5` | Shizuku API integration |
| `dev.rikka.shizuku:provider:13.1.5` | Shizuku provider integration |
| AndroidX Libraries | Android compatibility and application components |

---

## Project Structure

The project follows a standard Android application structure.

```text
FoldMirror/
│
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/
│   │       │   └── ...
│   │       ├── res/
│   │       │   └── ...
│   │       └── AndroidManifest.xml
│   │
│   └── build.gradle.kts
│
├── gradle/
│   └── wrapper/
│
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── LICENSE
└── README.md
```

---

## Permissions

FoldMirror requires the following permissions or user authorizations:

| Permission / Authorization | Purpose |
|---|---|
| Shizuku Authorization | Execute privileged system commands |
| MediaProjection Consent | Capture screen content |
| Foreground Service | Maintain the screen-capture session |
| Notification Permission | Display service notifications where required |
| Wireless Debugging | Start Shizuku without root access |

Some permissions are granted through Android system dialogs rather than regular application permission settings.

---

## Troubleshooting

### Shizuku Is Not Running

**Possible causes:**
- Shizuku service has not been started.
- Wireless debugging has been disabled.
- The device was rebooted.
- Shizuku pairing or authorization was unsuccessful.

**Solutions:**
1. Open Shizuku.
2. Confirm that Wireless debugging is enabled.
3. Start the Shizuku service again.
4. Open FoldMirror and retry.

### FoldMirror Cannot Access Shizuku

**Solutions:**
1. Open the Shizuku application.
2. Navigate to its authorized applications section.
3. Find FoldMirror.
4. Make sure permission is granted.
5. Restart FoldMirror if necessary.

### Screen Capture Permission Is Denied

FoldMirror requires MediaProjection permission to capture screen content.

**Solution:**

Press Start again and approve the Android screen-capture request.

### Cover Display Is Not Detected

**Possible causes:**
- The device does not expose the cover display as an available secondary display.
- The device-state command was unsuccessful.
- Samsung firmware behavior differs from the tested configuration.

**Solutions:**
1. Confirm that the device is a compatible foldable.
2. Check the diagnostic display information.
3. Confirm that Shizuku is running and authorized.
4. Review the application logs for display-related errors.

### Mirroring Stops Unexpectedly

FoldMirror monitors the device's fold state and stops the mirroring session when it detects that the device is no longer in the expected unfolded state.

Make sure the device remains unfolded while using the application.

### Shizuku Stops After Reboot

This is expected behavior for the non-root wireless debugging setup.

Start Shizuku again using Wireless debugging before launching a new mirroring session.

---

## Limitations

- The application is experimental.
- The primary tested device is the Samsung Galaxy Z Fold 3 running Android 15.
- Android 14 is the minimum supported Android version.
- Shizuku must be running for privileged device-state operations.
- Screen capture requires explicit user approval.
- Non-root Shizuku generally needs to be restarted after reboot.
- Samsung firmware updates may change device-state behavior.
- Other Samsung foldable models may behave differently.
- Successful operation depends on the device exposing a compatible secondary display and supporting the required concurrent display behavior.

---

## Contributing

Contributions, bug reports, and suggestions are welcome.

If you encounter an issue:

1. Check the existing GitHub issues.
2. Include your device model and Android version.
3. Describe the steps required to reproduce the issue.
4. Include relevant FoldMirror diagnostic logs.
5. Submit a new issue if the problem has not already been reported.

For code contributions, please keep changes focused and follow the existing Kotlin and Android development conventions.

---

## License

This project is licensed under the **Apache [LICENSE](LICENSE) 2.0**.



---

## Links

- **GitHub Repository:** https://github.com/rafiulrafi55/FoldMirror
- **GitHub Releases:** https://github.com/rafiulrafi55/FoldMirror/releases
- **Shizuku:** https://github.com/RikkaApps/Shizuku
- **Shizuku Setup Guide:** https://shizuku.rikka.app/guide/setup/

---

<p align="center">
  <b>FoldMirror</b><br>
  Exploring simultaneous display functionality on Samsung Galaxy Z Fold devices.
</p>