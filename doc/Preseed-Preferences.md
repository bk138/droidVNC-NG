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
    "startOnBootDelay": 0
}
```

## Via Managed App Restrictions
If you are using a device owner app, you can also preseed the preferences via [managed app restrictions](https://developer.android.com/work/managed-configurations). The same keys as in the JSON file above can be used.

**NOTE**: Updates to app restrictions are only applied when the service restarts.
