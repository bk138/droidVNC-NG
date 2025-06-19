# 2. Implement Control Interface for 3rd-party Apps via Intents

Date: 2023-06-12

## Status

Accepted

## Context

There are several ways of providing a control interface for 3rd-party apps. Possible approaches are:

### Static Methods

These are not inter-process.

### [Intents](https://developer.android.com/reference/android/content/Intent)

Not really idiosyncratic, but a lot of automation apps support this:

* Tasker
    * can send Intents as per https://joaoapps.com/autoshare/intentbuilder/
       * To activity, service, via broadcast 
    * can get intents to self back: https://tasker.joaoapps.com/commandsystem.html
    * https://wiki.torque-bhp.com/view/PluginDocumentation has AIDL and Intent interface for apps
      like Tasker
    * no PendingIntent
    * can ask permission
* IFTT
    * can not send Intents
* MacroDroid
    * can send intents as per https://www.macrodroidforum.com/index.php?threads/how-to-use-action-send-intent.57/
        * To activity, service, via broadcast
    * no PendingIntent
* Automate
    * has "start service" und "send broadcast" together with nice service picker
    * no PendingIntent
    * can ask permission, but root-only?

Sending Intents can be done via `sendBroadcast()` or `startService()`: `sendBroadcast()` can define
a permission that the recipient (i.e. droidVNC-NG) must have. Regarding `startService()` the service
can be declared with the permission it needs to start in the manifest, but other apps can not ask
for this at runtime.

Propagating feedback from droidVNC-NG to the caller can be done via:
- BroadcastReceiver https://developer.android.com/guide/components/broadcasts
    - Permissions yes, but via manifest?
    - In Android 4.0 and higher, you can specify a package with setPackage(String) when sending a
      broadcast. The system restricts the broadcast to the set of apps that match the package.
- ResultReceiver https://stackoverflow.com/questions/4510974/using-resultreceiver-in-android
    - IPC works, but with a lot of boilerplate https://stackoverflow.com/questions/5743485/android-resultreceiver-across-packages
    - Not supported by reviewed automation apps
- PendingIntent
    - There are reports it works, e.g. https://androidlearnersite.wordpress.com/2018/02/23/communicating-from-services/
    - It's more like "take this intent of mine and execute it with my permissions"
    - Not supported by reviewed automation apps

### IntentService/WorkManager

Deprecated according to https://developer.android.com/reference/android/app/IntentService but there
is a successor in https://developer.android.com/topic/libraries/architecture/workmanager. This seems
to be targeted at data crunching/computation and not service providing.

### ContentResolver

Not considered.

### [Messenger](https://developer.android.com/reference/android/os/Messenger)

This is explicitly for IPC, but apparently without AIDL.

### [Binder](https://developer.android.com/reference/android/os/Binder)

Explicitly for IPC, with [AIDL](https://developer.android.com/guide/components/aidl) and rather
full-blown compared to Messenger.


## Decision

It is decided to go with Intents since that's what 3rd-party automation apps support.

Static methods are not IPC and thus not useful at all; IntentService is deprecated; WorkManager and 
ContentResolver not a good use case fit. For Binder, we would have to publicise the AIDL definition,
but there is no real support for this in 3rd-party automation apps at the time of the research;
neither is there for Messenger.

Regarding the sending of Intents to droidVNC-NG, we opt for `startService()`: `sendBroadcast()` is
not really suitable since it defines a permission that the recipient (i.e. droidVNC-NG) must have.
While with `startService()` the receiving service can be declared with the permission it needs to
start in the manifest, other apps can not ask for this at runtime. A shared secret given as an
Intent Extra is a solution.

To propagate feedback from droidVNC-NG back to the caller, we opt for `sendBroadcast()` as we only
need to inform about the outcome of a certain incoming request. This can be solved by tacking a
request id onto the Intent for droidVNC-NG and letting droidVNC-NG send a broadcast with this
request id and a status code.

## Consequences

When sending simple Intents, security becomes a concern. Since we opted to not use Android
permissions as sending apps would need to declare the droidVNC-NG permission at build-time, we have
to cook our own access control.

With the devised system of having a shared secret between caller and droidVNC-NG, it becomes
important for our code to explicitly check for that secret; we cannot rely on the framework-provided
permission machinery.

Likewise, the devised system of using request ids for Intents to droidVNC-NG and letting droidVNC-NG
answer with a broadcast with this request id together with a status code does not reveal any other
payload of the request Intent; but is _does_ reveal that there was such a request.
