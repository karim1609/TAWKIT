package com.tawkit.service.settings;

import com.tawkit.model.prayer.Location;
import com.tawkit.model.prayer.PrayerType;
import com.tawkit.model.settings.AppSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip persistence tests: every new setting (#5, #6) must survive a
 * save → load cycle.
 */
class SettingsServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void jumuaSettingsRoundTrip() {
        SettingsService svc = new SettingsService(tempDir.resolve("s.properties"));
        AppSettings saved = new AppSettings();
        saved.setJumuaEnabled(false);
        saved.setJumuaKhutbaTime(LocalTime.of(13, 15));
        svc.save(saved);

        AppSettings loaded = svc.load();
        assertEquals(false, loaded.isJumuaEnabled());
        assertEquals(LocalTime.of(13, 15), loaded.getJumuaKhutbaTime());
    }

    @Test
    void adhanSoundFilesRoundTrip() {
        SettingsService svc = new SettingsService(tempDir.resolve("s.properties"));
        AppSettings saved = new AppSettings();
        saved.setAdhanSoundFile(PrayerType.FAJR, "C:/sounds/fajr.wav");
        saved.setAdhanSoundFile(PrayerType.MAGHRIB, "C:/sounds/maghrib.wav");
        saved.setAdhanSoundFile(PrayerType.DHUHR, ""); // blank must not persist
        svc.save(saved);

        AppSettings loaded = svc.load();
        assertEquals("C:/sounds/fajr.wav", loaded.getAdhanSoundFile(PrayerType.FAJR));
        assertEquals("C:/sounds/maghrib.wav", loaded.getAdhanSoundFile(PrayerType.MAGHRIB));
        assertEquals("", loaded.getAdhanSoundFile(PrayerType.DHUHR));
    }

    @Test
    void adhanSoundIdRoundTrip() {
        SettingsService svc = new SettingsService(tempDir.resolve("s.properties"));
        AppSettings saved = new AppSettings();
        saved.setAdhanSoundId("makkah_haram_al_maki");
        svc.save(saved);

        AppSettings loaded = svc.load();
        assertEquals("makkah_haram_al_maki", loaded.getAdhanSoundId());
    }

    @Test
    void legacyFileWithoutNewKeysLoadsDefaults() throws Exception {
        SettingsService svc = new SettingsService(tempDir.resolve("s.properties"));
        AppSettings withData = new AppSettings();
        withData.setMosqueName("Old mosque"); // something to force a file
        svc.save(withData);
        // Simulate legacy file: strip new keys
        var file = tempDir.resolve("s.properties");
        var lines = java.nio.file.Files.readAllLines(file).stream()
                .filter(l -> !l.startsWith("jumua") && !l.startsWith("adhanSound."))
                .toList();
        java.nio.file.Files.write(file, lines);

        AppSettings loaded = svc.load();
        assertEquals("Old mosque", loaded.getMosqueName());
        assertTrue(loaded.isJumuaEnabled(), "Jumu'a defaults to enabled");
        assertEquals("", loaded.getAdhanSoundFile(PrayerType.FAJR));
    }

    @Test
    void locationRoundTripPreserved() {
        SettingsService svc = new SettingsService(tempDir.resolve("s.properties"));
        AppSettings saved = new AppSettings();
        saved.setLocation(new Location("Rabat", "Morocco", 34.02, -6.83, 1.0));
        svc.save(saved);

        AppSettings loaded = svc.load();
        Location loc = loaded.getLocation();
        assertEquals("Rabat", loc.getName());
        assertEquals(34.02, loc.getLatitude(), 0.0001);
        assertEquals(-6.83, loc.getLongitude(), 0.0001);
        assertEquals(1.0, loc.getTimezone(), 0.0001);
    }

    @Test
    void temperatureUnitAndCountryCodeRoundTrip() {
        SettingsService svc = new SettingsService(tempDir.resolve("s.properties"));
        AppSettings saved = new AppSettings();
        saved.setTemperatureUnit('F');
        saved.setLocation(new Location("Vienna", "Austria", 48.21, 16.37, 1.0));
        saved.getLocation().setCountryCode("at");
        svc.save(saved);

        AppSettings loaded = svc.load();
        assertEquals('F', loaded.getTemperatureUnit());
        assertEquals("AT", loaded.getLocation().getCountryCode()); // normalized upper-case
    }

    @Test
    void legacyLocationWithoutCountryCodeStillLoads() throws Exception {
        SettingsService svc = new SettingsService(tempDir.resolve("s.properties"));
        AppSettings saved = new AppSettings();
        saved.setLocation(new Location("Cairo", "Egypt", 30.04, 31.24, 2.0));
        svc.save(saved);
        // Strip the countryCode field to simulate a pre-widget settings file
        var file = tempDir.resolve("s.properties");
        var lines = java.nio.file.Files.readAllLines(file).stream()
                .filter(l -> !l.contains("temperatureUnit"))
                .toList();
        java.nio.file.Files.write(file, lines);

        AppSettings loaded = svc.load();
        assertEquals('C', loaded.getTemperatureUnit()); // default unit
        assertEquals("Cairo", loaded.getLocation().getName());
        assertEquals("", loaded.getLocation().getCountryCode());
    }

    @Test
    void listsRoundTrip() {
        SettingsService svc = new SettingsService(tempDir.resolve("s.properties"));
        AppSettings saved = new AppSettings();
        saved.setAnnouncements(List.of("Cleanliness is half of faith"));
        saved.setReminders(List.of("15.BEFORE.FAJR.(Prepare)"));
        svc.save(saved);

        AppSettings loaded = svc.load();
        assertEquals(List.of("Cleanliness is half of faith"), loaded.getAnnouncements());
        assertEquals(List.of("15.BEFORE.FAJR.(Prepare)"), loaded.getReminders());
    }
}
