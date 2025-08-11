# droidVNC-NG

[![Join the chat at https://gitter.im/droidVNC-NG/community](https://badges.gitter.im/droidVNC-NG/community.svg)](https://gitter.im/droidVNC-NG/community?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

This is an Android VNC server using contemporary Android 7+ APIs. It therefore does not require
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
* Injection of remote pointer and basic keyboard events (Latin-1 charset plus some special keys,
  supporting any kind of UI widget on Android 14 and newer, on older devices into EditText widgets only).
* Handling of client-to-server text copy & paste. Note that server-to-client copy & paste only works
  automatically for text selected in editable text fields or manually by sharing text to droidVNC-NG
  via Android's Share-To functionality.
* Handling of special keys to trigger 'Recent Apps' overview, Home button, Back button, Power button
  and volume controls.
* Android permission handling.
* Screen rotation handling.
* File transfer via the local network, assuming TightVNC viewer for Windows version 1.3.x is used.
* Password protection for secure-in-terms-of-VNC connection. Note that setting a password is mandatory
  in case you want to access the server using MacOS's built-in Screen Sharing app.
* Ability to specify the port used.
* Start of background service on device boot. On Android 11 and newer this also works with kiosk-mode
  launchers and [lock task mode](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode).
* [Reverse VNC](#reverse-vnc).
* Ability to [connect to a UltraVNC-style Mode-2 repeater](#reverse-vnc).
* Functionality to provide default configuration via a JSON file or
  [Mobile Device Management](https://developer.android.com/work/managed-configurations).
* Zeroconf/Bonjour publishing for VNC server auto-discovery.
* Per-client mouse pointers on the controlled device.
* Ability to control a device’s shared screen directly from a web browser by shipping the fabulous
  [noVNC](https://github.com/novnc/noVNC) client with the server app. This alleviates the need for a
  native VNC client.

## How to use

### Keyboard Shortcuts From a VNC Viewer

* **Ctrl-Shift-Esc** triggers 'Recent Apps' overview
* **Home/Pos1** acts as Home button
* **End** acts as Power button
* **Escape** acts as Back button
* **Ctrl-Alt-PageUp** increases audio volume
* **Ctrl-Alt-PageDown** decreases audio volume

### Within a Local Area Network

1. Install the app from either marketplace.
2. Get it all the permissions required.
3. Set a good password and consider turning the `Start on Boot` off.
4. Connect to your local Wi-Fi. For accepting a connection your device should be connected to some Local Area Network that you can control, normally it is a router.
5. Click `Start` and connect to your device.

### From The Internet

**Disclaimer**: Anything else than password exchange is currently not encrypted, so use at your own risk!

If you want to accept incoming connections from VNC viewers:

1. You should allow [Port Forwarding](https://en.wikipedia.org/wiki/Port_forwarding) in your router's Firewall settings. Either find a [UPnP](https://en.wikipedia.org/wiki/Universal_Plug_and_Play) supported app to open ports dynamically or log in to your router's settings (usually open 192.168.1.1 in your browser, some routers have password written on them).
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
5. Figure out your public address i.e. <https://www.hashemian.com/whoami/>.
6. Use this address and port from above to connect to your device.

### Reverse VNC

Here's how to connect to a listening VNC viewer or repeater without opening a server port:
1. Leave the VNC port **blank**, which will get the Admin Panel to state the server **isn't** listening for incoming connections.
2. Make outbound connections by choosing either "Connect to a **listening viewer**" or "Connect to a **repeater**".


### How to Pre-seed Preferences

DroidVNC-NG can be supplied with defaults for preferences that apply if preferences
were not changed by the user.

See the [Preseed Preferences Docs](doc/Preseed-Preferences.md) for more details.

### Remote Control via the Intent Interface

droidVNC-NG features a remote control interface by means of Intents. This allows starting the VNC
server from other apps or on certain events. It is designed to be working with automation apps
like [MacroDroid](https://www.macrodroid.com/), [Automate](https://llamalab.com/automate/) or
[Tasker](https://tasker.joaoapps.com/) as well as to be called from code.

See the [Intent Interface Docs](doc/Intent-Interface.md) for more details.

## Building

* After cloning the repo, make sure you have the required git submodules set up via `git submodule update --init`.
* Then simply build via Android Studio or `gradlew`.
 

## Contributing

Contributions to the project are very welcome and encouraged! They can come in many forms.
You can:

  * Submit a feature request or bug report as an [issue](https://github.com/bk138/droidVNC-NG/issues).
  * Provide info for [issues that require feedback](https://github.com/bk138/droidVNC-NG/labels/answer-needed).
  * Add features or fix bugs via [pull requests](https://github.com/bk138/droidVNC-NG/pulls).
    Please note [there's a list of issues](https://github.com/bk138/droidVNC-NG/labels/help%20wanted)
	where contributions are especially welcome. Also, please adhere to the [contribution guidelines](CONTRIBUTING.md).


## Notes

* Requires at least Android 7.

* [Since Android 10](https://developer.android.com/about/versions/10/privacy/changes#screen-contents),
the permission to access the screen contents has to be given on each start and is not saved. You can,
however, work around this by installing [adb](https://developer.android.com/studio/command-line/adb)
(or simply Android Studio) on a PC, connecting the device running droidVNC-NG to that PC and running
`adb shell cmd appops set net.christianbeier.droidvnc_ng PROJECT_MEDIA allow` once. Alternatively, if
using the intent interface, you can also start with `net.christianbeier.droidvnc_ng.EXTRA_FALLBACK_SCREEN_CAPTURE`
set to true.

* You can also use adb to manually give input permission prior to app start via `adb shell settings put secure enabled_accessibility_services net.christianbeier.droidvnc_ng/.InputService:$(adb shell settings get secure enabled_accessibility_services)`.

* If you are getting a black screen in a connected VNC viewer despite having given all permissions, it
might be that your device does not support Android's MediaProjection API correctly. To find out, you can
try screen recording with another app, [ScreenRecorder](https://gitlab.com/vijai/screenrecorder). If it
fails as well, your device most likely does not support screen recording via MediaProjection. This is
known to be the case for [Android-x86](https://www.android-x86.org).

* In case you happen to have a board with an Ethernet interface and experience strange hangs during a
VNC session, setting the interface to a slower speed might help. This workaround can be applied with
[mii-tool](https://github.com/bk138/droidVNC-NG/issues/121#issuecomment-2150790814), for instance.

* If you see a a floating button similar to [this](https://user-images.githubusercontent.com/6049993/194750108-a808b9c3-2bc6-4cdd-ba40-b9c59476a456.jpg)
on your screen after enabling accessibility, make sure you have the "shortcut" option in accessibility settings
turned to off.

* Due to [limitations in Android API](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/media/java/android/media/projection/MediaProjectionConfig.java#72), secondary displays are not supported.
