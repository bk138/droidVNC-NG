# 5. Group Classes Into Sub-Packages By Functionality

Date: 2026-09-23

## Status

Accepted

## Context

At the time of writing, all 22 main-source classes live in the single flat package. Three of them
are 62% of the code (`InputService` ~1570 lines, `MainActivity` ~1280, `MainService` ~1175); the
remaining 19 average around 100 lines. The dependency graph is a star around `MainService`, which
references 11 siblings and is referenced by 8.

A flat package of this size no longer communicates anything: opening the source directory does not
tell a reader which classes form the screen-capture path, which form the input-injection path, and
which are incidental helpers.

Two class names are pinned by external contracts and cannot move without breaking conventions:

- **`MainService`** — `net.christianbeier.droidvnc_ng/.MainService` is the component name of the
  public intent API documented for third-party automation (see [Intent
  Interface](../Intent-Interface.md)). Renaming it breaks public API.
- **`InputService`** — the system persists this component name in
  `Settings.Secure.enabled_accessibility_services`. Moving it silently disables input for every
  existing installation on upgrade.

The `ACTION_*` / `EXTRA_*` intent API strings are hardcoded literals rather than being derived from
the package name, so they are unaffected by any repackaging.

### Solution Alternatives

#### Alternative 1: Slice by functionality

Group classes by what they are _for_, not by what kind of thing they are. The driving principle is
membership, not usage: a class belongs to the functionality it is _part of_, not to the one it
merely _uses_. A component that is driven by a subsystem and that has no meaning without that
subsystem is part of it; a component that reaches in from the outside to use it is not.

#### Alternative 2: Slice by component type

A package layout per type-of-thing like `service/`, `ui/`, `receiver/`, `data/`, `util/`.

All the files already carry what they _are_ in their standard Android naming suffix `*Activity`,
`*Service` or `*Receiver`, so a type package restates the filename instead of adding information.

Also, two of three services are pinned at the root, so `service/` would contain exactly one class
(`MediaProjectionService`) while `MainService` and `InputService` sit outside it — the organising
principle breaks on the very classes it most needs to organise.

## Decision

Go for alternative #1, applying the membership principle above wherever a class could plausibly
belong to more than one group.

```
net.christianbeier.droidvnc_ng
├── Constants.java                       pervasive
├── Defaults.kt                          pervasive
├── InputService.java                    pinned: accessibility component
├── MainService.java                     pinned: Intent-API component
├── Utils.kt                             pervasive
│
├── autostart/                           system-event entry points that decide on server auto-start
│   ├── OnBootReceiver.java
│   └── OnPackageReplacedReceiver.kt
│
├── capture/                             screen capture backends
│   └── mediaprojection/                 mediaprojection-based capture
│       ├── MediaProjectionRequestActivity.java
│       └── MediaProjectionService.java
│
├── input/                               remote input mapping, injection and presentation
│   ├── InputKeyShortcut.kt
│   ├── InputKeyShortcutSetupActivity.kt
│   ├── InputKeysyms.kt
│   ├── InputKeysymTable.kt
│   ├── InputKeysymToUnicode.kt
│   ├── InputPointerView.kt
│   └── InputRequestActivity.java
│
├── server/                              server state, data and startup permission steps
│   ├── ClientList.kt
│   ├── MainServicePersistData.kt
│   ├── NotificationRequestActivity.java
│   └── WriteStorageRequestActivity.java
│
└── ui/                                  user interface
    ├── MainActivity.java                via activity-alias, see below
    ├── ShareActivity.kt
    └── WebViewActivity.kt
```

`Constants`, `Defaults` and `Utils` stay at the root because they are reached by nearly every class;
a `util/` package would add an import line to 17 files without conveying anything.

`autostart/` holds two components that _use_ the server rather than being part of it. Both answer
the same question: the process was killed by a system lifecycle event, should the server come back,
and with what configuration? They differ only in where the configuration comes from — user
preferences for `OnBootReceiver`, the persisted start Intent for `OnPackageReplacedReceiver`.

`capture/` is nested because the app has _two_ screen capture backends: `MediaProjectionService` and
the accessibility screenshot path in `InputService.takeScreenShots()`. Only the first can be
packaged: `takeScreenshot()` may only be called by an `AccessibilityService`, which keeps it inside
pinned `InputService`. We nonetheless have `capture/` as a super node as it tells that there is a
second member elsewhere.

`server/` also holds `NotificationRequestActivity` and `WriteStorageRequestActivity`: `MainService`
starts them itself, they post their result back to it, and its startup state machine blocks until
they do. Nothing outside `MainService` calls them, and they gate no subsystem of their own — only
the server's own ability to run.

The other two request activities: `InputRequestActivity` deep-links into
`Settings.ACTION_ACCESSIBILITY_SETTINGS` and polls for the service to come up, and
`MediaProjectionRequestActivity` brokers a consent token via `createScreenCaptureIntent()`.
`MainService` drives these two as well, but each gates a subsystem that has code of its own, so each
is grouped with that subsystem rather than with the server.

Moving `MainActivity` into `ui/` changes its component name, so an `<activity-alias>` is added to
keep the old `.MainActivity` name resolving.

## Consequences

- **Positive**: the source tree states which classes belong to which functionality being offered.
  This is information that the class names mostly do not carry.
- **Neutral**: `MainService` and `InputService` remain at the root while their helpers move into
  `server/` and `input/`. This is an unavoidable consequence of the pinned component names, and
  makes the layout look inconsistent at the top level. Could be fixed later with a breaking change.
- **Neutral**: `capture/` holds only one of the two backends, because the a11y one stays in
  `InputService`, so the tree does not fully show that the two are alternatives for the same job.
  Adding a `package-info.java` with info pointing at the a11y backend alleviates this.
- **Negative**: the `*RequestActivity` classes are now spread over three packages. Their
  `requestIfNeededAndPostResult()` entry points are already `public` and keep working, but callers
  must know to look in `input/`, `capture/mediaprojection/` and `server/` rather than in one place.
- **Negative**: the `<activity-alias>` for `.MainActivity` has to be kept indefinitely. It cannot be
  dropped in a later clean-up without breaking home-screen entries created before the move.
- **Neutral**: moving `InputKeysymTable.kt` into `input/` requires editing the `generateKeysyms`
  task in `app/build.gradle`.
