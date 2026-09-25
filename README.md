# Boot Apps Manager — setup notes

## Drop into Android Studio
Create a new empty Kotlin project, then copy these files over the generated ones,
matching the paths shown (`app/src/main/...`). Sync Gradle.

## Since you're rooted
`BootAppsRepository.isRootAvailable()` runs `su -c id` and checks for `uid=0`.
If that succeeds, disabling a selected app targets *only its boot receiver
component(s)*, leaving the rest of the app untouched — same behavior as
AutoStarts. For each boot receiver the scan found, it runs:

    su -c "pm disable <package>/<fully-qualified-receiver-class>"

and re-enabling runs:

    su -c "pm enable <package>/<fully-qualified-receiver-class>"

This works because `su` executes as UID 0, which bypasses the
`CHANGE_COMPONENT_ENABLED_STATE` permission check that would otherwise stop
one app from toggling another app's components. The "Boot: on/off" badge in
the list reads each component's actual current state via
`PackageManager.getComponentEnabledSetting()`, so it stays accurate even if
you also change things through `adb` or another tool.

First launch: your root manager (Magisk, KernelSU, etc.) will prompt a grant
dialog the first time the app calls `su`. Approve it once; after that root
calls succeed silently.

If you'd rather not grant root to this app specifically, you can instead run
the same `pm disable-user` command yourself over `adb shell` for anything the
app flags — the package names it lists are exactly what `pm` expects.

## Known limitations / things to sanity-check
- The scan uses `GET_RECEIVERS` + confirms each candidate by resolving
  `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / `QUICKBOOT_POWERON` against it,
  which cuts false positives but won't catch apps that start via other
  mechanisms (JobScheduler/WorkManager periodic jobs, foreground services kept
  alive by the OS, `AlarmManager` on unlock, OEM auto-start whitelists like
  MIUI's "autostart" manager, etc.). Those aren't visible via PackageManager
  and need OEM-specific settings screens instead.
- Disabling a boot receiver that some apps treat as a broader "readiness"
  signal (rare, but some apps re-check permissions or licensing state in
  their boot receiver) could have side effects beyond just skipping
  autostart — worth spot-checking anything unfamiliar before disabling.
- The scan filters out `FLAG_SYSTEM` apps, so you won't be offered
  system-critical or OEM components to disable in the first place.
- Import/export uses Storage Access Framework (`ACTION_CREATE_DOCUMENT` /
  `ACTION_OPEN_DOCUMENT`), so no storage permission is needed — the user picks
  the file location each time.
