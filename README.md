# droidVNC-NG

[![Join the chat at https://gitter.im/droidVNC-NG/community](https://badges.gitter.im/droidVNC-NG/community.svg)](https://gitter.im/droidVNC-NG/community?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

This is an Android VNC server using contemporary Android 5+ APIs. It therefore does not require
root access. In reverence to the venerable [droid-VNC-server](https://github.com/oNaiPs/droidVncServer)
is is called droidVNC-NG.

If you have a general question, it's best to [ask in the community chat](https://gitter.im/droidVNC-NG/community). If your concern is about a bug or feature request instead, please use [the issue tracker](https://github.com/bk138/droidVNC-NG/issues).

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/net.christianbeier.droidvnc_ng/)
[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png"
     alt="Get it on Google Play"
     height="80">](https://play.google.com/store/apps/details?id=net.christianbeier.droidvnc_ng)

## Features

* Network export of device frame buffer with optional server-side scaling.
* Injection of remote pointer events.
* Handling of client-to-server text copy & paste. Note that server-to-client copy & paste does not
  work in a generic way due to [Android security restrictions](https://developer.android.com/about/versions/10/privacy/changes#clipboard-data).
* Handling of special keys to trigger 'Recent Apps' overview, Home button and Back button.
* Android permission handling.
* Screen rotation handling.
* File transfer via the local network, assuming TightVNC viewer for Windows version 1.3.x is used.
* Password protection for secure-in-terms-of-VNC connection.
* Ability to specify the port used.
* Start of background service on device boot.
* Reverse VNC.
* Ability to connect to a UltraVNC-style Mode-2 repeater.
* Functionality to provide default configuration via a JSON file.

## Contributing

Contributions to the project are very welcome and encouraged! They can come in many forms.
You can:

  * Submit a feature request or bug report as an [issue](https://github.com/bk138/droidVNC-NG/issues).
  * Provide info for [issues that require feedback](https://github.com/bk138/droidVNC-NG/labels/answer-needed).
  * Add features or fix bugs via [pull requests](https://github.com/bk138/droidVNC-NG/pulls).
    Please note [there's a list of issues](https://github.com/bk138/droidVNC-NG/labels/help%20wanted)
	where contributions are especially welcome. Also, please adhere to the [contribution guidelines](CONTRIBUTING.md).

## How to use

1. Install the app from either marketplace.
2. Get it all the permissions required.
3. Set a good password and consider turning the `Start on Boot` off.
4. Connect to your local Wi-Fi. For accepting a connection your device should be connected to some Local Area Network that you can control, normally it is a router. Connections via data networks (i.e. your mobile provider) are not supported.
5. Click `Start` and connect to your device.

### For accepting connections from outside

1. You should allow [Port Forwarding](https://en.wikipedia.org/wiki/Port_forwarding) in your router's Firewall settings. Login to your router's settings (usually open 192.168.1.1 in your browser, some routers have password written on them).
2. Find Port Forwarding, usually it's somewhere in **Network - Firewall - Port Forwards**.
3. Create a new rule, this is an example from OpenWRT firmware.
   
   Name: **VNC forwarding**
   
   Protocol: **TCP**
   
   Source zone: **wan** may be "internet", "modem", something that suggests the external source.
   
   External port: **5900** by default or whatever you specified in the app.
   
   Destination zone: **lan** something that suggests local network.
   
   Internal IP address: your device's local IP address, leaving **any** is less secure. The device's address may change over time! You can look it up in your routers' connected clients info.
   
   Internal port: same as external port.

4. Apply the settings, sometimes it requires rebooting a router.
5. Figure out your public adress i.e. <https://www.hashemian.com/whoami/>.
6. Use this address and port from above to connect to your device.

### How to Pre-seed Preferences

DroidVNC-NG can read a JSON file with default settings that apply if settings were not changed
by the user. A file named `defaults.json` needs to created under
`<external files directory>/Android/data/net.christianbeier.droidvnc_ng/files/` where
depending on your device, `<external files directory>` is something like `/storage/emulated/0` if
the device shows two external storages or simply `/sdcard` if the device has one external storage.

An example `defaults.json` with completely new defaults (not all entries need to be provided) is:

```json
{
    "port": 5901,
    "portReverse": 5555,
    "portRepeater": 5556,
    "scaling": 0.7,
    "password": "supersecure"
}
```

## Notes

* Requires at least Android 7.

* [Since Android 10](https://developer.android.com/about/versions/10/privacy/changes#screen-contents),
the permission to access the screen contents has to be given on each start and is not saved. You can,
however, work around this by installing [adb](https://developer.android.com/studio/command-line/adb)
(or simply Android Studio) on a PC, connecting the device running droidVNC-NG to that PC and running
`adb shell cmd appops set net.christianbeier.droidvnc_ng PROJECT_MEDIA allow` once.

* You can also use adb to manually give input permission prior to app start via `adb shell settings put secure enabled_accessibility_services net.christianbeier.droidvnc_ng/.InputService:$(adb shell settings get secure enabled_accessibility_services)`.

* If you are getting a black screen in a connected VNC viewer despite having given all permissions, it
might be that your device does not support Android's MediaProjection API correctly. To find out, you can
try screen recording with another app, [ScreenRecorder](https://gitlab.com/vijai/screenrecorder). If it
fails as well, your device most likely does not support screen recording via MediaProjection. This is
known to be the case for [Android-x86](https://www.android-x86.org).
