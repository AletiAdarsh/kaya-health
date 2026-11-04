# Noise REP Bluetooth protocol: what Kaya knows

Base: [OpenNoiseRep](https://github.com/mohammedr3za/OpenNoiseRep) (MIT), `docs/PROTOCOL.md`.
**New here:** live heart rate, app-state and workout commands, found 1 Nov 2026 by recording
NoiseFit 5.4.7 ↔ band (firmware 1.4.3) with Android's Bluetooth HCI snoop log.

## Link
- Advertised name `Noise_REP_XXXX`. No pairing, no encryption, no auth.
- While NoiseFit holds the link the band stops advertising. Kaya finds it via
  `BluetoothManager.getConnectedDevices(GATT)` and shares the phone's existing link.
- Service `16186f00-0000-1000-8000-00807f9b34fb`. `6f02` = commands to the band,
  `6f01` = replies, `6f03` = bulk/activity files. All five chars have props 0x14 (write-no-resp + notify).
- MTU 247 → 242-byte chunks.

## Transport
Start frame `00 00 00 00 <count u16 LE>` → band acks `00 00 01 01` (NACK `00 00 01 02` = busy, e.g.
another app mid-transfer) → chunks `<idx u16 LE, 1-based><≤242 B>` → end ack `00 00 01 00`.
The band sends the same way, and the receiver writes the acks back to the notifying characteristic.

## Messages (protobuf; field 1 = type)

| Type | Direction | Meaning | Status |
|---|---|---|---|
| 32 | → | device info: `4→1 {1 firmware, 2 serial, 3 mac, 4 model}` | confirmed |
| 112 | → | data index `9→2 →repeated 1 {1 date, 2 metric}` | confirmed |
| 113 / 115 | → | series for a metric since a date. **The date must be a day start** (00:00); otherwise status −1 | confirmed |
| 8/45, 8/46 | → | Xiaomi realtime start/stop | **rejected** (`status 1`) |
| **164** | → | app state `12:{3: 0}` = app open, `12:{3: 1}` = background | **new, confirmed** |
| **165** | ← | pushed ~every 10 s while "app open": `12→4 {1 steps, 2 kcal, 3 distance m, 4 heart rate, 14.., 16/17/18 hourly arrays}` | **new, confirmed** (HR 88 = stored 30-s record) |
| **355** | → | workout control `8→10 {4 sport_status (1 start, 2 pause, 3 resume, 4 end), 6 sport (2 = walk), 7 unix start}`. **The band buzzes on start and on end.** It ignores a start while an earlier workout is still unsynced (its 355 report then shows `1 is_sport_no_sync = true`) | **confirmed** (Kaya's Start walk) |
| 161 | → | FIND_WEAR `12 {1: 0 start / 1 stop}`: the REP acknowledges (status 0) but does **not** vibrate | tested |
| 320, 73 | → | sent right after 355 start/end (`73: 6→8 {1: 0 start / 1 end}`) | new, seen once |
| **363** | ← | during a workout, every 2 s: `8→5 {1 heart rate, 2 kcal?, 3 ?, 4 ?, 5 unix time, 11 ?}` | **new, confirmed** |
| 98 | → | phone GPS sent each second during an outdoor workout (`8→4 {2 time, 3/4 coordinates as doubles}`) | new |
| 117 → 119 → 121 | → | fetch workout file(s) after a workout, then (probably) confirm/delete | new, needs work |
| 48 | → | time sync `5→1 {1 unix time, 2 = 44}` | seen |
| 0, 16, 19 | → | session start (19 carries a 17-char id) | seen |

## Important behaviour
- **The band deletes a day once an app has synced it.** Yesterday's data disappeared from the index after NoiseFit
  synced. If NoiseFit keeps running, Kaya only reliably sees *today*. To keep history, Kaya must store
  data locally and be the app that syncs (NoiseFit off).
- Heart-rate history: metric 2, 30-s slots, 2,880 per day, one unsigned byte each (0 = no reading).
- Daily activity: metric 0, hourly big-endian u16 arrays. Field 3 = steps, 5 = distance, 7 = kcal, 15 = active min.
  Day totals are in fields 9 (steps), 10 (kcal), 13 (active min).

## More metrics (decoded 1 Nov 2026 with the KayaProbe debug dump)
Fetched with type 113 like the others. "blob" = one byte per slot in field 2 (no field 3). 288 bytes = 5-min slots.

| Metric | Shape | Meaning (evidence) |
|---|---|---|
| 8 | 288 × u16 BE (576 B), 5 min | skin temperature ×100 °C (34.0–36.0), only overnight |
| 11, 12 | 24 B hourly flags | 0/1 per hour (11 looks like "active hour") |
| 14 | 288 B blob | mostly 0, a few 5s; ff = future slots |
| 17 | scalars `2=76 3=10184` | unknown |
| 18 | 288 B blob | 0/5 flags scattered through the night, possibly movement |
| **19** | 288 B blob | **breathing rate (16–27/min), recorded only while the band thinks you're asleep** → the band's own motion-based sleep window (within ~10 min of NoiseFit's) |
| 23 | 24 B hourly | **Battery %** each hour (NoiseFit's SDK calls it `battery_data`); not resting HR. Kaya works out resting HR from series 2 instead |
| 25 | 6 × {1:0, 2:<300 B>} | all zeros so far |
| 26 | status 0 | nothing |
| 27 | 2,880 B blob | all zeros; beat-to-beat data comes as `*_RRI_Data.bin.txt` files (type 4097) instead |

## Command list
Message ids and field numbers below were identified for interoperability (so an independently written app can talk to a
band the user owns) and confirmed against the band itself where marked. No vendor code or files are included in this
repository. Envelope `SEWear { 1 id (SEFunctionId), 4 device, 8 sporting, 9 fitness, 12 micro_function, 15 setting_menu, 100 error_code }`.

| id | name | notes |
|---|---|---|
| 32 | GET_DEVICE_INFO | |
| 33 | GET_DEVICE_BATTERY_STATUS | → device(4).device_battery_status(2) {1 capacity %, 2 charge: 1 charging, 2 not, 3 full} |
| 48 | SET_SYSTEM_TIME | |
| 112 | GET_FITNESS_TYPE_ID_LIST | data index |
| 113 | REQUEST_FITNESS_TYPE_ID | fetch a series |
| **115** | **CONFIRM_FITNESS_TYPE_ID** | **"received, you may clear it". Do NOT send (OpenNoiseRep treated it as a 2nd fetch shape)** |
| 117/119/121 | GET/REQUEST/CONFIRM_FITNESS_SPORT_ID(_LIST) | workout files |
| 164 | PHONE_SEND_BASIC_DATA_REPORT | micro_function(12).basic_data_mode(3): 0 START, 1 STOP |
| 165 | REPORT_BASIC_DATA | SEBasicData: 1 steps, 2 kcal, 3 distance, 4 heart_rate, 5 blood_oxygen, 6 standing, 7 battery {capacity, charge}, 9 sleep_duration, 12 activity_duration, 13 pressure (stress), 16–18 hourly arrays. SpO2/stress here are the last stored value |
| 355/363 | SET_RING_SPORT_STATUS / RING_WEAR_SPORT_DATA | workout start/stop, 2-s live HR |
| 730–733 | GET/SET_REAL_TIME_HEART_RATE_SETTINGS, REPORT_REAL_TIME_HEART_RATE_DATA | {switch, frequency, auto-shutdown} / {timestamp, value}. Untested |
| 768/769 | GET/SET_DATA_MEASURE_FREQUENCY | setting_menu(15).config_measure_ferquency(56) {1 none_sleep, 2 in_sleep}: {hr, br, temp, rri, hrv, spo2}. Default: all level 1 |
| 778/779 | REPORT/GET_SLEEP_STATE | {is_sleep, sleep_time} |
| 4097 | FILE_INFO_UPDATE | |

There is no "measure SpO2/stress now" command for this band; measurement cadence is set by 769 (levels, meaning unpublished).
The REP has no ECG hardware (ECG_* ids exist in the shared SDK for other devices).
