package com.tawkit.service.audio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Catalog of the adhan recordings bundled with the app.
 *
 * <p>Recordings are the calls to prayer from the
 * <a href="https://github.com/Kiwifu/adhan-mp3">Kiwifu/adhan-mp3</a>
 * collection. The list lives in
 * <code>/assets/audio/adhan/catalog.txt</code> as
 * {@code id|display name|classpath resource} lines, so adding a recording
 * only needs a file plus one line there.
 *
 * <p>The settings dialog shows {@link Entry#displayName()} and stores
 * {@link Entry#id()} in {@code AppSettings}.
 */
public final class AdhanCatalog {

    private static final String CATALOG_RESOURCE = "/assets/audio/adhan/catalog.txt";

    /** One selectable recording. {@code id} is stable and persisted. */
    public record Entry(String id, String displayName, String resource) {
        @Override
        public String toString() {
            return displayName;
        }
    }

    /** Empty id means "use the original bundled recording". */
    public static final Entry BUNDLED_DEFAULT = new Entry(
            "",
            "Default — Hassan II Mosque (bundled)",
            "/assets/audio/adhan/adhan.wav");

    private static final List<Entry> RECORDINGS = load();

    private AdhanCatalog() {
    }

    private static List<Entry> load() {
        List<Entry> entries = new ArrayList<>();
        InputStream raw = AdhanCatalog.class.getResourceAsStream(CATALOG_RESOURCE);
        if (raw == null) {
            com.tawkit.infrastructure.Log.warn(
                    "Adhan catalog missing at " + CATALOG_RESOURCE);
            return List.of();
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(raw, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\|", 3);
                if (parts.length != 3) {
                    com.tawkit.infrastructure.Log.warn(
                            "Ignoring malformed adhan catalog line: " + line);
                    continue;
                }
                entries.add(new Entry(parts[0].trim(), parts[1].trim(), parts[2].trim()));
            }
        } catch (IOException e) {
            com.tawkit.infrastructure.Log.warn(
                    "Adhan catalog unreadable (" + e.getMessage() + ")");
        }
        return List.copyOf(entries);
    }

    /** Every choice for the settings combo, default first. */
    public static List<Entry> choices() {
        List<Entry> all = new ArrayList<>(RECORDINGS.size() + 1);
        all.add(BUNDLED_DEFAULT);
        all.addAll(RECORDINGS);
        return all;
    }

    /** Bundled recordings only (no default entry). */
    public static List<Entry> recordings() {
        return RECORDINGS;
    }

    /** Resolves a persisted id; unknown or blank ids fall back to the default. */
    public static Entry byId(String id) {
        if (id == null || id.isBlank()) return BUNDLED_DEFAULT;
        for (Entry entry : RECORDINGS) {
            if (entry.id().equals(id)) return entry;
        }
        return BUNDLED_DEFAULT;
    }
}
