<h1 align="center">Jisha Launcher</h1>

<p align="center">A clean, ad-free home screen for older Amazon Fire TV Sticks.<br>
It takes over the Home button and opens on boot, with no root.</p>

## Why

On a 2016 Fire TV Stick, Amazon's home screen is mostly sponsored banners, autoplaying
trailers and shopping rows, and it uses more memory than anything else on a stick with
about 875 MB of RAM.

Other launchers install fine, but Fire OS keeps sending the Home button and restarts to
Amazon's home. Redirect tools such as FTVLaunchX and LauncherHijack rely on an
accessibility service that Fire OS 5.2.9.5 switches off, and Home on Fire only supports
Fire OS 7 and 8.

## How it works

- **Home button:** a small background service reads the system log (permission granted
  once over ADB) and notices Amazon's home screen starting. Jisha then comes to the front.
- **No 5-second wait:** Fire OS blocks other apps for 5 seconds after Home. Jisha shows an
  overlay copy of its screen during that window, so you can pick an app straight away.
- **Boot:** a boot receiver starts Jisha when the TV finishes starting up.

## Features

- Glass dock for your four favourite apps, the rest in rows below
- Background glow in the colour of the selected app
- Remembers the last app or button you used when you press Home
- **Recent Apps:** double-press Home from any app; press Up to close an app; Clear RAM tile
- **Wi-Fi dropdown** with live signal strength and joining networks with a password
- **Settings menu** with direct links to Fire TV settings pages
- **Control Center:** Power Off, Day/Night, Standby and a live RAM bar with Clear RAM
- **Standby:** a full-screen calendar and clock with today's date in large type
- **Day / Night** mode, a glass-opacity slider and an **App Size** option (Small: 5 per row, Large: 4)
- Move, hide and show apps from the Menu button
- About 90 KB, native Android, no web views

## Compatibility

| Fire OS | Device | Status |
|---|---|---|
| 5.2.9.5 | Fire TV Stick 2nd Gen (2016), Android 5.1 | Tested |
| 5.x | Other Android 5.1 Fire TV devices | Should work, untested |
| 6, 7, 8 | Newer sticks | Untested |

## Install

You need a computer with [ADB](https://developer.android.com/tools/releases/platform-tools)
on the same network as the TV, and `jisha.apk` built from this repository (see [Build](#build)).

1. On the TV: **Settings → My Fire TV → Developer Options → ADB Debugging → On**.
   Find the TV's IP address under **About → Network**.
2. Install and grant the two permissions:

   ```bash
   adb connect <TV-IP>
   adb install jisha.apk
   adb shell pm grant com.jisha.launcher android.permission.READ_LOGS
   adb shell pm grant com.jisha.launcher android.permission.DUMP
   ```

3. Open Jisha once from the apps row. From then on, Home and restarts land on Jisha.

`READ_LOGS` is what lets Jisha see the Home button. `DUMP` lets Recent Apps read the list
of open apps. If you reinstall Jisha, grant them again.

**Uninstall:** Settings → Applications → Manage Installed Applications → Jisha → Uninstall.

## Build

Needs JDK 17+ and the Android SDK with `build-tools;34.0.0` and `platforms;android-34`.
No Gradle.

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
./build.sh
```

On the first build, `build.sh` creates a signing key (`jisha.keystore`) with a random
password stored in `keystore.properties`. Both are git-ignored. Keep them: you need the same
key to install updates over an existing Jisha.

## Screenshot mode

To take screenshots without showing your profile or Wi-Fi names:

```bash
adb shell settings put global jisha_demo 1   # sample names
adb shell settings put global jisha_demo 0   # back to normal
```

## License

[MIT](LICENSE)

---

Designed by Shravan · Built with Claude
