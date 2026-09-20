# Data Sync Android

Android companion app for the Redmi Watch 5 Vela app in `../data-sync`.

## Xiaomi SDK

The Xiaomi wearable SDK is required for communication with the watch. Obtain
`xms-wearable-lib_1.4_release.aar` from Xiaomi's wearable SDK distribution and
place it at:

```text
watch_data_sync/app/libs/xms-wearable-lib_1.4_release.aar
```

The AAR must be present when producing the APK that will be installed on the
phone. Without it, the APK can still build and display its UI, but device
discovery, sending text, receiving actions, and file-mode continuation do not
work. The app reports `Xiaomi wearable SDK missing` in its status line.

The project loads the SDK through its documented classes at runtime and packages
all `.aar` and `.jar` files found in `app/libs`.

## Xiaomi Health authorization

On first connection, the app checks and requests the Xiaomi wearable SDK's
`DEVICE_MANAGER` (`data_manager`) permission for the paired node. Approve the
request in Xiaomi Health/Mi Fitness. If it is dismissed or denied, open this app
again and tap **Authorize watch** before sending data.

This SDK authorization is separate from Android runtime permissions. A
`PermissionDeniedException` from `sendMessage` means the Xiaomi bridge has not
granted this node permission, even if Bluetooth and nearby-device permissions are
already enabled for Xiaomi Health.

The APK application ID and watch package are both
`top.lighilit.watch_data_sync`. Sign the APK and `.rpk` with the same certificate,
install `com.mi.health` or `com.xiaomi.wearable`, and pair the watch. See
[`../data-sync/docs/signing.md`](../data-sync/docs/signing.md) for the complete
shared-certificate procedure.

## File mode

The Android UI has separate Text, File, and Settings tabs. Both submitted text
and uploaded files use the persisted maximum characters per message configured
in Settings (1 to 10,000, default 300). The first part is sent immediately.
Each `next` action from the watch sends one pending part; `reset` discards all
remaining unsent parts.

The File tab stores the latest reading positions as absolute character offsets,
not message numbers, so changing the maximum message size does not change the
resume position. The Settings page controls the retained history count (default
10) and whether newly selected files are copied into internal app storage
(default off). History entries can be resumed by tapping them or edited to change
their offset and source. Internal backup names are made unique automatically.

When the watch connects, it requests this history and displays base filenames.
Selecting a filename remotely resumes that entry. Missing entries and unreadable
sources are returned to the watch as visible errors.

Build with:

```sh
./gradlew test assembleDebug
```
