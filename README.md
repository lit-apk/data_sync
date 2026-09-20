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

`Upload file` opens Android's document picker. The first 3,000-character part is
sent immediately. Each `confirm` action from the watch sends the next part. A
`cancel` action stops the transfer. Outside file mode, both actions display a
toast and update the status line.

Build with:

```sh
./gradlew test assembleDebug
```
