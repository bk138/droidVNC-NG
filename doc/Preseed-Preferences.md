# Preseed Preferences

DroidVNC-NG can be supplied with defaults for preferences that apply if preferences
were not changed by the user.

## Via JSON File
A file named `defaults.json` needs to created under
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
    "viewOnly": false,
    "showPointers": true,
    "fileTransfer": true,
    "password": "supersecure",
    "accessKey": "evenmoresecure",
    "startOnBoot": true,
    "startOnBootDelay": 0,
    "chordRecents": "Control_L+Shift_L+Escape",
    "chordHome": "Home",
    "chordBack": "Escape",
    "chordPower": "End",
    "chordVolumeUp": "Control_L+Alt_L+Page_Up",
    "chordVolumeDown": "Control_L+Alt_L+Page_Down",
    "chordRotate": "Control_L+Alt_L+Delete"
}
```
The `chord*` values are the key combinations a VNC client can use to trigger a keyboard shortcut
action. A chord is one or more keys joined with `+`, each named after its X11 keysym with the `XK_`
prefix stripped, matched case-sensitively:

* any of the modifiers `Control_L`/`Control_R`, `Alt_L`/`Alt_R`, `Shift_L`/`Shift_R` -- either side
  matches, and they may appear in any order
* one trigger key, which can be **any** X11 key name, not only the ones the in-app picker provides. 
  See [rfb/keysym.h](https://github.com/LibVNC/libvncserver/blob/master/include/rfb/keysym.h) for
  the full set.

An empty/unknown value turns that keyboard shortcut off. A chord assigned to two keyboard shortcut
actions leaves only the first working -- both are logged as warnings by InputService.

**NOTE**: a key bound to a shortcut is consumed and no longer reaches the app on the device, so avoid
binding keys that get typed.

**NOTE**: `chordRotate` only has an effect on RK3288-based devices with a portrait-in-landscape display quirk; on other hardware the setting is ignored.

## Via Managed App Restrictions
If you are using a device owner app, you can also preseed the preferences via [managed app restrictions](https://developer.android.com/work/managed-configurations). The same keys as in the JSON file above can be used.

**NOTE**: Updates to app restrictions are only applied when the service restarts.
