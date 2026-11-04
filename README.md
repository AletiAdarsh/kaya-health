# Kaya: honest health numbers from your Noise REP

Kaya is an Android app that talks **directly** to the Noise REP band over Bluetooth and turns what it records
into numbers you can trust: calories from lab equations instead of inflated heart-rate guesses, sleep stages
from a model tested on real sleep-lab nights, and a weight plan that re-plans itself every day.

*Kaya* (काया) is Sanskrit/Hindi for **the body**: an app that reads your body honestly, without flattering numbers.

It is unofficial and not affiliated with Noise. It is not a medical device.

## Why

- **Calories:** the official app estimates workout calories with a heart-rate formula (Keytel 2005) that, for a
  heavier or less-trained body, roughly doubles walking calories. Kaya uses the ACSM walking/running equations
  (weight × speed × slope), Mifflin–St Jeor for resting burn and, after about two weeks, your **real** burn from
  logged food plus your weight trend.
- **Your data stays yours:** no account, no server, no analytics. Nothing about you or your health ever leaves the
  phone. The only time Kaya goes online is the **optional update check** (off until you say yes), which just asks
  GitHub for the latest version number. Optional backups go only where you choose (see below).
- **Honest numbers:** unreliable readings are labelled as such (for example jumpy night-time SpO2), and every
  score shows how it is calculated.

## Features

| Area | What you get |
|---|---|
| Home | Energy, sleep and effort scores; a daily checklist, streaks and an optional checkpoint countdown |
| Vitals | Heart rate (live every ~2 s on demand), SpO2, stress, HRV, breathing, skin temperature, resting HR, each compared with *your* usual once Kaya has a few days of history |
| Sleep | Bedtime and wake from the band's own sleep log (gaps bridged when heart rate and steps say you were asleep); stages from Kaya's model; efficiency, wake-ups, sleeping heart rate, breathing, naps, 7-night history |
| Activity | This week's steps against your weekly goal, steps by hour, walking minutes, heart-rate zones, active calories, still hours, walk detection and a Start walk button |
| Stats | 35+ derived numbers, each with its research source and a healthy range: heart-rate recovery, night-time heart-rate dip, WHO active minutes, training load (TRIMP) and trend, sleep debt, bedtime consistency, social jetlag, routine stability, overnight baselines with an early strain/illness check, eating window, late-evening calories and more |
| Food | A food diary by meal (Recent / My foods / All, one-tap add, servings stepper), 120 common foods with macros offline, macro targets, "same as yesterday" |
| Body | Lose, gain or maintain; your target date next to a realistic one; milestones; BMI, BMR, TDEE; fitness age (HUNT VO2max model) with what brings it down |
| Mind | Four breathing patterns with live heart rate, stress stats, 5-4-3-2-1 grounding |
| Discipline | Habits with streaks and a heatmap, a morning if-then plan, mood, focus timer, 30-day challenges, your "why" and rewards, a weekly review, and a share card for an accountability partner |
| Stop. Look. (Craving SOS) | Opens with a voice message you recorded to yourself, your latest progress photo next to your goal, and what this food and this week cost in days. Then a 10-minute reset: breathing, a smarter swap, your why. Logs triggers and learns when cravings usually hit |
| Meal ideas | "What can I eat?" plates that fit the calories and protein you have left, from a built-in offline food list |
| Body extras | Water tracker, waist measurements, private progress photos, and plain-language notes on water weight and plateaus |
| Widget | Calories left, steps, water (+1 glass) and a one-tap Craving SOS on your home screen |

Everything personal is editable: height (cm or ft/in), age, sex, weight, goal weight and pace, daily intake,
protein, water and sleep goals, weekly steps, max heart rate, a checkpoint (any name, weight and date), theme,
reminders and backups in **Settings**; foods, habits, rewards and your "why" where you use them; weigh-ins and
food entries can be deleted. Nothing is preset to anyone's personal numbers.

## Supported hardware

- **Band:** Noise REP. Other Noise models may use a different protocol and are untested.
- **Phone:** Android 9 or newer with Bluetooth LE. Health Connect is used for food and weight.

## Install

1. Download the APK from the [Releases](../../releases) page (or build it, below).
2. Open it on your phone and allow "install unknown apps" for your browser or file manager.
3. Open Kaya: a welcome screen, a short "about you" form, then one screen to allow Bluetooth (your band),
   Health Connect (food and weight) and notifications (reminders). Each is optional and explained.
   The microphone is asked for only if you choose to record a message to yourself for Stop. Look.; the
   recording stays on your phone (and in your backup, if you turn backups on).
4. Turn the band on near the phone. Kaya finds it by itself.

**If you also use NoiseFit:** both apps can share the band, but syncing is slower while both are connected.
The band deletes a finished day once any app confirms it has synced it. Kaya never does this, and it reads the
band in the background every couple of hours and just before midnight so each day is saved first.

## Updates

On first open, Kaya asks whether it may check for updates. If you say yes, once a day it asks
`api.github.com` for this project's latest release. That request contains nothing about you: no ID, no account,
no health data. When there's a newer version, Home shows **Update**; Kaya downloads the APK from this repository's
Releases, refuses it unless it's signed with the same key as the app you have, and Android asks you to confirm the
install. Your data stays. Turn it on or off any time in **Settings → Updates**, or skip it and download new
versions from [Releases](../../releases) yourself.

## Backups (optional, off by default)

- **Backup file:** pick a file anywhere: phone storage, Google Drive, OneDrive, Dropbox. Kaya rewrites it once a
  day at the time you choose. After a reinstall, use "Restore from a backup file".
- **Android backup:** Android's own backup, end-to-end encrypted with your screen lock; restores automatically on
  reinstall or a new phone.

## Accuracy, honestly

- Sleep stages: on people the model had never seen, it matched the sleep lab **61%** of the time for four
  stages and **90%** for asleep vs awake (31 nights, Walch et al. 2019). Commercial wrist trackers typically
  land around 60–80%. Bedtime, wake time and total sleep are the solid numbers.
- Wrist SpO2 at night is noisy; Kaya shows the typical value and says when readings are unreliable.
- Calorie targets below 1,500 kcal (men) or 1,200 kcal (women) are allowed if you set them, with a warning.
  Talk to a doctor before aggressive diets.

## Build from source

Requires JDK 17 and the Android SDK (platform 36).

```bash
./gradlew testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Release builds are signed only if a `keystore.properties` file exists in the project root (never commit it):

```properties
storeFile=release.jks
storePassword=...
keyAlias=kaya
keyPassword=...
```

## How it works

```
Noise REP ──Bluetooth LE (direct)──▶ Kaya ──▶ saved on the phone (and your optional backup)
                                      ▲
Health Connect (food, weight; heart rate as a fallback) ┘
```

Protocol notes: [`docs/BAND_PROTOCOL.md`](docs/BAND_PROTOCOL.md). Code map:

- `band/`: Bluetooth transport, protocol, background sync, local storage
- `engine/`: calories, real burn, goal planner, sleep model, scores, vital insights (pure Kotlin, unit-tested)
- `data/`: Health Connect, settings, backups
- `ui/`: Jetpack Compose screens

## Legal and safety

- Kaya is an independent project. It is **not affiliated with, endorsed by or supported by Noise**. "Noise", "NoiseFit" and "REP" are trademarks of their owners and are used only to say which
  device Kaya works with.
- Kaya contains **no code, files or assets from NoiseFit**. Its Bluetooth layer builds on the MIT-licensed
  OpenNoiseRep project and on protocol facts identified for interoperability with a band the user owns.
- Using Kaya may not be covered by your band's warranty or the official app's terms. Use it at your own risk.
- Kaya is **not a medical device** and does not diagnose, treat or prevent any condition. Numbers are estimates.
  Talk to a doctor before big changes to diet or exercise, especially with a medical condition.

## Credits and licence

- Bluetooth groundwork ported from [OpenNoiseRep](https://github.com/mohammedr3za/OpenNoiseRep) (MIT).
- Sleep model trained on the Walch 2019 PhysioNet dataset (ODC-By).
- See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md). Kaya itself is released under the [MIT licence](LICENSE).
