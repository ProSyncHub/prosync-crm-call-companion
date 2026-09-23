# Phase 1 Physical Device Test Plan

## Device 1: Samsung Galaxy A55

### Setup
- Grant READ_CALL_LOG
- Grant READ_PHONE_STATE
- Active user selected
- Device label saved

### Calls
1. Outgoing connected call — 20 sec
2. Outgoing connected call — 60 sec
3. Outgoing unanswered/cancelled call
4. Incoming connected call — 20 sec
5. Incoming connected call — 60 sec
6. Incoming missed call
7. Incoming rejected call
8. Switch employee
9. Outgoing connected call
10. Incoming connected call

### Verify each row
- Phone number
- Direction
- Timestamp
- Duration
- Employee
- PhoneAccount ID
- Capture mode

### Pass criteria
- 10/10 calls present
- No duplicate rows
- Employee before switch remains employee A
- Calls after switch show employee B
- Missed/rejected calls are present

## Recovery test
- Force-stop app.
- Make a call.
- Re-open app and run Recovery Scan.
- Call must appear, even if employee attribution is UNASSIGNED.
