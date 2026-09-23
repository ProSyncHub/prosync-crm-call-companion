# ProSync CRM Call Companion

This build captures normal Android SIM calls on company phones, attributes each call to the selected employee, finds native call recordings where the phone saves them, and syncs the call plus recording to ProSync CRM through an offline-safe queue.

## Capture control

- `CAPTURE ON`: saves call sessions locally, finds native recordings, uploads to CRM, and requests transcript + AI summary.
- `CAPTURE OFF`: the companion does not observe/save call history, scan recordings, or upload data.
- Turning capture off does not control the manufacturer's native Phone recorder. If that Phone app is independently configured to record every call, Android may still create its own file; ProSync will not read or upload it while OFF.
- Calls made without internet remain queued with their original timestamps and sync automatically when connectivity returns.
- Capture ON/OFF does not depend on employee selection. If no employee is verified, calls are retained and synced as `UNASSIGNED`.
- Selecting an employee requires that employee's CRM / Workforce password. The password is sent only for server verification and is never stored on the phone.

It is not a CRM dialer. Employees keep using the normal phone app.

## What is implemented

- Active employee / shift selection
- `ACTION_PHONE_STATE_CHANGED` receiver
- Employee is snapshotted at call start, not at sync time
- Incoming, outgoing, missed, rejected and blocked calls read from Android CallLog
- Phone number, direction, timestamp, duration and PhoneAccount ID/component captured
- Event-to-CallLog matching using call timing + optional phone-number hint
- Duplicate PHONE_STATE broadcasts are handled safely
- Local SQLite database
- 15-minute recovery scan catches calls if the real-time receiver misses one
- Capture is explicit; no calls are stored while capture is OFF
- CRM server integration for call-session sync
- Native recording matching and multipart upload
- CRM transcription/AI trigger after recording upload
- Persistent notification showing the active employee
- No WhatsApp logic yet

## Core Phase 1 rule

**The Android CallLog is the source of truth.**

A call event only tells us that a call started/ended. After the call ends, the app finds the corresponding CallLog row and saves it locally.

## CRM sync

The app posts to:

- `GET /api/mobile/employees`
- `POST /api/mobile/calls`
- `POST /api/mobile/calls/{callLogId}/analyze`

Use the CRM `MOBILE_SYNC_API_KEY` as the app API key.

## First physical test

Use one Samsung Galaxy A55 first.

1. Open app.
2. Grant Call Log + Phone State permissions.
3. Enter CRM URL and mobile API key.
4. Sync employees from CRM.
5. Select an employee and verify their CRM / Workforce password, or choose Unassigned.
6. Set device label, e.g. `Samsung A55 - Test`.
7. Tap **TURN CAPTURE ON**.
8. Make 5 outgoing calls.
9. Receive 5 incoming calls.
10. Create 2 missed calls.
11. Return to app and tap Refresh.
12. Sync runs automatically; use **Sync now** only for diagnostics.
13. Target result: 12/12 calls captured locally and synced to CRM or unmatched-call queue.

## Important permission note

`READ_CALL_LOG` is a sensitive/hard-restricted Android permission. This project is intended for company-managed internal Android devices. Physical-device installation behavior must be validated on Samsung, OPPO and OnePlus before broader deployment.

## Build environment

- compileSdk 36
- targetSdk 36
- minSdk 29
- AGP 8.13.2
- Kotlin 2.3.20
- Gradle 8.13
- Java 17

The Gradle wrapper JAR/scripts are not bundled in this zip. Open the project in Android Studio and let Android Studio configure Gradle, or generate the wrapper with Gradle 8.13 installed.
