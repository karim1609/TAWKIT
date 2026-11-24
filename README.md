# Tawkit Desktop — Java Swing Prayer Times App

A free, offline-first **prayer times display app for mosques and homes**, built in
**pure Java + Swing** (no JEE, no external libraries). Inspired by
[tawkit.net](https://tawkit.net) — same features, desktop kiosk style.

> وَأَقِيمُوا الصَّلَاةَ — «Et accomplissez la prière»

## Features

| Area | What it does |
|---|---|
| **Prayer times** | 5 prayers + Shuruq (sunrise), computed **inside the app** (offline) with the PrayTimes.org astronomical algorithm; 7 calculation methods; Standard/Hanafi Asr; ±30 min manual adjustment per prayer; timezone sanity-check derived from coordinates (DST-aware) |
| **Monthly table** | **Monthly prayer-times dialog** (☰ menu or Ctrl+M) — every day of the month + Hijri date, with a Print button to pin it on the mosque wall |
| **Jumu'a mode** | Separate Friday khutba time replaces the Dhuhr iqama; ticker reminder + on-screen alert 30 min before khutba |
| **Iqama** | Countdown to iqama with circular progress ring; per-prayer offset (minutes after adhan) or fixed clock times |
| **Next prayer** | Large countdown (HH:MM:SS) + progress ring; next prayer highlighted in green; past prayers shown in a softer color |
| **Clock & dates** | Big digital clock (12/24h, seconds on/off), Hijri + Gregorian dates, Hijri day offset (±3), Ramadan countdown, Monday/Thursday fasting reminders |
| **Hijri events** | Ramadan, Eid al-Fitr, Eid al-Adha, Arafah, Ashura, Mawlid & Islamic New Year auto-detected and announced in the ticker (≤30 days ahead) |
| **Adhan & alerts** | Plays bundled WAV/MP3 files (adhan, iqama, alert) — or synthesized chimes if files are missing, so sound always works; **230+ named adhan recordings** (Makkah, Madinah, Al-Aqsa, Cairo, Istanbul, Morocco and many more) in a searchable Settings list, plus **custom WAV/MP3 per prayer**; volume control; pre-Fajr alert |
| **Reminders** | Tawkit keyword syntax: `15.BEFORE.FAJR.(message)`, `10.AFTER.MAGHRIB.(msg)`, `NISF.(msg)` (midnight), `TULT.(msg)` (last third of night). 12-second on-screen message + beep |
| **Content** | Rotating Qur'an verses, hadith and adhkar (Arabic + translation + reference); azkar shown after each prayer; **verse-of-the-day corner widget** (changes once per day) |
| **Slides** | Your own slides (text with `\|` line breaks, `.jpg/.png` files, URLs) shown between prayers — folder: `~/.tawkit/slides/` |
| **Ticker** | Scrolling announcements bar (click to pause/resume); custom announcements + auto Ramadan countdown + Hijri event highlights |
| **Weather** | Current conditions + 7-day forecast from Open-Meteo.com (no API key); refreshes at startup and at midnight; keeps last data offline |
| **Auto-location** | On first launch the city is guessed from the public IP (ipwho.is, no key) so the app is usable immediately; the city picker stays available |
| **City database** | 130+ built-in cities (Morocco, Algeria, Saudi, Europe, Americas, Africa, Asia…), country → city picker, coordinates editable |
| **Display** | Full-screen kiosk (F11), background themes (night / green / blue) or your own image, screen dimming during prayer, **fade transitions** between content items, **live preview** of the dashboard in Settings, high-DPI font scaling for 4K screens |
| **Languages** | English, Français, العربية (RTL) — property bundles in `i18n/` |
| **Settings backup** | Export/import settings to a file (`settings.properties`) to copy to another screen |
| **Persistence** | All settings saved to `~/.tawkit/settings.properties` and restored on startup |
| **Robustness** | Single-instance lock (no two kiosks fighting), file logging to `~/.tawkit/logs/`, graceful fallbacks for every bundled resource, **JUnit test suite** for the astronomy/calendar/timezone core |
| **Installer** | `scripts/package-windows.ps1` / `scripts/package-linux.sh` build a real `.exe`/`.msi`/`.deb` installer with a bundled JRE via `jpackage` |

## Run

```bash
# Run directly
mvn compile exec:java

# Or build and run the JAR
mvn package
java -jar target/tawkit-desktop-1.0-SNAPSHOT.jar

# Run the test suite
mvn test

# Build a Windows installer (.exe with bundled JRE)
powershell -ExecutionPolicy Bypass -File scripts\package-windows.ps1
```

Requires **Java 21+** (tested with Maven 3.9).

## Controls

- **F11** — full screen / **ESC** — exit full screen
- **Ctrl+M** — monthly prayer-times table (also in the ☰ menu)
- **Ctrl+Q** — quit
- Click the **☰ button** — menu: Settings, Monthly times, Full screen, Quit
- Click the **next-prayer ring** to enlarge it
- Click the **ticker** to pause/resume scrolling

## Adhan sounds (optional)

Open **Settings → Audio** and pick a recording by name from *Adhan recitation*
(230+ recordings from Makkah, Madinah, Al-Aqsa, Cairo, Istanbul, Morocco and
many more) — it applies to every prayer. Type in *Find adhan* to narrow the
list. You can also choose a different WAV/MP3 file per prayer; the selected
recording is used when no per-prayer file is set.

Bundled audio files live in `src/main/resources/assets/audio/`:

```
assets/audio/adhan/adhan.wav              default adhan
assets/audio/adhan/catalog.txt            list shown in Settings
assets/audio/adhan/curated/*.mp3          featured recordings
assets/audio/adhan/library/*.mp3          the full collection
assets/audio/adhan/adhan_fajr.wav         Fajr adhan (optional)
assets/audio/adhan/short_adhan.wav        short adhan (optional)
assets/audio/iqama/short_iqama.wav        iqama call
assets/audio/alerts/alert.wav             alert sound
```

If a file is missing, a synthesized chime plays instead — sound always works.
The recordings come from the
[Kiwifu/adhan-mp3](https://github.com/Kiwifu/adhan-mp3) collection; see
`assets/audio/adhan/README.txt` for details.

## Add your own slides

In `~/.tawkit/slides/`:
- `slides.txt` — one slide per line: text (use `|` for line breaks), a URL, or an image filename
- drop `.jpg/.png` files directly in the folder — they are picked up automatically

## Project structure (MVC)

```
src/main/java/com/tawkit/
├── Main.java                     entry point (single-instance lock, UI scaling)
├── GenerateIcon.java             one-shot generator for assets/icon.png
├── controller/AppController      wires everything together
├── model/                        plain data classes (prayer, settings, content, weather)
├── view/                         Swing UI (MainFrame, TawkitScreen, MonthlyTimesDialog,
│                                 SettingsDialog + live preview, SlidesPanel, UiScale, Icons…)
│   └── render/                   dashboard elements incl. VerseOfDayElement
├── service/
│   ├── prayer/                   PrayerTimeCalculator (astronomy), PrayerScheduler,
│   │                             HijriCalendarService (+ events), TimezoneCheck
│   ├── audio/                    AudioService (WAV + synth fallback + custom per-prayer files)
│   ├── weather/                  WeatherService (Open-Meteo, hand-rolled JSON parsing)
│   ├── location/                 GeoLocationService (IP auto-location, first launch)
│   ├── content/                  ContentService (verses/hadith/azkar/slides/verse-of-day)
│   ├── settings/                 SettingsService (persistence + export/import)
│   ├── scheduler/                ReminderService (custom alerts)
│   ├── city/                     CityService (built-in city database)
│   └── localization/             Messages (i18n EN/FR/AR + RTL, hardened fallbacks)
└── infrastructure/               Log (java.util.logging → ~/.tawkit/logs),
                                  ToneGenerator, SingleInstanceLock (FileLock)

src/test/java/                    JUnit 5: astronomy vs published references,
                                  Hijri conversions, timezone sanity, settings round-trip
scripts/                          package-windows.ps1 / package-linux.sh (jpackage)
```

Bundled data lives in `src/main/resources/`: `data/` (Qur'an, hadith, azkar, announcements, cities),
`i18n/` (language bundles), `assets/audio/` (sound folders).

## Team

Oussama, Amine, Oumayma & Karim — Java Swing course project.

Free as in free. Not for sale, like Tawkit.
