# Battery check: 24-hour idle measurement

**Status: not run yet.** The author's sandbox has no Android device or emulator, so nobody has measured this
yet. The procedure and the empty results table below are for you to run on your own phone (spec §8). Until
someone fills in the table, nothing in this repository claims a measured battery figure.

## What the app does in the background

These points come from `work/Scheduler.kt` and `work/CheckWorker.kt`:

- It uses exactly **one** WorkManager unique periodic job, named `manga_update_check`. The interval is 30 min by
  default, can be set from 15 min to 6 h, or turned off. Flex is about a quarter of the interval and never less
  than 5 min.
- The job's constraints are: network `CONNECTED` (or `UNMETERED` with *Wi-Fi only*) and battery not low.
- Each run sends one `GET /api/v1/changes` with an `ETag`. A `304` ends the run with no database write and no
  notification.
- After a failure the job retries once, 30 s later (exponential backoff). After that it waits for the next
  period.
- The app has no foreground service, no expedited work and no alarms of its own, and its own code takes no wake
  locks. WorkManager's `SystemForegroundService` is removed from the manifest.
- With *Quiet hours* and *Slow down at night* on, the job still wakes on schedule. Inside quiet hours it skips the
  network request when the last successful check is less than 2 h old. Quiet hours also hold back notifications.

WorkManager adds `WAKE_LOCK` and `RECEIVE_BOOT_COMPLETED`. While a job runs, WorkManager or JobScheduler holds a
short wake lock for it (a few seconds per run). After a reboot, WorkManager schedules the same single job again.

## Before you start

- Install the **signed release** APK from a GitHub Release. Do not measure with the debug build: its package is
  `com.mbols2510.mangamanager.debug` and it is debuggable, so it is not what users run.
- Pair the app and leave the settings you want to measure. The default is 30 min, Wi-Fi only off, quiet hours off.
- Turn on USB debugging (Developer options) and connect the phone with `adb devices` showing it.
- Charge to around 80-90 %, then unplug. Battery stats only count time on battery, and battery-not-low must hold
  for the job to run.

## Procedure

```sh
PKG=com.mbols2510.mangamanager

# 1. Confirm there is exactly ONE job for the app, and look at its constraints.
adb shell dumpsys jobscheduler | grep -A12 "$PKG"

# 2. Reset the statistics and record the full wake-lock history.
adb shell dumpsys batterystats --reset
adb shell dumpsys batterystats --enable full-wake-history

# 3. Unplug USB (adb over USB counts as charging). Turn the screen off and leave the phone idle on battery
#    for 24 hours: normal network, no use of the app. (Optional: adb tcpip / wireless debugging avoids replugging,
#    but keeps Wi-Fi debugging active; note it in the results if you use it.)

# 4. After 24 h, reconnect and collect.
adb shell dumpsys batterystats "$PKG" > batterystats-app.txt
adb shell dumpsys batterystats > batterystats-full.txt
adb shell dumpsys jobscheduler | grep -A12 "$PKG"
adb shell dumpsys power | grep -i wake
adb shell dumpsys deviceidle | head -n 40
adb shell dumpsys alarm | grep -i "$PKG"

# 5. Optional: a full bug report for Battery Historian (https://github.com/google/battery-historian).
adb bugreport bugreport-24h.zip

# 6. Turn the extra history off again.
adb shell dumpsys batterystats --disable full-wake-history
```

### Forcing one run (to check that the job works at all)

`dumpsys jobscheduler` lists the job as `JOB #u0aNNN/<jobId>: ... com.mbols2510.mangamanager/androidx.work.impl.background.systemjob.SystemJobService`.
Take the number after the slash:

```sh
adb shell cmd jobscheduler run -f com.mbols2510.mangamanager <jobId>
```

`-f` ignores the constraints. Afterwards, Settings in the app shows a new "last successful check" time.

### Doze notes

When the screen is off, Android delays jobs to Doze maintenance windows. Runs can then be later and further
apart than the interval. This is expected (spec §5.3). To try Doze quickly instead of waiting:

```sh
adb shell dumpsys deviceidle force-idle      # enter deep Doze now
adb shell dumpsys deviceidle step            # step through the idle states
adb shell dumpsys deviceidle unforce         # back to normal
```

If an OEM battery optimiser ("app sleep", "deep sleep") puts the app to sleep, it may stop running jobs
completely. That is a device setting, not a fault in the app.

## How to read the output

- **`dumpsys jobscheduler`**: you should see **exactly one** job for the package.
  - It should show the WorkManager job service, `SystemJobService`.
  - Its required constraints should include connectivity (`CONNECTIVITY`, or an unmetered network request with
    Wi-Fi only) and `BATTERY_NOT_LOW`.
  - WorkManager schedules each period as a one-off job with a minimum latency up to the interval, and then
    schedules the next one after the run. So you may not see a "periodic" flag. The interval shows up as the
    latency and as the spacing of runs.
  - Two or more jobs for the package means something is wrong.
- **`dumpsys batterystats <pkg>`**: in the per-app section ("Estimated power use" / "Uid u0aNNN"), read the mAh
  attributed to the app and its wake-lock lines.
  - Look at "Wake lock ... realtime": only short `*job*/...SystemJobService` locks are expected, a few seconds
    each.
  - Look at "Jobs" / "Job completions" to count the runs.
  - Look at the wakeup alarm counts. Expect zero from the app's own code. WorkManager may hold one long-dated
    alarm for detecting a force stop.
  - Look at mobile and Wi-Fi bytes.
- **`dumpsys power | grep -i wake`**: no wake lock held by `com.mbols2510.mangamanager` while idle. Wake locks of
  other apps are not relevant here.

### Expected shape (not measured)

| Item | Expected at the 30 min default |
|---|---|
| Jobs for the package | exactly 1 |
| Job runs | up to about 2 per hour while awake; fewer and batched in Doze (often a few per hour or fewer overnight) |
| Wake locks | only the short per-run job locks; none held while idle |
| App alarms | none of its own |
| Network per quiet run | one small request answered with `304` |
| Energy | a small share of the daily battery use; should be in the noise next to the system |

## Results (fill in)

| Device | Android version | Interval | Wi-Fi only | Hours on battery | App mAh | Job runs / wakeups | Wake-lock time | Notes |
|---|---|---|---|---|---|---|---|---|
| | | | | | | | | |

## If the numbers look wrong

1. **Too many runs or more than one job.** Copy the `dumpsys jobscheduler` block into an issue. Unpairing and
   pairing again replaces the schedule.
2. **High mAh or many runs.** Raise the interval to 60 min or more (Settings > Background checks). Each step halves
   the number of runs.
3. **Mobile data use.** Turn on *Wi-Fi only*. The job then waits for an unmetered network.
4. **Night.** Turn on *Quiet hours* with *Slow down at night*: the app makes at most one request every 2 h inside the
   quiet window, and notifications wait until it ends.
5. **Not needed at all.** Set the interval to *Off*. The app then only refreshes when you open it.
6. **A wake lock held by the app while idle.** This should never happen. Report it with the `dumpsys power` and
   `batterystats` output.
