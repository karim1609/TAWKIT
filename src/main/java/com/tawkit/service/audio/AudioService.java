package com.tawkit.service.audio;

import com.tawkit.infrastructure.audio.ToneGenerator;
import com.tawkit.model.prayer.PrayerType;

import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.player.AudioDevice;
import javazoom.jl.player.JavaSoundAudioDevice;
import javazoom.jl.player.Player;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plays adhan, iqama and alert sounds.
 *
 * <p>WAV and MP3 files bundled in <code>/assets/audio/...</code> are played
 * when present; otherwise a synthesized chime is used so the app always makes
 * sound. Supported bundled files:
 * <pre>
 *   /assets/audio/adhan/adhan.wav        default full adhan
 *   /assets/audio/adhan/curated/*.mp3    selectable recordings (AdhanCatalog)
 *   /assets/audio/adhan/adhan_fajr.wav   Fajr-specific adhan (optional)
 *   /assets/audio/iqama/short_iqama.wav  short iqama
 *   /assets/audio/alerts/alert.wav       generic alert
 * </pre>
 * Playback runs on a background thread; sounds never block the UI.
 */
public class AudioService {

    public enum Sound { ADHAN, ADHAN_FAJR, SHORT_ADHAN, IQAMA, ALERT }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tawkit-audio");
        t.setDaemon(true);
        return t;
    });

    private volatile double volume = 1.0;
    private volatile boolean enabled = true;
    private volatile Clip currentClip;
    private volatile Player currentPlayer;
    /** Bundled recording chosen in the settings dialog (default = original WAV). */
    private volatile AdhanCatalog.Entry selectedAdhan = AdhanCatalog.BUNDLED_DEFAULT;
    private final java.util.EnumMap<PrayerType, String> customAdhanFiles =
            new java.util.EnumMap<>(PrayerType.class);

    public void setVolume(double volume) {
        this.volume = Math.max(0.0, Math.min(1.0, volume));
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Selects one of the bundled recordings; blank/unknown ids use the default. */
    public void setSelectedAdhan(String id) {
        this.selectedAdhan = AdhanCatalog.byId(id);
    }

    public AdhanCatalog.Entry getSelectedAdhan() {
        return selectedAdhan;
    }

    /**
     * Custom adhan file per prayer (change #6), WAV or MP3. An absolute or
     * relative file path; empty/missing falls back to the bundled sound.
     * Applied by the controller after settings change.
     */
    public void setCustomAdhanFile(PrayerType type, String path) {
        if (type == null) return;
        if (path == null || path.isBlank()) customAdhanFiles.remove(type);
        else customAdhanFiles.put(type, path.trim());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void play(Sound sound, PrayerType forPrayer) {
        if (!enabled) return;
        executor.submit(() -> {
            try {
                if (!playBundled(sound, forPrayer)) {
                    playFallback(sound);
                }
            } catch (Exception e) {
                com.tawkit.infrastructure.Log.warn("Audio playback failed: " + e.getMessage());
            }
        });
    }

    public void playAlert() {
        play(Sound.ALERT, null);
    }

    /** Stops whatever is currently playing (e.g. screen click). */
    public void stop() {
        Clip clip = currentClip;
        if (clip != null && clip.isRunning()) {
            clip.stop();
            clip.close();
        }
        Player player = currentPlayer;
        if (player != null) {
            player.close();
        }
    }

    // ------------------------------------------------------------ internals

    private boolean playBundled(Sound sound, PrayerType forPrayer) {
        boolean isAdhan = sound == Sound.ADHAN || sound == Sound.ADHAN_FAJR;

        // Custom per-prayer adhan file (change #6) takes priority.
        if (isAdhan && forPrayer != null) {
            String custom = customAdhanFiles.get(forPrayer);
            if (custom != null && playFile(custom)) return true;
        }

        // A recording picked by name in the settings dialog.
        AdhanCatalog.Entry chosen = selectedAdhan;
        if (isAdhan && chosen != null && !chosen.id().isBlank()) {
            if (playResource(chosen.resource())) return true;
        }

        if (playResource(resourcePath(sound, forPrayer))) return true;

        // Fajr has an optional dedicated file; fall back to the standard adhan.
        if (sound == Sound.ADHAN_FAJR) {
            return playResource("/assets/audio/adhan/adhan.wav");
        }
        return false;
    }

    /** Plays a classpath resource; false when it is missing or unreadable. */
    private boolean playResource(String path) {
        URL url = getClass().getResource(path);
        if (url == null) return false;
        try {
            if (isMp3(path)) return playMp3(url);
            return playClip(url);
        } catch (Exception e) {
            com.tawkit.infrastructure.Log.warn("Audio unreadable " + path
                    + " (" + e.getMessage() + ") — using fallback");
            return false;
        }
    }

    /** Plays a user-provided audio file from the filesystem; false when unusable. */
    private boolean playFile(String path) {
        try {
            URL url = new File(path).toURI().toURL();
            if (isMp3(path)) return playMp3(url);
            return playClip(url);
        } catch (Exception e) {
            com.tawkit.infrastructure.Log.warn("Custom adhan unreadable " + path
                    + " (" + e.getMessage() + ") — falling back to bundled");
            return false;
        }
    }

    private static boolean isMp3(String path) {
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".mp3");
    }

    private static String resourcePath(Sound sound, PrayerType forPrayer) {
        return switch (sound) {
            case ADHAN -> "/assets/audio/adhan/adhan.wav";
            case ADHAN_FAJR -> forPrayer == PrayerType.FAJR
                    ? "/assets/audio/adhan/adhan_fajr.wav"
                    : "/assets/audio/adhan/adhan.wav";
            case SHORT_ADHAN -> "/assets/audio/adhan/short_adhan.wav";
            case IQAMA -> "/assets/audio/iqama/short_iqama.wav";
            case ALERT -> "/assets/audio/alerts/alert.wav";
        };
    }

    private boolean playClip(URL url) throws IOException,
            UnsupportedAudioFileException, LineUnavailableException {
        try (InputStream raw = url.openStream();
             AudioInputStream ais = AudioSystem.getAudioInputStream(new BufferedInputStream(raw))) {

            Clip clip = AudioSystem.getClip();
            clip.open(ais);
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) {
                    clip.close();
                }
            });
            currentClip = clip;
            clip.start();
            return true;
        }
    }

    private boolean playMp3(URL url) throws IOException, JavaLayerException {
        // Player.play() blocks for the whole recording, so it runs on the
        // single audio executor; stop() closes it from another thread.
        AudioDevice device = new VolumeAudioDevice(new JavaSoundAudioDevice(), volume);
        try (InputStream in = new BufferedInputStream(url.openStream())) {
            Player player = new Player(in, device);
            currentPlayer = player;
            try {
                player.play();
            } finally {
                currentPlayer = null;
            }
            return true;
        }
    }

    /** Scales MP3 samples by the volume slider before they reach the sound card. */
    private static final class VolumeAudioDevice implements AudioDevice {
        private final AudioDevice delegate;
        private final double volume;

        VolumeAudioDevice(AudioDevice delegate, double volume) {
            this.delegate = delegate;
            this.volume = volume;
        }

        @Override
        public void open(Decoder decoder) throws JavaLayerException {
            delegate.open(decoder);
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void write(short[] samples, int offset, int length) throws JavaLayerException {
            if (volume >= 0.999 || length <= 0) {
                delegate.write(samples, offset, length);
                return;
            }
            short[] scaled = new short[length];
            for (int i = 0; i < length; i++) {
                scaled[i] = (short) Math.round(samples[offset + i] * volume);
            }
            delegate.write(scaled, 0, length);
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public void flush() {
            delegate.flush();
        }

        @Override
        public int getPosition() {
            return delegate.getPosition();
        }
    }

    private void playFallback(Sound sound) throws LineUnavailableException {
        switch (sound) {
            case ADHAN, ADHAN_FAJR -> ToneGenerator.playSequence(ToneGenerator.adhanChime(), volume);
            case IQAMA -> ToneGenerator.playSequence(ToneGenerator.iqamaChime(), volume);
            default -> ToneGenerator.playSequence(ToneGenerator.alertBeeps(), volume);
        }
    }
}
