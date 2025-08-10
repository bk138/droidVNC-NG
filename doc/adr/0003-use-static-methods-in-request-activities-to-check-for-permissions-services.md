# 3. Use Static Methods in Request Activities To Check for Permissions/Services

Date: 2025-08-10

## Status

Accepted

## Context

The `*RequestActivity` activities check for the permission they are requesting / the service they
should start in their onCreate(), which in turn means that if started on boot, these activities are
created even if the permission is already given / the service up. This is problematic in setups where
[lock task mode](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode) is used:
the activity would not be started, MainService would never receive the result Intent and not continue
startup.

## Decision

Use static helper methods in each request activity to check for the permission they are requesting /
the service they should start. If the precondition is already met, post a result Intent to
MainService immediately without actually starting the activity.

There is a [similar approach that did away with the result Intents as well](https://github.com/bk138/droidVNC-NG/pull/243)
by refactoring MainService, but it was decided not to use this as it would introduce a second code
path on startup in addition to the result Intent posting.

## Consequences

After adding static helper methods to request activities, these become the designated way of
checking for permission/service. Other components in the app must take care to use these.
