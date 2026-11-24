package com.tawkit.controller;

import com.tawkit.model.content.ContentItem;
import com.tawkit.model.content.Slide;
import com.tawkit.model.prayer.DailyPrayerTimes;
import com.tawkit.model.prayer.PrayerEntry;
import com.tawkit.model.prayer.PrayerType;
import com.tawkit.model.settings.AppSettings;
import com.tawkit.model.weather.WeatherData;
import com.tawkit.service.audio.AudioService;
import com.tawkit.service.content.ContentService;
import com.tawkit.service.format.TimeText;
import com.tawkit.service.prayer.HijriCalendarService;
import com.tawkit.service.prayer.PrayerScheduler;
import com.tawkit.service.prayer.PrayerTimeCalculator;
import com.tawkit.service.scheduler.ReminderService;
import com.tawkit.service.settings.SettingsService;
import com.tawkit.service.weather.WeatherService;
import com.tawkit.view.MainFrame;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Central controller: loads settings, wires the scheduler, audio, weather,
 * content and reminders services to the MainFrame.
 */
public class AppController implements PrayerScheduler.Listener, WeatherService.Listener {

    private final MainFrame frame;
    private final SettingsService settingsService;
    private final AppSettings settings;
    private final AudioService audio;
    private final ContentService content;
    private final HijriCalendarService hijri;
    private final PrayerTimeCalculator calculator;
    private final ReminderService reminders;
    private final WeatherService weatherService;

    private PrayerScheduler scheduler;
    private boolean showingAzkar = false;
    private boolean preFajrAlertFired = false;
    private boolean jumuaAlertFired = false;
    private final com.tawkit.service.location.PreciseLocationService preciseLocation =
            new com.tawkit.service.location.PreciseLocationService();
    private final com.tawkit.service.location.GeocodingService geocoding =
            new com.tawkit.service.location.GeocodingService();
    private java.time.Instant lastWeatherRefresh = java.time.Instant.EPOCH; // 30-min cadence

    /**
     * Composition root: all services are created here and injected through
     * the constructor — no hidden statics, everything replaceable for tests.
     */
    public AppController(MainFrame frame) {
        this.frame = frame;
        this.settingsService = new SettingsService();
        this.audio = new AudioService();
        this.content = new ContentService();
        this.hijri = new HijriCalendarService();
        this.calculator = new PrayerTimeCalculator();
        this.reminders = new ReminderService();
        this.weatherService = new WeatherService(this);
        this.settings = settingsService.load();
        settingsService.save(settings); // normalize defaults to disk
    }

    public AppSettings getSettings() {
        return settings;
    }

    /** Starts the whole application. */
    public void start() {
        scheduler = new PrayerScheduler(settings, calculator);
        scheduler.setListener(this);

        applySettingsToFrame();
        frame.setPrayerTimes(scheduler.getToday());

        if (settings.isWeatherEnabled() && settings.getLocation() != null) {
            weatherService.refreshAsync(settings.getLocation().getLatitude(),
                    settings.getLocation().getLongitude());
        }

        // Auto-location on first launch (#7): no city configured yet → IP lookup
        if (settings.getLocation() == null) {
            useMyLocationQuietly();
        }

        // Hamburger-menu actions (weather/location widget)
        frame.setHeaderMenuActions(new MainFrame.HeaderMenuActions() {
            @Override public void useMyLocation() { useMyLocationInteractive(); }
            @Override public void searchCity() { openCitySearch(); }
            @Override public void refreshWeather() { refreshWeatherNow(); }
            @Override public void toggleTemperatureUnit() { switchTemperatureUnit(); }
            @Override public void openSettings() { frame.openSettings(); }
            @Override public void openMonthly() { frame.openMonthly(); }
            @Override public void toggleFullScreen() { frame.toggleFullScreen(); }
            @Override public void quit() { System.exit(0); }
        });
    }

    /** Silent location lookup (first launch): sets location if found. */
    private void useMyLocationQuietly() {
        preciseLocation.locateAsync(new com.tawkit.service.location.PreciseLocationService.Callback() {
            @Override
            public void onLocation(com.tawkit.service.location.PreciseLocationService.Fix fix) {
                applyDetectedLocation(fix, "Auto-location");
            }

            @Override
            public void onFailure(String message, boolean permissionBlocked) {
                com.tawkit.infrastructure.Log.warn("Auto-location skipped: " + message);
            }
        });
    }

    /** Location lookup triggered from the menu, with feedback dialogs. */
    private void useMyLocationInteractive() {
        frame.setLocationLabel("Detecting location…");
        preciseLocation.locateAsync(new com.tawkit.service.location.PreciseLocationService.Callback() {
            @Override
            public void onLocation(com.tawkit.service.location.PreciseLocationService.Fix fix) {
                applyDetectedLocation(fix, "Location set");
                frame.setLocationLabel(fix.location().getDisplayName());
                if (fix.permissionBlocked()) {
                    showApproximateLocationNotice(fix);
                }
            }

            @Override
            public void onFailure(String message, boolean permissionBlocked) {
                var current = settings.getLocation();
                frame.setLocationLabel(current == null ? "No location set" : current.getDisplayName());
                showLocationFailure(message, permissionBlocked);
            }
        });
    }

    /** Stores a detected fix and refreshes prayer times, clock and weather. */
    private void applyDetectedLocation(
            com.tawkit.service.location.PreciseLocationService.Fix fix, String logPrefix) {
        var loc = fix.location();
        settings.setLocation(loc);
        settingsService.save(settings);
        settingsChanged();
        com.tawkit.infrastructure.Log.info(logPrefix + ": " + loc.getDisplayName()
                + " (" + loc.getLatitude() + ", " + loc.getLongitude()
                + ", " + fix.accuracyText() + ")");
    }

    /**
     * Actionable error dialog: on Windows the usual cause is the machine-wide
     * "Location services / Let desktop apps access your location" setting.
     */
    private void showLocationFailure(String message, boolean permissionBlocked) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String text = permissionBlocked
                ? "Windows is blocking location access.\n\n"
                        + "Turn on Settings → Privacy & security → Location:\n"
                        + "  • Location services\n"
                        + "  • Let desktop apps access your location\n\n"
                        + "Then press “Use my location” again."
                : "Could not detect your location automatically:\n" + message
                        + "\n\nUse “Search city…” or type the coordinates manually.";
        String[] options = (windows && permissionBlocked)
                ? new String[]{"Open Windows settings", "Cancel"}
                : new String[]{"OK"};
        int choice = javax.swing.JOptionPane.showOptionDialog(frame, text,
                "Location unavailable", javax.swing.JOptionPane.DEFAULT_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        if (windows && permissionBlocked && choice == 0) {
            openWindowsLocationSettings();
        }
    }

    /**
     * A position was applied, but it is the network approximation because
     * Windows blocks the exact one — tell the user how to get the exact fix.
     */
    private void showApproximateLocationNotice(
            com.tawkit.service.location.PreciseLocationService.Fix fix) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String text = "Location set to " + fix.location().getDisplayName()
                + " — approximate network position.\n\n"
                + (windows
                        ? "For the exact position, turn on Settings → Privacy & security → "
                                + "Location:\n  • Location services\n"
                                + "  • Let desktop apps access your location\n\n"
                                + "Then choose “Use my location” again."
                        : "The exact position needs a system location service that is not "
                                + "available on this platform.");
        String[] options = windows
                ? new String[]{"Open Windows settings", "OK"}
                : new String[]{"OK"};
        int choice = javax.swing.JOptionPane.showOptionDialog(frame, text,
                "Approximate location", javax.swing.JOptionPane.DEFAULT_OPTION,
                javax.swing.JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
        if (windows && choice == 0) {
            openWindowsLocationSettings();
        }
    }

    /** Opens the Windows page with "Location services" and desktop-app access. */
    private void openWindowsLocationSettings() {
        try {
            new ProcessBuilder("cmd", "/c", "start", "",
                    "ms-settings:privacy-location").start();
        } catch (java.io.IOException e) {
            com.tawkit.infrastructure.Log.warn("Cannot open Windows location settings ("
                    + e.getMessage() + ")");
        }
    }

    /** City search dialog → real coordinates + country + flag via geocoding API. */
    private void openCitySearch() {
        com.tawkit.view.CitySearchDialog dialog =
                new com.tawkit.view.CitySearchDialog(frame, place -> {
                    var loc = new com.tawkit.model.prayer.Location(
                            place.name(), place.country(),
                            place.latitude(), place.longitude(), 0.0);
                    loc.setCountryCode(place.countryCode());
                    // Accurate zone from the geocoding result (falls back to coords)
                    if (!place.timezone().isBlank()) {
                        try {
                            loc.setTimezone(java.time.ZoneOffset.from(
                                    java.time.ZoneId.of(place.timezone()).getRules()
                                            .getOffset(java.time.Instant.now()))
                                    .getTotalSeconds() / 3600.0);
                        } catch (Exception ignored) {
                        }
                    }
                    settings.setLocation(loc);
                    settingsService.save(settings);
                    settingsChanged();
                });
        dialog.setVisible(true);
    }

    /** Manual refresh from the menu. */
    private void refreshWeatherNow() {
        var loc = settings.getLocation();
        if (loc == null) return;
        lastWeatherRefresh = java.time.Instant.now();
        weatherService.refreshAsync(loc.getLatitude(), loc.getLongitude());
    }

    /** °C ⇄ °F toggle from the menu (persisted immediately). */
    private void switchTemperatureUnit() {
        settings.setTemperatureUnit(settings.getTemperatureUnit() == 'C' ? 'F' : 'C');
        settingsService.save(settings);
        applySettingsToFrame(); // re-renders widget with converted temperature
    }

    /** Called after the settings dialog was saved. */
    public void settingsChanged() {
        settingsService.save(settings);
        applySettingsToFrame();
        if (scheduler != null) {
            scheduler.recompute();
            frame.setPrayerTimes(scheduler.getToday());
        }
        if (settings.isWeatherEnabled() && settings.getLocation() != null) {
            weatherService.refreshAsync(settings.getLocation().getLatitude(),
                    settings.getLocation().getLongitude());
            lastWeatherRefresh = java.time.Instant.now();
        } else {
            // Weather turned off (or no location): clear the stale display
            frame.setWeather(null);
        }
    }

    /** Real ISO code for the flag: stored one, else derived from the country name. */
    private String resolveFlagIso(com.tawkit.model.prayer.Location loc) {
        if (loc.getCountryCode() != null && !loc.getCountryCode().isBlank()) {
            return loc.getCountryCode();
        }
        return com.tawkit.service.city.CountryCodes.fromCountryName(loc.getCountry());
    }

    private void applySettingsToFrame() {
        com.tawkit.service.localization.Messages.setLanguage(settings.getLanguage());
        frame.setTitle(com.tawkit.service.localization.Messages.get("app.title"));
        frame.setMosqueName(settings.getMosqueName());

        var loc = settings.getLocation();
        if (loc != null) {
            frame.setCityInfo(loc.getName(), loc.getLatitude(), loc.getLongitude());
            frame.setLocationLabel(loc.getDisplayName());
            frame.setCountryFlag(resolveFlagIso(loc));
            // Keep the large dashboard clock synchronized with the selected city.
            // Without this call, prayer calculations can use the new timezone
            // while the displayed clock continues using the previous/system zone.
            frame.setLocationTimezone(PrayerScheduler.resolveTimezone(loc));
        } else {
            frame.setCityInfo("", 0, 0);
            frame.setLocationLabel("No location set");
            frame.setCountryFlag("");
            double systemOffset = java.time.ZoneOffset.from(
                    java.time.ZoneId.systemDefault().getRules().getOffset(java.time.Instant.now())
            ).getTotalSeconds() / 3600.0;
            frame.setLocationTimezone(systemOffset);
        }
        frame.setUse24Hour(settings.isUse24HourClock());
        frame.setShowSeconds(settings.isShowSeconds());
        frame.setLanguage(settings.getLanguage());
        frame.setTemperatureUnit(settings.getTemperatureUnit()); // °C/°F widget

        hijri.setOffsetDays(settings.getHijriOffsetDays());
        updateDates();

        if (settings.getBackgroundTheme().equals("custom")
                && !settings.getCustomBackgroundPath().isBlank()) {
            frame.applyCustomBackground(settings.getCustomBackgroundPath());
        } else {
            frame.applyTheme(settings.getBackgroundTheme());
        }
        content.loadAnnouncementsFor(settings.getLanguage());
        frame.setTickerMessages(buildTickerMessages());
        frame.setContentItems(buildDefaultContent());
        frame.setRotationSeconds(settings.getContentRotationSeconds());
        frame.setSlides(settings.isSlidesEnabled() ? content.getSlides() : List.of());
        frame.setVerseOfDay(content.getVerseOfDay(LocalDate.now()));

        audio.setEnabled(settings.isAdhanEnabled());
        audio.setVolume(settings.getAudioVolume());
        audio.setSelectedAdhan(settings.getAdhanSoundId());
        for (var type : com.tawkit.model.prayer.PrayerType.values()) {
            audio.setCustomAdhanFile(type, settings.getAdhanSoundFile(type)); // #6
        }
        reminders.setRules(settings.getReminders());
    }

    private void updateDates() {
        LocalDate today = LocalDate.now();
        frame.setGregorianDate(today);
        frame.setHijriDate(hijri.toHijri(today));
    }

    private List<String> buildTickerMessages() {
        List<String> msgs = new ArrayList<>(content.getAnnouncements());
        if (!settings.getAnnouncements().isEmpty()) {
            msgs.addAll(settings.getAnnouncements());
        }
        long daysToRamadan = hijri.daysUntilRamadan(LocalDate.now());
        if (daysToRamadan > 0 && daysToRamadan < 60) {
            msgs.add("Ramadan starts in " + daysToRamadan + " day(s)");
        }
        // Hijri event highlights (#9) — Ramadan/Eids/Ashura/Mawlid etc.
        msgs.addAll(hijri.upcomingEventMessages(LocalDate.now(), 30));
        if (HijriCalendarService.isFastingDay(LocalDate.now())) {
            msgs.add("Fasting day: Monday / Thursday");
        }
        // Jumu'a reminder (#5)
        if (settings.isJumuaEnabled() && LocalDate.now().getDayOfWeek() == java.time.DayOfWeek.FRIDAY) {
            if (settings.getJumuaKhutbaTime() != null) {
                msgs.add("Jumu'a today — khutba at " + settings.getJumuaKhutbaTime());
            }
        }
        return msgs;
    }

    private List<ContentItem> buildDefaultContent() {
        List<ContentItem> items = new ArrayList<>();
        items.addAll(content.getVerses());
        items.addAll(content.getHadiths());
        items.addAll(content.getAdhkar());
        return items;
    }

    // ------------------------------------------------- scheduler callbacks

    @Override
    public void onTick(PrayerScheduler.TickSnapshot snapshot) {
        // Prayer grid: highlight next, soften past
        frame.setPrayerTimes(scheduler.getToday());
        frame.setHighlightedPrayer(snapshot.nextPrayer);

        if (snapshot.state == PrayerScheduler.State.IQAMA_COUNTDOWN && snapshot.secondsToIqama >= 0) {
            frame.setIqamaCountdown("Iqama in " + TimeText.mmss(snapshot.secondsToIqama));
        } else {
            frame.setIqamaCountdown("");
        }

        // Next-prayer ring
        String name = snapshot.nextPrayer != null ? snapshot.nextPrayer.getDisplayName() : "";
        String countdown = snapshot.secondsToNextAdhan >= 0
                ? TimeText.hhmmss(snapshot.secondsToNextAdhan) : "--:--:--";
        String iqamaInfo = snapshot.state == PrayerScheduler.State.IQAMA_COUNTDOWN
                ? "Iqama " + TimeText.mmss(snapshot.secondsToIqama) : "";
        frame.setNextPrayer(name, countdown, ringProgress(snapshot), iqamaInfo);

        // Dimming during prayer
        boolean dim = settings.isScreenDimDuringPrayer()
                && snapshot.state != PrayerScheduler.State.TO_ADHAN;
        frame.setDimmed(dim);

        // Reminders
        for (String message : reminders.check(LocalTime.now(), snapshot.date, scheduler.getToday())) {
            frame.showAlertMessage(message);
            audio.playAlert();
        }

        // Built-in pre-Fajr alert
        if (settings.isPreFajrAlertEnabled() && snapshot.nextPrayer == PrayerType.FAJR
                && snapshot.secondsToNextAdhan >= 0) {
            long target = settings.getPreFajrAlertMinutes() * 60L;
            if (snapshot.secondsToNextAdhan <= target && snapshot.secondsToNextAdhan > target - 60) {
                if (!preFajrAlertFired) {
                    preFajrAlertFired = true;
                    frame.showAlertMessage("Fajr in "
                            + snapshot.secondsToNextAdhan / 60 + " minutes");
                    audio.playAlert();
                }
            } else if (snapshot.secondsToNextAdhan > target + 300) {
                preFajrAlertFired = false;
            }
        }

        // Built-in Jumu'a reminder (#5): alert 30 min before the khutba on Fridays
        if (settings.isJumuaEnabled() && snapshot.date.getDayOfWeek() == java.time.DayOfWeek.FRIDAY
                && snapshot.nextPrayer == PrayerType.DHUHR && snapshot.secondsToNextAdhan >= 0) {
            if (snapshot.secondsToNextAdhan <= 30 * 60L && snapshot.secondsToNextAdhan > 29 * 60L
                    && !jumuaAlertFired) {
                jumuaAlertFired = true;
                frame.showAlertMessage("Jumu'a in " + snapshot.secondsToNextAdhan / 60 + " minutes");
                audio.playAlert();
            } else if (snapshot.secondsToNextAdhan > 35 * 60L) {
                jumuaAlertFired = false;
            }
        }

        // Restore default content once the praying window is over
        if (snapshot.state == PrayerScheduler.State.TO_ADHAN && showingAzkar) {
            showingAzkar = false;
            frame.setContentItems(buildDefaultContent());
        }

        // Sensible weather refresh cadence (every 30 min) — not every tick
        if (settings.isWeatherEnabled() && settings.getLocation() != null
                && java.time.Duration.between(lastWeatherRefresh, java.time.Instant.now())
                        .toMinutes() >= 30) {
            refreshWeatherNow();
        }
    }

    @Override
    public void onAdhan(PrayerType prayer) {
        if (!settings.isAdhanEnabled()) return;
        audio.play(prayer == PrayerType.FAJR
                ? AudioService.Sound.ADHAN_FAJR : AudioService.Sound.ADHAN, prayer);
        // Show azkar after prayer, like Tawkit
        if (!content.getAdhkar().isEmpty()) {
            showingAzkar = true;
            frame.setContentItems(content.getAdhkar());
        }
    }

    @Override
    public void onIqama(PrayerType prayer) {
        if (!settings.isAdhanEnabled()) return;
        audio.play(AudioService.Sound.IQAMA, prayer);
    }

    @Override
    public void onDayChanged(LocalDate newDay) {
        updateDates();
        frame.setVerseOfDay(content.getVerseOfDay(newDay)); // #8 — daily verse
        content.loadAnnouncementsFor(settings.getLanguage());
        frame.setTickerMessages(buildTickerMessages());
        if (settings.isWeatherEnabled() && settings.getLocation() != null) {
            weatherService.refreshAsync(settings.getLocation().getLatitude(),
                    settings.getLocation().getLongitude());
        }
    }

    // --------------------------------------------------- weather callbacks

    @Override
    public void onWeatherLoading() {
        javax.swing.SwingUtilities.invokeLater(frame::setWeatherLoading);
    }

    @Override
    public void onWeatherUpdated(WeatherData data) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            frame.setWeather(data);
            frame.setWeatherUpdatedLabel("Updated "
                    + java.time.LocalTime.now().format(
                            java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
        });
        lastWeatherRefresh = java.time.Instant.now();
    }

    @Override
    public void onWeatherError(String message) {
        com.tawkit.infrastructure.Log.warn("Weather: " + message);
        javax.swing.SwingUtilities.invokeLater(() -> frame.setWeatherError(message));
    }

    // -------------------------------------------------------------- helpers

    /**
     * Fraction of the interval between the previous prayer's adhan and the
     * next prayer's adhan that has already elapsed — drives the ring.
     */
    private double ringProgress(PrayerScheduler.TickSnapshot snapshot) {
        if (snapshot.nextPrayer == null || snapshot.secondsToNextAdhan < 0) return 0;
        PrayerType[] order = PrayerType.values();
        int idx = snapshot.nextPrayer.ordinal();

        DailyPrayerTimes sourceDay = scheduler.getToday();
        LocalTime prevAdhan;
        if (idx == 0) {
            // Fajr: previous is today's Isha
            PrayerEntry isha = sourceDay.get(PrayerType.ISHA);
            if (isha == null || isha.getAdhanTime() == null) return 0;
            prevAdhan = isha.getAdhanTime();
        } else {
            PrayerEntry prev = sourceDay.get(order[idx - 1]);
            if (prev == null || prev.getAdhanTime() == null) return 0;
            prevAdhan = prev.getAdhanTime();
        }

        long interval;
        if (idx == 0) {
            interval = Duration.between(prevAdhan, LocalTime.MAX).getSeconds() + 1
                    + Duration.between(LocalTime.MIDNIGHT, snapshot.nextAdhanTime).getSeconds();
        } else {
            interval = Duration.between(prevAdhan, snapshot.nextAdhanTime).getSeconds();
        }
        if (interval <= 0) return 0;
        double elapsed = interval - snapshot.secondsToNextAdhan;
        return Math.max(0.0, Math.min(1.0, elapsed / interval));
    }

    public ContentService getContentService() {
        return content;
    }
}
