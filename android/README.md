## Android 17 / KernelSU DID configuration (hhxhl fork)

This fork does not include an LSPosed/Xposed module or its L2CAP/SDP native hook.
On the tested Pixel 8 running GrapheneOS Android 17, a separate KernelSU
`system.prop` module sets the Bluetooth SDP DID properties:

```properties
bluetooth.device_id.vendor_id=76
bluetooth.device_id.vendor_id_source=1
```

The DID module is **not bundled into the APK**. Android's Bluetooth stack
still manages A2DP/HFP; LibrePods provides an independent system-managed
BLE PendingIntent scanner for automatic reconnection. ATT advanced controls
are optional and disabled by default. Automatic AACP audio takeover is also
disabled by default and controlled separately in LibrePods settings.

The above configuration is device/OS-specific and has not been verified on
other Android releases. It does not grant Bluetooth privileged permissions.

## Installation

### Google Play Store

This repository is a customized source fork. Google Play Store builds are upstream releases and do not include the changes in this fork. For this fork, build and install the APK through your own workflow.

<a href="https://play.google.com/store/apps/details?id=me.kavishdevar.librepods"><img width="170" alt="GetItOnGooglePlay_Badge_Web_color_English" src="https://github.com/user-attachments/assets/2948308f-af92-443f-94d9-ee381c3a6ccc"/></a>

### GitHub Releases

This fork's APK does not include an LSPosed module. Install the DID module separately when needed.

### As a system app (root module)

If you want LibrePods to have privileged Bluetooth permissions to 
- show battery status in the system settings and widgets
- show AirPods icon in system settings (not implemented by this fork)
- switch audio to phone speakers when you are not wearing your AirPods

you can install the root module. This is optional and only provides extra features, but it is not required for the app to work.

> [!IMPORTANT]
> When using the root module, do not install the Play Store version. There might be issues because of the signature mismatch between the Play Store version and the root module.

## Nightly/Development Builds

Want to try the latest features before they're officially released? You can grab nightly builds from the [latest nightly release](https://github.com/kavishdevar/librepods/releases?q=nightly).

> [!WARNING]
> These builds are automatically generated from the latest code and may contain new features and bug fixes that haven't been included in a stable release yet. However, please note that they may also be less stable than official releases, so use them at your own risk.

## Screenshots

|                                                                                 |                                            |                                                                      |
| ------------------------------------------------------------------------------- | ------------------------------------------ | -------------------------------------------------------------------- |
| ![Settings 1](./imgs/settings-1.png)                                            | ![Settings 2](./imgs/settings-2.png)       | ![Head Tracking and Gestures](./imgs/head-tracking-and-gestures.png) |
| ![Long Press Configuration](./imgs/long-press.png)                              | ![Customizations 1](./imgs/customizations-1.png)                                | ![accessibility](./imgs/accessibility.png) |
| ![transparency](./imgs/transparency.png)                                        | ![hearing-aid](./imgs/hearing-aid.png)     | ![hearing-test](./imgs/hearing-test.png)   |
| ![hearing-aid-adjustments](./imgs/hearing-aid-adjustments.png)                  | ![Battery Notification and QS Tile for NC Mode](./imgs/notification-and-qs.png) | ![Widget](./imgs/widget.png)               |


here's a very unprofessional demo video

https://github.com/user-attachments/assets/43911243-0576-4093-8c55-89c1db5ea533

### Troubleshooting steps for common errors
- Check that the KernelSU DID module is enabled and that the phone was rebooted afterward.
- Verify the Android version is supported; this fork was tested on GrapheneOS Android 17.
- If AirPods fail to auto-connect, inspect `AirPodsSystemScan` logcat messages.

### A few notes

- Due to recent AirPods' firmware upgrades, you must enable `Off listening mode` to switch to `Off`. This is because in this mode, loud sounds are not reduced.

- When renaming your AirPods through the app, you'll need to re-pair them with your phone for the name change to take effect. This is a limitation of how Bluetooth device naming works on Android.
