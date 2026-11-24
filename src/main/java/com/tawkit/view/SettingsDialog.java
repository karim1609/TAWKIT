package com.tawkit.view;

import com.tawkit.model.prayer.AsrMethod;
import com.tawkit.model.prayer.CalculationMethod;
import com.tawkit.model.prayer.IqamaSettings;
import com.tawkit.model.prayer.Location;
import com.tawkit.model.prayer.PrayerType;
import com.tawkit.model.settings.AppSettings;
import com.tawkit.service.city.CityService;
import com.tawkit.service.city.CountryCodes;
import com.tawkit.service.location.PreciseLocationService;
import com.tawkit.service.audio.AdhanCatalog;
import com.tawkit.service.audio.AudioService;
import com.tawkit.service.settings.SettingsService;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicSliderUI;
import javax.swing.plaf.basic.BasicTextFieldUI;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Full settings dialog, Tawkit-style: location with city picker, prayer
 * calculation, iqama (offset or fixed), audio, display, content, reminders
 * and settings backup (export/import).
 *
 * <p>The chrome follows the kiosk's premium language: deep charcoal,
 * restrained gold accents, serif display type and soft rounded surfaces.
 */
public class SettingsDialog extends JDialog {

    private static final Color SETTINGS_BG = new Color(0x0A, 0x0B, 0x0C);
    private static final Color SETTINGS_SURFACE = new Color(0x11, 0x12, 0x14);
    private static final Color SETTINGS_CARD = new Color(0x15, 0x16, 0x18);
    private static final Color SETTINGS_FIELD = new Color(0x1E, 0x1F, 0x21);
    private static final Color SETTINGS_ACCENT = TawkitTheme.GOLDEN_BEIGE;
    private static final Color SETTINGS_ACCENT_LIGHT = new Color(0xE1, 0xC4, 0x86);
    private static final Color SETTINGS_TEXT = TawkitTheme.WARM_WHITE;
    private static final Color SETTINGS_MUTED = new Color(0xA7, 0xA3, 0x99);
    private static final Color SETTINGS_DIM = new Color(0x7D, 0x7A, 0x72);
    private static final Color SETTINGS_LINE = new Color(0x2C, 0x2D, 0x2F);
    private static final Color SETTINGS_LINE_WARM = new Color(0x42, 0x3C, 0x2E);

    private static final int CARD_ARC = 18;

    private static final PrayerType[] IQAMA_PRAYERS = {
            PrayerType.FAJR, PrayerType.DHUHR, PrayerType.ASR, PrayerType.MAGHRIB, PrayerType.ISHA
    };

    /** Icon + supporting copy for one settings section. */
    private record SectionInfo(String title, Glyph glyph, String description) {
    }

    private static final Map<String, SectionInfo> SECTION_INFO = Map.of(
            "Location", new SectionInfo("Location", Glyph.LOCATION,
                    "Set the place, coordinates and timezone used for prayer times."),
            "Prayer", new SectionInfo("Prayer calculation", Glyph.PRAYER,
                    "Choose how prayer times are calculated and fine-tune each prayer."),
            "Iqama", new SectionInfo("Iqama", Glyph.IQAMA,
                    "Define congregation timing after the adhan or at a fixed clock time."),
            "Audio", new SectionInfo("Adhan & sound", Glyph.AUDIO,
                    "Select the recitation, set the volume, and configure prayer alerts."),
            "Display", new SectionInfo("Display", Glyph.DISPLAY,
                    "Shape the look of the kiosk and preview changes as you make them."),
            "Content", new SectionInfo("Content", Glyph.CONTENT,
                    "Control announcements, slides, weather and content rotation."),
            "Reminders", new SectionInfo("Reminders", Glyph.REMINDERS,
                    "Schedule sound alerts before and after prayer."),
            "Backup", new SectionInfo("Backup", Glyph.BACKUP,
                    "Copy this screen's configuration to another Tawkit display.")
    );

    private final CityService cityService = new CityService();
    /** Prevents intermediate JComboBox events while rebuilding the city list. */
    private boolean refreshingCities = false;

    // Location
    private final JTextField mosqueField = new JTextField(20);
    private final JComboBox<String> countryCombo = new JComboBox<>();
    private final JComboBox<String> cityCombo = new JComboBox<>();
    private final JTextField latField = new JTextField(8);
    private final JTextField lonField = new JTextField(8);
    private final JTextField timezoneField = new JTextField(5);
    private final JButton useMyLocationButton = new JButton("⌖ Use my location");
    private final JLabel locationStatus = styledLabel(new JLabel(" "));
    private final PreciseLocationService locationService = new PreciseLocationService();
    /**
     * Exact location returned by "Use my location" — carries the real city
     * name and ISO country code that the city combo cannot represent.
     */
    private Location detectedLocation;
    /** True while the dialog itself rebuilds the country/city combos. */
    private boolean suppressCityEvents = false;

    // Prayer
    private final JComboBox<CalculationMethod> methodCombo = new JComboBox<>(CalculationMethod.values());
    private final JComboBox<AsrMethod> asrCombo = new JComboBox<>(AsrMethod.values());
    private final Map<PrayerType, JSpinner> adjustSpinners = new LinkedHashMap<>();

    // Iqama
    private final Map<PrayerType, JSpinner> iqamaOffsets = new LinkedHashMap<>();
    private final Map<PrayerType, JCheckBox> fixedChecks = new LinkedHashMap<>();
    private final Map<PrayerType, JTextField> fixedFields = new LinkedHashMap<>();

    // Audio
    private final JCheckBox adhanCheck = new JCheckBox("Play adhan at prayer times", true);
    private final JSlider volumeSlider = new JSlider(0, 100, 100);
    private final JCheckBox preFajrAlertCheck = new JCheckBox("Alert before Fajr", true);
    private final JSpinner preFajrMinutes = new JSpinner(new SpinnerNumberModel(10, 1, 60, 1));
    /** Bundled recordings, chosen by name and applied to every prayer. */
    private final JComboBox<AdhanCatalog.Entry> adhanCombo = new JComboBox<>();
    /** Narrows the large recording list while typing. */
    private final JTextField adhanSearch = new JTextField(14);
    private AdhanCatalog.Entry lastAdhanSelection = AdhanCatalog.BUNDLED_DEFAULT;
    private boolean rebuildingAdhanChoices = false;
    private final AudioService previewAudio = new AudioService();
    /** Custom adhan file per prayer (#6) — empty text uses the selected recording. */
    private final Map<PrayerType, JTextField> adhanFileFields = new LinkedHashMap<>();
    private final JLabel adhanSelectionStatus = hintLabel("Using bundled adhan");

    // Jumu'a (#5)
    private final JCheckBox jumuaCheck = new JCheckBox("Jumu'a mode (khutba time replaces Dhuhr iqama on Fridays)", true);
    private final JTextField jumuaKhutbaField = new JTextField(6);

    // Display
    private final JCheckBox dimCheck = new JCheckBox("Dim screen during prayer", true);
    private final JSpinner dimMinutes = new JSpinner(new SpinnerNumberModel(10, 0, 60, 1));
    private final JCheckBox clock24Check = new JCheckBox("24-hour clock", true);
    private final JCheckBox secondsCheck = new JCheckBox("Show seconds", true);
    private final JSpinner hijriOffset = new JSpinner(new SpinnerNumberModel(0, -3, 3, 1));
    private final JComboBox<String> themeCombo = new JComboBox<>(
            new String[]{"night", "green", "blue", "custom"});
    private final JTextField customBgField = new JTextField(18);
    private final JButton customBgBrowse = new JButton("…");
    private final JComboBox<String> languageCombo = new JComboBox<>(
            new String[]{"en", "ar", "fr"});
    private final JComboBox<String> unitCombo = new JComboBox<>(new String[]{"°C", "°F"});

    // Content
    private final JSpinner rotationSpinner = new JSpinner(new SpinnerNumberModel(18, 5, 120, 1));
    private final JCheckBox slidesCheck = new JCheckBox("Show user slides between prayers", true);
    private final JCheckBox weatherCheck = new JCheckBox("Weather forecast (internet)", true);
    private final JTextArea announcementsArea = new JTextArea(5, 26);

    // Reminders
    private final DefaultListModel<String> reminderModel = new DefaultListModel<>();

    private boolean saved = false;
    /** Live dashboard preview (#15) — a real TawkitScreen at miniature size. */
    private final TawkitScreen previewScreen = new TawkitScreen();
    private boolean previewUpdating = false;
    /**
     * The live settings object this dialog edits (applied immediately on
     * "Apply" / "Save" so the app reflects changes without restart).
     */
    private AppSettings liveSettings;
    /** Fired after changes were pushed into the live settings. */
    private Runnable onApply;

    public SettingsDialog(JFrame parent) {
        this(parent, null);
    }

    /**
     * Preferred constructor: pass the live {@link AppSettings} so
     * <b>Apply</b> pushes changes straight into the running app via
     * {@link #setOnApply(Runnable)}.
     */
    public SettingsDialog(JFrame parent, AppSettings settings) {
        super(parent, "Settings", true);
        this.liveSettings = settings;
        setIconImage(Icons.appIcon());
        buildUI();
        loadCityCountries();
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        double scale = UiScale.factor();
        // The premium chrome gives the nav column and cards room to breathe;
        // keep enough width for the two-column forms at 100% scaling.
        int w = (int) Math.min(820 * scale, screen.width * 0.92);
        int h = (int) Math.min(780 * scale, screen.height * 0.92);
        setSize(w, h);
        setMinimumSize(new Dimension(Math.min(780, screen.width), Math.min(560, screen.height)));
        setResizable(true);
        setLocationRelativeTo(null);
        // ESC closes the dialog like Cancel
        javax.swing.InputMap im = getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        javax.swing.ActionMap am = getRootPane().getActionMap();
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "closeDialog");
        am.put("closeDialog", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                dispose();
            }
        });
        // Stop preview timers when the dialog is closed, so nothing repaints a ghost window
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                previewScreen.stop();
                previewAudio.stop();
            }
        });
    }

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(0, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setPaint(new GradientPaint(
                        0, 0, new Color(0x0C, 0x0D, 0x0F),
                        0, getHeight(), new Color(0x07, 0x07, 0x08)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                // A restrained golden glow keeps the dark chrome from feeling flat.
                float radius = Math.max(420f, getWidth() * 0.72f);
                RadialGradientPaint glow = new RadialGradientPaint(
                        new Point2D.Float(getWidth() * 0.27f, 0),
                        radius,
                        new float[]{0f, 1f},
                        new Color[]{new Color(197, 166, 106, 30),
                                new Color(197, 166, 106, 0)});
                g2.setPaint(glow);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        root.setOpaque(false);
        setContentPane(root);

        root.add(buildSettingsHeader(), BorderLayout.NORTH);

        JPanel workspace = new JPanel(new BorderLayout(16, 0));
        workspace.setOpaque(false);
        workspace.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));

        RoundedPanel nav = new RoundedPanel(
                new Color(0x11, 0x12, 0x14), new Color(0x0F, 0x10, 0x12),
                new Color(0x2D, 0x2C, 0x27), CARD_ARC);
        nav.setLayout(new BoxLayout(nav, BoxLayout.Y_AXIS));
        nav.setBorder(BorderFactory.createEmptyBorder(18, 12, 14, 12));
        nav.setPreferredSize(new Dimension(214, 0));

        JLabel navKicker = kicker("CONFIGURE");
        navKicker.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
        navKicker.setAlignmentX(Component.LEFT_ALIGNMENT);
        nav.add(navKicker);
        nav.add(Box.createVerticalStrut(15));

        CardLayout cards = new CardLayout();
        RoundedPanel content = new RoundedPanel(
                new Color(0x16, 0x17, 0x19), new Color(0x13, 0x14, 0x16),
                new Color(0x2F, 0x2E, 0x29), CARD_ARC);
        content.setLayout(cards);
        content.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));

        Map<String, JPanel> sections = new LinkedHashMap<>();
        sections.put("Location", buildLocationTab());
        sections.put("Prayer", buildPrayerTab());
        sections.put("Iqama", buildIqamaTab());
        sections.put("Audio", buildAudioTab());
        sections.put("Display", buildDisplayTab());
        sections.put("Content", buildContentTab());
        sections.put("Reminders", buildRemindersTab());
        sections.put("Backup", buildBackupTab());

        List<NavButton> navButtons = new ArrayList<>();
        int sectionNumber = 1;
        for (Map.Entry<String, JPanel> entry : sections.entrySet()) {
            JScrollPane scroll = buildScrollableTab(entry.getKey(), sectionNumber, entry.getValue());
            content.add(scroll, entry.getKey());

            SectionInfo info = SECTION_INFO.get(entry.getKey());
            NavButton navButton = new NavButton(entry.getKey(),
                    info != null ? info.glyph() : Glyph.CONTENT);
            navButton.setAlignmentX(Component.LEFT_ALIGNMENT);
            navButton.addActionListener(e -> {
                cards.show(content, entry.getKey());
                for (NavButton button : navButtons) {
                    button.setSelectedLook(button == navButton);
                }
            });
            navButtons.add(navButton);
            nav.add(navButton);
            if (navButtons.size() < sections.size()) {
                nav.add(Box.createVerticalStrut(3));
            }
            sectionNumber++;
        }

        if (!navButtons.isEmpty()) {
            navButtons.get(0).setSelectedLook(true);
        }

        nav.add(Box.createVerticalGlue());
        JPanel navFooter = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        navFooter.setOpaque(false);
        navFooter.setAlignmentX(Component.LEFT_ALIGNMENT);
        navFooter.setBorder(BorderFactory.createEmptyBorder(14, 7, 0, 0));
        JLabel onlineDot = styledLabel(new JLabel("●"));
        onlineDot.setForeground(new Color(0x7F, 0xB6, 0x88));
        onlineDot.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 8));
        JLabel localLabel = styledLabel(new JLabel("LOCAL CONFIGURATION"));
        localLabel.setForeground(SETTINGS_DIM);
        localLabel.setFont(tracked(new Font(Font.SANS_SERIF, Font.BOLD, 9), 0.12));
        localLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        navFooter.add(onlineDot);
        navFooter.add(localLabel);
        nav.add(navFooter);

        workspace.add(nav, BorderLayout.WEST);
        workspace.add(content, BorderLayout.CENTER);
        root.add(workspace, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(
                        0, 0, new Color(0x12, 0x13, 0x15),
                        0, getHeight(), new Color(0x0D, 0x0E, 0x10)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(SETTINGS_LINE_WARM);
                g2.drawLine(0, 0, getWidth(), 0);
                g2.dispose();
            }
        };
        buttonPanel.setOpaque(false);
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(13, 24, 13, 24));

        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        statusRow.setOpaque(false);
        JLabel statusDot = styledLabel(new JLabel("●"));
        statusDot.setForeground(SETTINGS_ACCENT);
        statusDot.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 8));
        JLabel status = styledLabel(new JLabel("Saved locally   ·   Apply to update live"));
        status.setForeground(SETTINGS_MUTED);
        status.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        statusRow.add(statusDot);
        statusRow.add(status);
        buttonPanel.add(statusRow, BorderLayout.WEST);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 9, 0));
        actions.setOpaque(false);
        JButton cancelButton = styleButton(new JButton("Cancel"), false);
        JButton applyButton = styleButton(new JButton("Apply"), false);
        applyButton.setToolTipText("Apply changes to the app without closing");
        applyButton.addActionListener(e -> applyNow());
        JButton saveButton = styleButton(new JButton("Save changes"), true);
        saveButton.addActionListener(e -> {
            saved = true;
            applyNow();
            dispose();
        });
        cancelButton.addActionListener(e -> dispose());
        actions.add(cancelButton);
        actions.add(applyButton);
        actions.add(saveButton);
        buttonPanel.add(actions, BorderLayout.EAST);
        root.add(buttonPanel, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(saveButton);
        styleSettingsComponents();
        // A second pass catches LAF-created children (combo/spinner arrows).
        SwingUtilities.invokeLater(this::styleSettingsComponents);
    }

    private JPanel buildSettingsHeader() {
        JPanel header = new JPanel(new BorderLayout(18, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(
                        0, 0, new Color(0x15, 0x16, 0x18),
                        0, getHeight(), new Color(0x0D, 0x0E, 0x10)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setPaint(new GradientPaint(
                        0, 0, new Color(197, 166, 106, 95),
                        getWidth(), 0, new Color(197, 166, 106, 0)));
                g2.fillRect(0, getHeight() - 1, getWidth(), 1);
                g2.dispose();
            }
        };
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(22, 26, 19, 26));

        JPanel brand = new JPanel(new BorderLayout(14, 0));
        brand.setOpaque(false);
        brand.add(buildEmblem(), BorderLayout.WEST);

        JPanel title = new JPanel();
        title.setOpaque(false);
        title.setLayout(new BoxLayout(title, BoxLayout.Y_AXIS));

        JLabel eyebrow = kicker("TAWKIT   ·   CONTROL CENTER");
        eyebrow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel heading = styledLabel(new JLabel("Settings"));
        heading.setForeground(SETTINGS_TEXT);
        heading.setFont(TawkitTheme.serif(Font.BOLD, 27));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel subtitle = styledLabel(new JLabel("Curate every detail of your kiosk."));
        subtitle.setForeground(SETTINGS_MUTED);
        subtitle.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        title.add(eyebrow);
        title.add(Box.createVerticalStrut(5));
        title.add(heading);
        title.add(Box.createVerticalStrut(2));
        title.add(subtitle);
        brand.add(title, BorderLayout.CENTER);
        header.add(brand, BorderLayout.WEST);

        JPanel badge = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        badge.setOpaque(false);
        badge.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(197, 166, 106, 55), 1, true),
                BorderFactory.createEmptyBorder(7, 12, 7, 12)));
        JLabel badgeDot = styledLabel(new JLabel("●"));
        badgeDot.setForeground(new Color(0x7F, 0xB6, 0x88));
        badgeDot.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 8));
        JLabel badgeText = styledLabel(new JLabel("THIS SCREEN"));
        badgeText.setForeground(SETTINGS_MUTED);
        badgeText.setFont(tracked(new Font(Font.SANS_SERIF, Font.BOLD, 9), 0.12));
        badgeText.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        badge.add(badgeDot);
        badge.add(badgeText);
        JPanel badgeHolder = new JPanel(new GridBagLayout());
        badgeHolder.setOpaque(false);
        badgeHolder.add(badge);
        header.add(badgeHolder, BorderLayout.EAST);
        return header;
    }

    /** Small app emblem, or a gold monogram if the bundled icon is missing. */
    private JComponent buildEmblem() {
        JLabel emblem = styledLabel(new JLabel());
        emblem.setHorizontalAlignment(SwingConstants.CENTER);
        emblem.setVerticalAlignment(SwingConstants.CENTER);
        emblem.setOpaque(false);
        Image icon = Icons.appIcon();
        if (icon != null) {
            emblem.setIcon(new ImageIcon(icon.getScaledInstance(42, 42, Image.SCALE_SMOOTH)));
        } else {
            emblem.setText("T");
            emblem.setForeground(SETTINGS_ACCENT);
            emblem.setFont(TawkitTheme.serif(Font.BOLD, 24));
        }
        emblem.setPreferredSize(new Dimension(48, 48));
        emblem.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(197, 166, 106, 90), 1, true),
                BorderFactory.createEmptyBorder(2, 2, 2, 2)));
        return emblem;
    }

    private JScrollPane buildScrollableTab(String section, int number, JPanel content) {
        JPanel page = buildSectionPage(section, number, content);
        JScrollPane scroll = new JScrollPane(page);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUI(new PremiumScrollBarUI());
        scroll.getVerticalScrollBar().setOpaque(false);
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(9, 0));
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.getVerticalScrollBar().setBlockIncrement(120);
        return scroll;
    }

    private JPanel buildSectionPage(String section, int number, JPanel content) {
        SectionInfo info = SECTION_INFO.get(section);
        String title = info != null ? info.title() : section;
        String description = info != null ? info.description() : "";

        ScrollablePage page = new ScrollablePage();
        page.setLayout(new BorderLayout(0, 20));
        page.setOpaque(false);
        page.setBorder(BorderFactory.createEmptyBorder(28, 30, 30, 30));

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));

        JLabel kicker = kicker(String.format(Locale.ROOT, "SECTION %02d", number));
        kicker.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel heading = styledLabel(new JLabel(title));
        heading.setForeground(SETTINGS_TEXT);
        heading.setFont(TawkitTheme.serif(Font.BOLD, 23));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel descriptionLabel = styledLabel(new JLabel(description));
        descriptionLabel.setForeground(SETTINGS_MUTED);
        descriptionLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        descriptionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        header.add(kicker);
        header.add(Box.createVerticalStrut(6));
        header.add(heading);
        header.add(Box.createVerticalStrut(4));
        header.add(descriptionLabel);
        header.add(Box.createVerticalStrut(15));

        JComponent rule = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(
                        0, 0, new Color(197, 166, 106, 85),
                        getWidth(), 0, new Color(197, 166, 106, 0)));
                g2.fillRect(0, 0, getWidth(), 1);
                g2.dispose();
            }
        };
        rule.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        rule.setPreferredSize(new Dimension(10, 1));
        header.add(rule);
        page.add(header, BorderLayout.NORTH);
        page.add(content, BorderLayout.CENTER);
        return page;
    }

    /** A page that fills the scroll viewport width while keeping its own height. */
    private static final class ScrollablePage extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(80, visibleRect.height - 40);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private JButton styleButton(JButton button, boolean primary) {
        button.putClientProperty("tawkitStyled", Boolean.TRUE);
        button.putClientProperty("tawkitPrimary", primary);
        button.setUI(new PremiumButtonUI());
        button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBorder(BorderFactory.createEmptyBorder(
                primary ? 10 : 9, primary ? 20 : 16, primary ? 10 : 9, primary ? 20 : 16));
        button.setForeground(primary ? new Color(0x14, 0x11, 0x09) : SETTINGS_TEXT);
        return button;
    }

    private void styleSettingsComponents() {
        styleSettingsComponent(this);
        revalidate();
        repaint();
    }

    private void styleSettingsComponent(Component component) {
        if (component instanceof NavButton) {
            return;
        }

        if (component instanceof JButton button) {
            if (Boolean.TRUE.equals(button.getClientProperty("tawkitChrome"))) {
                button.setOpaque(false);
                button.setContentAreaFilled(false);
                button.setBorderPainted(false);
                button.setFocusPainted(false);
            } else if (button.getClientProperty("tawkitStyled") != Boolean.TRUE) {
                button.putClientProperty("tawkitStyled", Boolean.TRUE);
                button.putClientProperty("tawkitPrimary", Boolean.FALSE);
                button.setUI(new PremiumButtonUI());
                button.setOpaque(false);
                button.setContentAreaFilled(false);
                button.setBorderPainted(false);
                button.setFocusPainted(false);
                button.setForeground(SETTINGS_TEXT);
                button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
                button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                button.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
            }
        } else if (component instanceof JTextField field) {
            boolean spinnerEditor = SwingUtilities.getAncestorOfClass(JSpinner.class, field) != null;
            field.setForeground(SETTINGS_TEXT);
            field.setCaretColor(SETTINGS_ACCENT);
            if (spinnerEditor) {
                field.setOpaque(false);
                field.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
            } else if (field.getClientProperty("tawkitFieldStyled") != Boolean.TRUE) {
                field.putClientProperty("tawkitFieldStyled", Boolean.TRUE);
                field.setUI(new PremiumTextFieldUI());
                field.setOpaque(true);
                field.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
                field.addFocusListener(new FocusAdapter() {
                    @Override public void focusGained(FocusEvent e) {
                        field.repaint();
                    }

                    @Override public void focusLost(FocusEvent e) {
                        field.repaint();
                    }
                });
            }
        } else if (component instanceof JTextArea area) {
            area.setOpaque(false);
            area.setForeground(SETTINGS_TEXT);
            area.setCaretColor(SETTINGS_ACCENT);
            area.setSelectionColor(new Color(0x55, 0x47, 0x2B));
            area.setSelectedTextColor(SETTINGS_TEXT);
            area.setBorder(BorderFactory.createEmptyBorder(9, 10, 9, 10));
        } else if (component instanceof JComboBox<?> combo) {
            if (combo.getClientProperty("tawkitComboStyled") != Boolean.TRUE) {
                combo.putClientProperty("tawkitComboStyled", Boolean.TRUE);
                combo.setUI(new PremiumComboBoxUI());
                combo.setOpaque(false);
                combo.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 6));
                combo.setForeground(SETTINGS_TEXT);
                combo.setRenderer(new DarkComboRenderer());
                combo.addFocusListener(new FocusAdapter() {
                    @Override public void focusGained(FocusEvent e) {
                        combo.repaint();
                    }

                    @Override public void focusLost(FocusEvent e) {
                        combo.repaint();
                    }
                });
            }
        } else if (component instanceof JSpinner spinner) {
            if (spinner.getClientProperty("tawkitSpinnerStyled") != Boolean.TRUE) {
                spinner.putClientProperty("tawkitSpinnerStyled", Boolean.TRUE);
                spinner.setUI(new PremiumSpinnerUI());
                spinner.setOpaque(false);
                spinner.setBorder(new RoundedBorder(
                        SETTINGS_FIELD, SETTINGS_LINE, 9, 8, 3));
                JComponent editor = spinner.getEditor();
                editor.setOpaque(false);
                if (editor instanceof JSpinner.DefaultEditor spinnerEditor) {
                    JTextField field = spinnerEditor.getTextField();
                    field.setForeground(SETTINGS_TEXT);
                    field.setCaretColor(SETTINGS_ACCENT);
                    field.setOpaque(false);
                    field.setBorder(BorderFactory.createEmptyBorder(2, 3, 2, 3));
                    field.addFocusListener(new FocusAdapter() {
                        @Override public void focusGained(FocusEvent e) {
                            spinner.repaint();
                        }

                        @Override public void focusLost(FocusEvent e) {
                            spinner.repaint();
                        }
                    });
                }
            }
        } else if (component instanceof JCheckBox check) {
            check.setOpaque(false);
            check.setForeground(SETTINGS_TEXT);
            check.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            check.setIconTextGap(10);
            check.setIcon(new CheckIcon(false));
            check.setSelectedIcon(new CheckIcon(true));
            check.setRolloverIcon(new CheckIcon(false));
            check.setRolloverSelectedIcon(new CheckIcon(true));
            check.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
        } else if (component instanceof JSlider slider) {
            slider.setOpaque(false);
            slider.setForeground(SETTINGS_ACCENT);
            if (!(slider.getUI() instanceof PremiumSliderUI)) {
                slider.setUI(new PremiumSliderUI(slider));
                slider.setPreferredSize(new Dimension(180, 34));
            }
        } else if (component instanceof JLabel label) {
            if (Boolean.TRUE.equals(label.getClientProperty("tawkitLabelStyled"))) {
                // Already typographically styled by the premium chrome.
            } else if (Boolean.TRUE.equals(label.getClientProperty("tawkitMuted"))) {
                label.setForeground(SETTINGS_MUTED);
                label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            } else {
                label.setForeground(SETTINGS_MUTED);
                label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            }
        } else if (component instanceof JList<?> list) {
            list.setOpaque(false);
            list.setForeground(SETTINGS_TEXT);
            list.setSelectionBackground(new Color(0x36, 0x2F, 0x21));
            list.setSelectionForeground(SETTINGS_ACCENT_LIGHT);
            list.setFixedCellHeight(30);
            list.setCellRenderer(new DarkListRenderer());
        } else if (component instanceof JScrollPane scroll) {
            if (scroll.getViewport().getView() instanceof ScrollablePage) {
                scroll.setOpaque(false);
                scroll.getViewport().setOpaque(false);
            } else {
                scroll.setOpaque(false);
                scroll.getViewport().setOpaque(false);
                scroll.setBorder(new RoundedBorder(
                        SETTINGS_FIELD, SETTINGS_LINE, 10, 0, 0));
                scroll.getVerticalScrollBar().setUI(new PremiumScrollBarUI());
                scroll.getVerticalScrollBar().setOpaque(false);
                scroll.getVerticalScrollBar().setPreferredSize(new Dimension(9, 0));
            }
        } else if (component instanceof JPanel panel) {
            Color bg = panel.getBackground();
            boolean preserve =
                    bg.equals(SETTINGS_SURFACE)
                    || bg.equals(SETTINGS_BG)
                    || bg.equals(new Color(0x13, 0x14, 0x16))
                    || bg.equals(new Color(0x16, 0x17, 0x19));
            if (!preserve) {
                panel.setOpaque(false);
            }
        }

        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                styleSettingsComponent(child);
            }
        }
    }

    private static JLabel styledLabel(JLabel label) {
        label.putClientProperty("tawkitLabelStyled", Boolean.TRUE);
        return label;
    }

    /** Small supporting copy shown under a control. */
    private static JLabel hintLabel(String text) {
        JLabel label = styledLabel(new JLabel(text));
        label.setForeground(SETTINGS_MUTED);
        label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        return label;
    }

    /** Lays several checkboxes out on one line with generous breathing room. */
    private static JPanel checkRow(JCheckBox... boxes) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        for (int i = 0; i < boxes.length; i++) {
            if (i > 0) row.add(Box.createHorizontalStrut(26));
            row.add(boxes[i]);
        }
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private static JLabel kicker(String text) {
        JLabel label = styledLabel(new JLabel(text));
        label.setForeground(SETTINGS_ACCENT);
        label.setFont(tracked(new Font(Font.SANS_SERIF, Font.BOLD, 10), 0.16));
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
        return label;
    }

    private static Font tracked(Font base, double amount) {
        return base.deriveFont(Map.of(java.awt.font.TextAttribute.TRACKING, amount));
    }

    // ---------------------------------------------------------- premium chrome

    private static final class RoundedPanel extends JPanel {
        private final Color top;
        private final Color bottom;
        private final Color border;
        private final int arc;

        RoundedPanel(Color top, Color bottom, Color border, int arc) {
            this.top = top;
            this.bottom = bottom;
            this.border = border;
            this.arc = arc;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            g2.setPaint(new GradientPaint(0, 0, top, 0, h, bottom));
            g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
            if (border != null) {
                g2.setColor(border);
                g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static final class PremiumTextFieldUI extends BasicTextFieldUI {
        @Override
        protected void paintBackground(Graphics g) {
            JComponent c = getComponent();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(SETTINGS_FIELD);
            g2.fillRoundRect(0, 0, c.getWidth() - 1, c.getHeight() - 1, 10, 10);
            g2.setColor(c.hasFocus() ? SETTINGS_ACCENT : SETTINGS_LINE);
            g2.drawRoundRect(0, 0, c.getWidth() - 1, c.getHeight() - 1, 10, 10);
            g2.dispose();
        }
    }

    private static final class RoundedBorder extends javax.swing.border.AbstractBorder {
        private final Color fill;
        private final Color line;
        private final int arc;
        private final Insets insets;

        RoundedBorder(Color fill, Color line, int arc, int padX, int padY) {
            this.fill = fill;
            this.line = line;
            this.arc = arc;
            this.insets = new Insets(padY + 1, padX + 1, padY + 1, padX + 1);
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return insets;
        }

        @Override
        public Insets getBorderInsets(Component c, Insets target) {
            target.set(insets.top, insets.left, insets.bottom, insets.right);
            return target;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            if (fill != null) {
                g2.setColor(fill);
                g2.fillRoundRect(x, y, width - 1, height - 1, arc, arc);
            }
            Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            boolean focused = focus != null
                    && (focus == c || SwingUtilities.isDescendingFrom(focus, c));
            g2.setColor(focused ? SETTINGS_ACCENT : line);
            g2.drawRoundRect(x, y, width - 1, height - 1, arc, arc);
            g2.dispose();
        }
    }

    private static final class DarkComboRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            if (index < 0) {
                setOpaque(false);
                setBackground(new Color(0, 0, 0, 0));
            } else {
                setOpaque(true);
                setBackground(isSelected
                        ? new Color(0x35, 0x2E, 0x20)
                        : new Color(0x1B, 0x1C, 0x1E));
            }
            setForeground(isSelected ? SETTINGS_ACCENT_LIGHT : SETTINGS_TEXT);
            if (value instanceof CalculationMethod method) {
                setText(method.getDisplayName());
            } else if (value instanceof AsrMethod method) {
                setText(method == AsrMethod.HANAFI
                        ? "Hanafi (shadow x2)" : "Standard (shadow x1)");
            } else {
                setText(value == null ? "" : value.toString());
            }
            return this;
        }
    }

    private static final class DarkListRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
            setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            setOpaque(isSelected);
            setBackground(new Color(0x35, 0x2E, 0x20));
            setForeground(isSelected ? SETTINGS_ACCENT_LIGHT : SETTINGS_TEXT);
            return this;
        }
    }

    private static final class CheckIcon implements Icon {
        private final boolean selected;

        CheckIcon(boolean selected) {
            this.selected = selected;
        }

        @Override
        public int getIconWidth() {
            return 18;
        }

        @Override
        public int getIconHeight() {
            return 18;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.translate(x, y);
            if (selected) {
                g2.setPaint(new GradientPaint(0, 0, SETTINGS_ACCENT_LIGHT,
                        0, getIconHeight(), SETTINGS_ACCENT));
                g2.fillRoundRect(0, 0, 18, 18, 6, 6);
                g2.setColor(new Color(0x15, 0x12, 0x0B));
                g2.setStroke(new BasicStroke(2.1f,
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(4, 9, 7, 12);
                g2.drawLine(7, 12, 14, 5);
            } else {
                g2.setColor(new Color(0x1A, 0x1B, 0x1D));
                g2.fillRoundRect(0, 0, 18, 18, 6, 6);
                g2.setColor(new Color(0x3B, 0x3C, 0x3E));
                g2.drawRoundRect(0, 0, 17, 17, 6, 6);
            }
            g2.dispose();
        }
    }

    private static final class PremiumButtonUI extends BasicButtonUI {
        @Override
        public void paint(Graphics g, JComponent c) {
            AbstractButton button = (AbstractButton) c;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = c.getWidth();
            int h = c.getHeight();
            boolean primary = Boolean.TRUE.equals(button.getClientProperty("tawkitPrimary"));
            boolean hover = button.getModel().isRollover();
            boolean pressed = button.getModel().isPressed();
            boolean enabled = button.isEnabled();

            Color top;
            Color bottom;
            Color line;
            Color text;
            if (primary) {
                top = pressed ? new Color(0xB9, 0x96, 0x52)
                        : hover ? new Color(0xE7, 0xCB, 0x8C) : new Color(0xD7, 0xB6, 0x74);
                bottom = pressed ? new Color(0x9C, 0x7B, 0x3E) : new Color(0xBA, 0x95, 0x52);
                line = new Color(0xF0, 0xD7, 0x9F, 180);
                text = new Color(0x17, 0x13, 0x0A);
            } else {
                top = pressed ? new Color(0x18, 0x19, 0x1B)
                        : hover ? new Color(0x2A, 0x2B, 0x2D) : new Color(0x21, 0x22, 0x24);
                bottom = pressed ? new Color(0x13, 0x14, 0x16) : new Color(0x18, 0x19, 0x1B);
                line = hover ? new Color(0x4A, 0x43, 0x34) : new Color(0x36, 0x37, 0x39);
                text = SETTINGS_TEXT;
            }
            if (!enabled) {
                top = new Color(0x1A, 0x1B, 0x1D);
                bottom = new Color(0x16, 0x17, 0x19);
                line = new Color(0x2A, 0x2B, 0x2D);
                text = SETTINGS_DIM;
            }

            g2.setPaint(new GradientPaint(0, 0, top, 0, h, bottom));
            g2.fillRoundRect(0, 0, w - 1, h - 1, 11, 11);
            g2.setColor(line);
            g2.drawRoundRect(0, 0, w - 1, h - 1, 11, 11);
            if (button.hasFocus()) {
                g2.setColor(new Color(197, 166, 106, 110));
                g2.drawRoundRect(2, 2, w - 5, h - 5, 9, 9);
            }

            String label = button.getText();
            if (label != null && !label.isEmpty()) {
                g2.setFont(button.getFont());
                FontMetrics fm = g2.getFontMetrics();
                int tx = Math.max(2, (w - fm.stringWidth(label)) / 2);
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.setColor(text);
                g2.drawString(label, tx, ty);
            }
            g2.dispose();
        }
    }

    private static final class PremiumComboBoxUI extends BasicComboBoxUI {
        @Override
        public void paint(Graphics g, JComponent c) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(SETTINGS_FIELD);
            g2.fillRoundRect(0, 0, c.getWidth() - 1, c.getHeight() - 1, 10, 10);
            g2.setColor(c.hasFocus() ? SETTINGS_ACCENT : SETTINGS_LINE);
            g2.drawRoundRect(0, 0, c.getWidth() - 1, c.getHeight() - 1, 10, 10);
            g2.dispose();
            super.paint(g, c);
        }

        @Override
        public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
            // The rounded surface is painted by paint().
        }

        @Override
        protected JButton createArrowButton() {
            JButton arrow = new JButton() {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(getModel().isRollover() ? SETTINGS_ACCENT : SETTINGS_MUTED);
                    g2.setStroke(new BasicStroke(1.6f,
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    int cx = getWidth() / 2;
                    int cy = getHeight() / 2;
                    g2.drawLine(cx - 4, cy - 2, cx, cy + 2);
                    g2.drawLine(cx, cy + 2, cx + 4, cy - 2);
                    g2.dispose();
                }
            };
            arrow.setName("ComboBox.arrowButton");
            arrow.putClientProperty("tawkitChrome", Boolean.TRUE);
            arrow.setOpaque(false);
            arrow.setContentAreaFilled(false);
            arrow.setBorderPainted(false);
            arrow.setFocusable(false);
            arrow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            arrow.setPreferredSize(new Dimension(28, 0));
            return arrow;
        }
    }

    private static final class PremiumSpinnerUI extends javax.swing.plaf.basic.BasicSpinnerUI {
        @Override
        protected Component createNextButton() {
            SpinnerArrowButton button = new SpinnerArrowButton(true);
            button.setName("Spinner.nextButton");
            return button;
        }

        @Override
        protected Component createPreviousButton() {
            SpinnerArrowButton button = new SpinnerArrowButton(false);
            button.setName("Spinner.previousButton");
            return button;
        }
    }

    private static final class SpinnerArrowButton extends JButton {
        private final boolean up;

        SpinnerArrowButton(boolean up) {
            this.up = up;
            putClientProperty("tawkitChrome", Boolean.TRUE);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setFocusable(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 4));
            setPreferredSize(new Dimension(21, 0));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            if (getModel().isRollover()) {
                g2.setColor(new Color(197, 166, 106, 30));
                g2.fillRoundRect(2, 1, getWidth() - 4, getHeight() - 2, 6, 6);
            }
            g2.setColor(getModel().isRollover() ? SETTINGS_ACCENT : SETTINGS_MUTED);
            g2.setStroke(new BasicStroke(1.5f,
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            if (up) {
                g2.drawLine(cx - 3, cy + 1, cx, cy - 2);
                g2.drawLine(cx, cy - 2, cx + 3, cy + 1);
            } else {
                g2.drawLine(cx - 3, cy - 2, cx, cy + 1);
                g2.drawLine(cx, cy + 1, cx + 3, cy - 2);
            }
            g2.dispose();
        }
    }

    private static final class PremiumSliderUI extends BasicSliderUI {
        PremiumSliderUI(JSlider slider) {
            super(slider);
        }

        @Override
        protected Dimension getThumbSize() {
            return new Dimension(16, 16);
        }

        @Override
        public void paintTrack(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int y = trackRect.y + trackRect.height / 2 - 2;
            g2.setColor(new Color(0x30, 0x31, 0x33));
            g2.fillRoundRect(trackRect.x, y, trackRect.width, 4, 4, 4);
            int center = thumbRect.x + thumbRect.width / 2;
            int filled = Math.max(0, Math.min(trackRect.width, center - trackRect.x));
            if (filled > 0) {
                g2.setPaint(new GradientPaint(
                        trackRect.x, 0, SETTINGS_ACCENT,
                        trackRect.x + trackRect.width, 0, SETTINGS_ACCENT_LIGHT));
                g2.fillRoundRect(trackRect.x, y, filled, 4, 4, 4);
            }
            g2.dispose();
        }

        @Override
        public void paintThumb(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int x = thumbRect.x;
            int y = thumbRect.y;
            int w = thumbRect.width;
            int h = thumbRect.height;
            g2.setColor(new Color(0, 0, 0, 90));
            g2.fillOval(x + 1, y + 2, w, h);
            g2.setPaint(new GradientPaint(
                    0, y, SETTINGS_ACCENT_LIGHT, 0, y + h, SETTINGS_ACCENT));
            g2.fillOval(x, y, w, h);
            g2.setColor(new Color(0x18, 0x15, 0x0E));
            g2.drawOval(x, y, w - 1, h - 1);
            g2.dispose();
        }

        @Override
        public void paintFocus(Graphics g) {
            // No dotted focus ring: the gold thumb is the focus cue.
        }
    }

    private static final class PremiumScrollBarUI extends BasicScrollBarUI {
        @Override
        protected void configureScrollBarColors() {
            thumbColor = new Color(0x3F, 0x3D, 0x36);
            trackColor = new Color(0, 0, 0, 0);
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return zeroButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return zeroButton();
        }

        private JButton zeroButton() {
            JButton button = new JButton();
            button.setPreferredSize(new Dimension(0, 0));
            button.setMinimumSize(new Dimension(0, 0));
            button.setMaximumSize(new Dimension(0, 0));
            button.setOpaque(false);
            button.setContentAreaFilled(false);
            button.setBorderPainted(false);
            return button;
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
            // Transparent track keeps the card surface clean.
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle bounds) {
            if (bounds.width <= 0 || bounds.height <= 0) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(thumbColor);
            g2.fillRoundRect(bounds.x + 2, bounds.y, Math.max(3, bounds.width - 4),
                    bounds.height, 8, 8);
            g2.dispose();
        }
    }

    private static final class NavButton extends JButton {
        private final Glyph glyph;
        private boolean selectedLook;
        private boolean hover;

        NavButton(String text, Glyph glyph) {
            super(text);
            this.glyph = glyph;
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setHorizontalAlignment(SwingConstants.LEFT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            setForeground(SETTINGS_MUTED);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            setPreferredSize(new Dimension(188, 44));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        void setSelectedLook(boolean selected) {
            this.selectedLook = selected;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();

            if (selectedLook || hover) {
                Color top = selectedLook
                        ? new Color(197, 166, 106, 48)
                        : new Color(0x1E, 0x1F, 0x21, 190);
                Color bottom = selectedLook
                        ? new Color(197, 166, 106, 9)
                        : new Color(0x16, 0x17, 0x19, 190);
                g2.setPaint(new GradientPaint(0, 0, top, w, 0, bottom));
                g2.fillRoundRect(1, 0, w - 2, h, 12, 12);
                g2.setColor(selectedLook
                        ? new Color(197, 166, 106, 85)
                        : new Color(0x2C, 0x2D, 0x2F));
                g2.drawRoundRect(1, 0, w - 3, h - 1, 12, 12);
            }
            if (selectedLook) {
                g2.setColor(SETTINGS_ACCENT);
                g2.fillRoundRect(1, 10, 3, h - 20, 3, 3);
            }

            glyph.paint(g2, 15, (h - 16) / 2f,
                    selectedLook ? SETTINGS_ACCENT : new Color(0x9B, 0x98, 0x90));
            g2.setFont(getFont().deriveFont(selectedLook ? Font.BOLD : Font.PLAIN));
            g2.setColor(selectedLook ? SETTINGS_ACCENT_LIGHT : new Color(0xC6, 0xC2, 0xB8));
            FontMetrics fm = g2.getFontMetrics();
            int baseline = (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(getText(), 40, baseline);
            if (hasFocus()) {
                g2.setColor(new Color(197, 166, 106, 100));
                g2.drawRoundRect(3, 3, w - 7, h - 7, 10, 10);
            }
            g2.dispose();
        }
    }

    private enum Glyph {
        LOCATION, PRAYER, IQAMA, AUDIO, DISPLAY, CONTENT, REMINDERS, BACKUP;

        void paint(Graphics2D g2, float x, float y, Color color) {
            Graphics2D g = (Graphics2D) g2.create();
            g.translate(x, y);
            g.setColor(color);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(1.5f,
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (this) {
                case LOCATION -> {
                    Path2D pin = new Path2D.Float();
                    pin.moveTo(3.2, 5.5);
                    pin.curveTo(3.8, 9.2, 8, 13.7, 8, 13.7);
                    pin.curveTo(8, 13.7, 12.2, 9.2, 12.8, 5.5);
                    g.draw(pin);
                    g.draw(new Ellipse2D.Float(5f, 2.2f, 6f, 6f));
                    g.fill(new Ellipse2D.Float(7.2f, 4.6f, 1.6f, 1.6f));
                }
                case PRAYER -> {
                    Area moon = new Area(new Ellipse2D.Float(3.0f, 1.5f, 10.5f, 12f));
                    moon.subtract(new Area(new Ellipse2D.Float(6.6f, 0.2f, 9.2f, 11.5f)));
                    g.draw(moon);
                    g.fill(new Ellipse2D.Float(12.4f, 3.2f, 1.5f, 1.5f));
                }
                case IQAMA -> {
                    g.draw(new Ellipse2D.Float(2.5f, 2.5f, 11f, 11f));
                    g.draw(new Line2D.Float(8f, 5f, 8f, 8.6f));
                    g.draw(new Line2D.Float(8f, 8.6f, 10.5f, 10f));
                    g.draw(new Line2D.Float(6.5f, 1.2f, 9.5f, 1.2f));
                }
                case AUDIO -> {
                    Path2D speaker = new Path2D.Float();
                    speaker.moveTo(2.8, 6.2);
                    speaker.lineTo(5.0, 6.2);
                    speaker.lineTo(7.8, 3.5);
                    speaker.lineTo(7.8, 12.5);
                    speaker.lineTo(5.0, 9.8);
                    speaker.lineTo(2.8, 9.8);
                    speaker.closePath();
                    g.draw(speaker);
                    g.draw(new java.awt.geom.Arc2D.Float(
                            6.5f, 4.8f, 6.5f, 6.4f, -50, 100, java.awt.geom.Arc2D.OPEN));
                    g.draw(new java.awt.geom.Arc2D.Float(
                            8.5f, 2.8f, 6.0f, 10.4f, -50, 100, java.awt.geom.Arc2D.OPEN));
                }
                case DISPLAY -> {
                    g.draw(new RoundRectangle2D.Float(1.8f, 2.3f, 12.4f, 8.4f, 2.5f, 2.5f));
                    g.draw(new Line2D.Float(8, 10.7f, 8, 13.2f));
                    g.draw(new Line2D.Float(5.2f, 13.4f, 10.8f, 13.4f));
                }
                case CONTENT -> {
                    g.draw(new RoundRectangle2D.Float(2, 2.2f, 12, 3.2f, 2, 2));
                    g.draw(new RoundRectangle2D.Float(2, 6.4f, 12, 3.2f, 2, 2));
                    g.draw(new RoundRectangle2D.Float(2, 10.6f, 12, 3.2f, 2, 2));
                }
                case REMINDERS -> {
                    Path2D bell = new Path2D.Float();
                    bell.moveTo(3.2, 11.8);
                    bell.curveTo(4.4, 10.4, 4.4, 8.4, 4.4, 6.8);
                    bell.curveTo(4.4, 4.4, 6.1, 2.3, 8, 2.3);
                    bell.curveTo(9.9, 2.3, 11.6, 4.4, 11.6, 6.8);
                    bell.curveTo(11.6, 8.4, 11.6, 10.4, 12.8, 11.8);
                    bell.closePath();
                    g.draw(bell);
                    g.fill(new Ellipse2D.Float(7.1f, 13.2f, 1.8f, 1.8f));
                }
                case BACKUP -> {
                    g.draw(new Line2D.Float(5, 2.2f, 5, 12.8f));
                    g.draw(new Line2D.Float(11, 3.2f, 11, 13.8f));
                    g.draw(new Line2D.Float(2.8f, 4.4f, 5, 2.2f));
                    g.draw(new Line2D.Float(5, 2.2f, 7.2f, 4.4f));
                    g.draw(new Line2D.Float(8.8f, 11.6f, 11, 13.8f));
                    g.draw(new Line2D.Float(11, 13.8f, 13.2f, 11.6f));
                }
            }
            g.dispose();
        }
    }

    /** Pushes the dialog contents into the live settings and notifies the app. */
    private void applyNow() {
        if (liveSettings == null) return;
        applyToSettings(liveSettings);
        if (onApply != null) onApply.run();
        refreshPreview(); // reflect theme/bg immediately in the mini dashboard
    }

    /** Registers the callback that applies changes to the running app. */
    public void setOnApply(Runnable onApply) {
        this.onApply = onApply;
    }

    private void loadCityCountries() {
        // Register listeners before loading values so the first country is
        // initialized through the same path as every later country change.
        countryCombo.addActionListener(e -> {
            if (suppressCityEvents) return;
            detectedLocation = null; // user picked another country
            refreshCityCombo();
        });
        cityCombo.addActionListener(e -> {
            if (refreshingCities || suppressCityEvents) return;
            detectedLocation = null; // user picked another city
            updateLocationFromCity();
        });

        countryCombo.removeAllItems();
        for (String country : cityService.getCountries()) {
            countryCombo.addItem(country);
        }
        refreshCityCombo();
    }

    /** Rebuilds the city list and selects the first city of the new country. */
    private void refreshCityCombo() {
        String country = (String) countryCombo.getSelectedItem();
        refreshingCities = true;
        try {
            cityCombo.removeAllItems();
            if (country != null) {
                for (String city : cityService.getCitiesOf(country)) {
                    cityCombo.addItem(city);
                }
            }
        } finally {
            refreshingCities = false;
        }

        // JComboBox selects the first inserted city. Always refresh the
        // coordinates explicitly so values from the previous country cannot
        // remain in the fields.
        updateLocationFromCity();
    }

    // ---------------------------------------------------------------- tabs

    private JPanel buildLocationTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = grid();

        gbc.gridy = 0;
        panel.add(new JLabel("Mosque / location:"), gbc);
        gbc.gridx = 1;
        panel.add(mosqueField, gbc);

        gbc.gridy = 1; gbc.gridx = 0;
        panel.add(new JLabel("Country:"), gbc);
        gbc.gridx = 1;
        panel.add(countryCombo, gbc);

        gbc.gridy = 2; gbc.gridx = 0;
        panel.add(new JLabel("City:"), gbc);
        gbc.gridx = 1;
        panel.add(cityCombo, gbc);

        gbc.gridy = 3; gbc.gridx = 0;
        panel.add(new JLabel("Latitude:"), gbc);
        gbc.gridx = 1;
        panel.add(latField, gbc);

        gbc.gridy = 4; gbc.gridx = 0;
        panel.add(new JLabel("Longitude:"), gbc);
        gbc.gridx = 1;
        panel.add(lonField, gbc);

        gbc.gridy = 5; gbc.gridx = 0;
        panel.add(new JLabel("Timezone (UTC offset):"), gbc);
        gbc.gridx = 1;
        panel.add(timezoneField, gbc);

        gbc.gridy = 6; gbc.gridx = 0; gbc.gridwidth = 2;
        JPanel cityActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        cityActions.add(useMyLocationButton);
        JButton applyCity = new JButton("Apply selected city");
        applyCity.addActionListener(e -> applySelectedCity());
        cityActions.add(applyCity);
        panel.add(cityActions, gbc);

        gbc.gridy = 7; gbc.gridx = 0; gbc.gridwidth = 2;
        locationStatus.setFont(locationStatus.getFont().deriveFont(
                locationStatus.getFont().getSize2D() - 1f));
        locationStatus.setForeground(SETTINGS_MUTED);
        panel.add(locationStatus, gbc);

        gbc.gridy = 8; gbc.gridwidth = 2;
        panel.add(new JLabel("<html><i>“Use my location” takes the exact position reported by "
                + "this computer.<br>For best accuracy, turn on Windows location services "
                + "(Privacy &amp; security → Location).<br>You can also pick a city or type "
                + "coordinates manually.</i></html>"), gbc);

        useMyLocationButton.setToolTipText(
                "Detect this computer's exact position and apply it immediately");
        useMyLocationButton.addActionListener(e -> detectMyLocation());

        // Manual edits invalidate a previously detected fix (its name/country
        // no longer match the coordinates the user typed).
        javax.swing.event.DocumentListener clearDetected =
                new javax.swing.event.DocumentListener() {
                    @Override public void insertUpdate(javax.swing.event.DocumentEvent e) {
                        detectedLocation = null;
                    }

                    @Override public void removeUpdate(javax.swing.event.DocumentEvent e) {
                        detectedLocation = null;
                    }

                    @Override public void changedUpdate(javax.swing.event.DocumentEvent e) {
                        detectedLocation = null;
                    }
                };
        latField.getDocument().addDocumentListener(clearDetected);
        lonField.getDocument().addDocumentListener(clearDetected);
        timezoneField.getDocument().addDocumentListener(clearDetected);

        return panel;
    }

    private void applySelectedCity() {
        String country = (String) countryCombo.getSelectedItem();
        String city = (String) cityCombo.getSelectedItem();
        CityService.City found = (country == null || city == null)
                ? null : cityService.find(country, city);
        if (found != null) {
            // Built-in city: use its reference coordinates.
            latField.setText(Double.toString(found.latitude()));
            lonField.setText(Double.toString(found.longitude()));
            timezoneField.setText(Double.toString(found.timezone()));
            detectedLocation = null;
        }
        // A detected/custom place is not in the database: keep its exact
        // coordinates instead of clearing them.
        applyNow();
        locationStatus.setForeground(new Color(0x7F, 0xB6, 0x88));
        locationStatus.setText("📍 " + city + ", " + country + " applied.");
    }

    /** Exact position of this computer, applied straight to the live settings. */
    private void detectMyLocation() {
        useMyLocationButton.setEnabled(false);
        locationStatus.setForeground(SETTINGS_MUTED);
        locationStatus.setText("Detecting your location…");
        locationService.locateAsync(new PreciseLocationService.Callback() {
            @Override
            public void onLocation(PreciseLocationService.Fix fix) {
                useMyLocationButton.setEnabled(true);
                applyDetectedLocation(fix);
            }

            @Override
            public void onFailure(String message, boolean permissionBlocked) {
                useMyLocationButton.setEnabled(true);
                locationStatus.setForeground(new Color(0xE0, 0x85, 0x7A));
                locationStatus.setText(permissionBlocked
                        ? "Windows is blocking location access for desktop apps."
                        : "Could not detect your location: " + message);
                showLocationHelp(message, permissionBlocked);
            }
        });
    }

    private void applyDetectedLocation(PreciseLocationService.Fix fix) {
        Location loc = fix.location();

        // Show a non-built-in place (e.g. "Agdal") in the pickers without
        // letting their listeners overwrite the exact coordinates.
        suppressCityEvents = true;
        try {
            if (loc.getCountry() != null && !loc.getCountry().isBlank()) {
                if (!containsItem(countryCombo, loc.getCountry())) {
                    countryCombo.addItem(loc.getCountry());
                }
                countryCombo.setSelectedItem(loc.getCountry());
                rebuildCityCombo(loc.getCountry(), loc.getName());
            }
        } finally {
            suppressCityEvents = false;
        }

        latField.setText(String.valueOf(loc.getLatitude()));
        lonField.setText(String.valueOf(loc.getLongitude()));
        timezoneField.setText(String.valueOf(loc.getTimezone()));
        // Set after the field updates above (their listeners clear it).
        detectedLocation = loc;

        locationStatus.setForeground(fix.permissionBlocked()
                ? new Color(0xD9, 0xA4, 0x5C) : new Color(0x7F, 0xB6, 0x88));
        locationStatus.setText("📍 " + loc.getDisplayName() + " — "
                + formatCoordinate(loc.getLatitude()) + ", "
                + formatCoordinate(loc.getLongitude())
                + " (" + fix.accuracyText() + ") — applied.");

        applyNow(); // location is live immediately, no Save needed

        // We got a usable position, but it is the network approximation
        // because the OS is blocking the exact one: say so and offer the fix.
        if (fix.permissionBlocked()) {
            showLocationHelp(null, true);
        }
    }

    /**
     * Explains why no exact position could be obtained, with a shortcut to
     * the Windows privacy page that controls desktop-app location access.
     *
     * @param message          network error message, or {@code null} when an
     *                         approximate position was applied instead
     * @param permissionBlocked OS refused location access
     */
    private void showLocationHelp(String message, boolean permissionBlocked) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String reason = permissionBlocked
                ? "Windows is blocking location access.\n\n"
                : "Could not detect your location automatically:\n" + message + "\n\n";
        String text = permissionBlocked
                ? (message == null
                        ? "Only an approximate position could be used because "
                                + "Windows location access is off.\n\n"
                        : "")
                        + "For the exact position, turn on "
                        + "Settings → Privacy & security → Location:\n"
                        + "  • Location services\n"
                        + "  • Let desktop apps access your location\n\n"
                        + "Then press “Use my location” again."
                : reason + "Pick a city below or type the coordinates manually.";
        String[] options = (windows && permissionBlocked)
                ? new String[]{"Open Windows settings", "Close"}
                : new String[]{"OK"};
        int choice = JOptionPane.showOptionDialog(this, text,
                permissionBlocked ? "Exact location needs Windows permission"
                        : "Location unavailable",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[0]);
        if (windows && permissionBlocked && choice == 0) {
            try {
                new ProcessBuilder("cmd", "/c", "start", "",
                        "ms-settings:privacy-location").start();
            } catch (java.io.IOException e) {
                com.tawkit.infrastructure.Log.warn(
                        "Cannot open Windows location settings (" + e.getMessage() + ")");
            }
        }
    }

    private static String formatCoordinate(double value) {
        return String.format(java.util.Locale.ROOT, "%.5f", value);
    }

    private static boolean containsItem(JComboBox<String> combo, String value) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (value.equalsIgnoreCase(combo.getItemAt(i))) return true;
        }
        return false;
    }

    /** Rebuilds the city list and selects {@code city}, even if unknown. */
    private void rebuildCityCombo(String country, String city) {
        cityCombo.removeAllItems();
        for (String name : cityService.getCitiesOf(country)) {
            cityCombo.addItem(name);
        }
        if (city != null && !city.isBlank() && !containsItem(cityCombo, city)) {
            cityCombo.addItem(city);
        }
        if (city != null && !city.isBlank()) {
            cityCombo.setSelectedItem(city);
        }
    }

    private void updateLocationFromCity() {
        String country = (String) countryCombo.getSelectedItem();
        String city = (String) cityCombo.getSelectedItem();
        CityService.City found = (country == null || city == null)
                ? null : cityService.find(country, city);

        if (found == null) {
            // Do not keep coordinates/timezone belonging to the old country.
            latField.setText("");
            lonField.setText("");
            timezoneField.setText("");
            return;
        }

        latField.setText(Double.toString(found.latitude()));
        lonField.setText(Double.toString(found.longitude()));
        timezoneField.setText(Double.toString(found.timezone()));
    }

    private JPanel buildPrayerTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = grid();

        gbc.gridy = 0;
        panel.add(new JLabel("Calculation method:"), gbc);
        gbc.gridx = 1;
        panel.add(methodCombo, gbc);
        methodCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof CalculationMethod m) setText(m.getDisplayName());
                return this;
            }
        });

        gbc.gridy = 1; gbc.gridx = 0;
        panel.add(new JLabel("Asr method:"), gbc);
        gbc.gridx = 1;
        panel.add(asrCombo, gbc);
        asrCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof AsrMethod m) {
                    setText(m == AsrMethod.HANAFI ? "Hanafi (shadow x2)" : "Standard (shadow x1)");
                }
                return this;
            }
        });

        gbc.gridy = 2; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(new JLabel("Manual adjustments (minutes, ±30):"), gbc);
        gbc.gridwidth = 1;

        int row = 3;
        for (PrayerType type : PrayerType.values()) {
            JSpinner spinner = new JSpinner(new SpinnerNumberModel(0, -30, 30, 1));
            adjustSpinners.put(type, spinner);
            gbc.gridy = row; gbc.gridx = 0;
            panel.add(new JLabel(type.getDisplayName() + ":"), gbc);
            gbc.gridx = 1;
            panel.add(spinner, gbc);
            row++;
        }

        // ---- Jumu'a mode (#5) ----
        gbc.gridy = row + 1; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(jumuaCheck, gbc);
        gbc.gridwidth = 1;
        gbc.gridy = row + 2; gbc.gridx = 0;
        panel.add(new JLabel("Khutba time (Friday):"), gbc);
        gbc.gridx = 1;
        JPanel khutbaRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        khutbaRow.add(jumuaKhutbaField);
        khutbaRow.add(new JLabel("HH:MM"));
        panel.add(khutbaRow, gbc);
        return panel;
    }

    private JPanel buildIqamaTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = grid();

        gbc.gridy = 0; gbc.gridx = 0; gbc.gridwidth = 3;
        panel.add(new JLabel("Iqama: minutes after adhan, or a fixed clock time (HH:MM)."), gbc);
        gbc.gridwidth = 1;

        int row = 1;
        for (PrayerType type : IQAMA_PRAYERS) {
            JSpinner spinner = new JSpinner(new SpinnerNumberModel(15, 1, 120, 1));
            iqamaOffsets.put(type, spinner);

            JCheckBox fixedCheck = new JCheckBox();
            fixedChecks.put(type, fixedCheck);
            JTextField fixedField = new JTextField(6);
            fixedField.setEnabled(false);
            fixedFields.put(type, fixedField);
            fixedCheck.addActionListener(e -> fixedField.setEnabled(fixedCheck.isSelected()));

            gbc.gridy = row; gbc.gridx = 0;
            panel.add(new JLabel(type.getDisplayName() + ":"), gbc);
            gbc.gridx = 1;
            panel.add(spinner, gbc);
            gbc.gridx = 2;
            JPanel fixedPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            fixedPanel.add(fixedCheck);
            fixedPanel.add(fixedField);
            panel.add(fixedPanel, gbc);
            row++;
        }
        return panel;
    }

    private JPanel buildAudioTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = grid();

        gbc.gridy = 0; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(adhanCheck, gbc);
        gbc.gridwidth = 1;

        // ---- bundled recordings, chosen by name ----
        adhanCombo.setToolTipText(
                "Choose the adhan played at prayer times (applies to every prayer)");
        adhanCombo.addActionListener(e -> {
            if (rebuildingAdhanChoices) return;
            AdhanCatalog.Entry selected = (AdhanCatalog.Entry) adhanCombo.getSelectedItem();
            if (selected != null) lastAdhanSelection = selected;
            previewAudio.stop();
            updateAdhanSelectionStatus();
        });
        refreshAdhanChoices();

        gbc.gridy = 1; gbc.gridx = 0;
        panel.add(new JLabel("Adhan recitation:"), gbc);
        gbc.gridx = 1;
        JButton previewAdhan = styleButton(new JButton("▶ Preview"), false);
        previewAdhan.setToolTipText("Listen to the selected recording");
        previewAdhan.addActionListener(e -> previewSelectedAdhan());
        JPanel recitationRow = new JPanel(new BorderLayout(6, 0));
        recitationRow.setOpaque(false);
        recitationRow.add(adhanCombo, BorderLayout.CENTER);
        recitationRow.add(previewAdhan, BorderLayout.EAST);
        panel.add(recitationRow, gbc);

        adhanSearch.setToolTipText("Type a name to narrow the list");
        adhanSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) {
                refreshAdhanChoices();
            }

            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) {
                refreshAdhanChoices();
            }

            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) {
                refreshAdhanChoices();
            }
        });

        gbc.gridy = 2; gbc.gridx = 0;
        panel.add(new JLabel("Find adhan:"), gbc);
        gbc.gridx = 1;
        panel.add(adhanSearch, gbc);

        gbc.gridy = 3; gbc.gridx = 0; gbc.gridwidth = 2;
        JLabel recitationHint = new JLabel(
                "<html><i>Applies to all prayers. A custom file below overrides it.</i></html>");
        recitationHint.setForeground(SETTINGS_MUTED);
        panel.add(recitationHint, gbc);
        gbc.gridwidth = 1;

        gbc.gridy = 4; gbc.gridx = 0;
        panel.add(new JLabel("Volume:"), gbc);
        gbc.gridx = 1;
        panel.add(volumeSlider, gbc);

        gbc.gridy = 5; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(preFajrAlertCheck, gbc);
        gbc.gridwidth = 1;

        gbc.gridy = 6; gbc.gridx = 0;
        panel.add(new JLabel("Fajr alert (min):"), gbc);
        gbc.gridx = 1;
        panel.add(preFajrMinutes, gbc);

        // Live test so the slider is verifiable immediately
        gbc.gridy = 7; gbc.gridx = 0;
        panel.add(new JLabel(""), gbc);
        gbc.gridx = 1;
        JButton testSound = new JButton("🔊 Test sound");
        testSound.addActionListener(e -> {
            double vol = volumeSlider.getValue() / 100.0;
            Thread player = new Thread(() -> {
                try {
                    com.tawkit.infrastructure.audio.ToneGenerator.playSequence(
                            com.tawkit.infrastructure.audio.ToneGenerator.alertBeeps(), vol);
                } catch (Exception ex) {
                    com.tawkit.infrastructure.Log.warn("Sound test failed ("
                            + ex.getMessage() + ")");
                }
            }, "tawkit-soundtest");
            player.setDaemon(true);
            player.start();
        });
        JPanel testRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        testRow.add(testSound);
        panel.add(testRow, gbc);

        gbc.gridy = 8; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(new JLabel("Or use your own audio file for every prayer:"), gbc);

        JButton chooseAllAdhan = styleButton(
                new JButton("Choose file for all prayers"), false);
        chooseAllAdhan.addActionListener(e -> chooseAdhanForAllPrayers());
        JButton useBundledAdhan = styleButton(new JButton("Reset to default"), false);
        useBundledAdhan.addActionListener(e -> resetAdhanFiles());
        JPanel allPrayerRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        allPrayerRow.setOpaque(false);
        allPrayerRow.add(chooseAllAdhan);
        allPrayerRow.add(useBundledAdhan);
        gbc.gridy = 9; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(allPrayerRow, gbc);

        gbc.gridy = 10; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(adhanSelectionStatus, gbc);

        // ---- Per-prayer custom adhan files (#6) ----
        gbc.gridy = 11; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(new JLabel("Custom adhan file per prayer (blank = selected adhan):"), gbc);
        gbc.gridwidth = 1;
        int fileRow = 12;
        for (PrayerType type : IQAMA_PRAYERS) {
            JTextField field = new JTextField(14);
            adhanFileFields.put(type, field);
            field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    updateAdhanSelectionStatus();
                }

                @Override public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    updateAdhanSelectionStatus();
                }

                @Override public void changedUpdate(javax.swing.event.DocumentEvent e) {
                    updateAdhanSelectionStatus();
                }
            });
            JButton browse = new JButton("Choose...");
            browse.addActionListener(e -> chooseAdhanFile(field));

            gbc.gridy = fileRow; gbc.gridx = 0;
            panel.add(new JLabel(type.getDisplayName() + ":"), gbc);
            gbc.gridx = 1;
            JPanel row = new JPanel(new BorderLayout(4, 0));
            row.add(field, BorderLayout.CENTER);
            row.add(browse, BorderLayout.EAST);
            panel.add(row, gbc);
            fileRow++;
        }
        return panel;
    }

    /** Rebuilds the combo from the search text, keeping a sensible selection. */
    private void refreshAdhanChoices() {
        String filter = adhanSearch.getText().trim().toLowerCase(java.util.Locale.ROOT);
        AdhanCatalog.Entry previous = (AdhanCatalog.Entry) adhanCombo.getSelectedItem();
        if (previous == null) previous = lastAdhanSelection;

        rebuildingAdhanChoices = true;
        try {
            adhanCombo.removeAllItems();
            for (AdhanCatalog.Entry entry : AdhanCatalog.choices()) {
                if (filter.isEmpty()
                        || entry.displayName().toLowerCase(java.util.Locale.ROOT)
                                .contains(filter)) {
                    adhanCombo.addItem(entry);
                }
            }
            if (adhanCombo.getItemCount() > 0) {
                adhanCombo.setSelectedItem(previous);
                if (!(adhanCombo.getSelectedItem() instanceof AdhanCatalog.Entry)) {
                    adhanCombo.setSelectedIndex(0);
                }
                if (adhanCombo.getSelectedItem() instanceof AdhanCatalog.Entry current) {
                    lastAdhanSelection = current;
                }
            }
        } finally {
            rebuildingAdhanChoices = false;
        }
        updateAdhanSelectionStatus();
    }

    private void previewSelectedAdhan() {
        AdhanCatalog.Entry entry = (AdhanCatalog.Entry) adhanCombo.getSelectedItem();
        if (entry == null) return;
        previewAudio.stop();
        previewAudio.setEnabled(true);
        previewAudio.setVolume(volumeSlider.getValue() / 100.0);
        previewAudio.setSelectedAdhan(entry.id());
        // ADHAN with no prayer plays the chosen recording even for Fajr entries.
        previewAudio.play(AudioService.Sound.ADHAN, null);
    }

    private void chooseAdhanFile(JTextField target) {
        String path = selectAdhanFile();
        if (path != null) {
            target.setText(path);
            updateAdhanSelectionStatus();
        }
    }

    private void chooseAdhanForAllPrayers() {
        String path = selectAdhanFile();
        if (path == null) return;
        for (PrayerType type : IQAMA_PRAYERS) {
            adhanFileFields.get(type).setText(path);
        }
        updateAdhanSelectionStatus();
    }

    private void resetAdhanFiles() {
        for (JTextField field : adhanFileFields.values()) {
            field.setText("");
        }
        adhanSearch.setText("");
        if (adhanCombo.getItemCount() > 0) {
            adhanCombo.setSelectedIndex(0);
        }
        lastAdhanSelection = AdhanCatalog.BUNDLED_DEFAULT;
        previewAudio.stop();
        updateAdhanSelectionStatus();
    }

    private String selectAdhanFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Audio (WAV, MP3)", "wav", "mp3"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            return chooser.getSelectedFile().getAbsolutePath();
        }
        return null;
    }

    private void updateAdhanSelectionStatus() {
        String selectedPath = null;
        boolean hasDifferentPaths = false;
        for (JTextField field : adhanFileFields.values()) {
            String path = field.getText().trim();
            if (path.isEmpty()) continue;
            if (selectedPath == null) {
                selectedPath = path;
            } else if (!selectedPath.equals(path)) {
                hasDifferentPaths = true;
                break;
            }
        }

        if (hasDifferentPaths) {
            adhanSelectionStatus.setText("Different custom file selected per prayer");
        } else if (selectedPath != null) {
            adhanSelectionStatus.setText("Custom file: " + new File(selectedPath).getName());
        } else if (adhanCombo.getItemCount() == 0) {
            String query = adhanSearch.getText().trim();
            adhanSelectionStatus.setText(query.isEmpty()
                    ? "No adhan available"
                    : "No adhan matches \"" + query + "\"");
        } else {
            AdhanCatalog.Entry entry = (AdhanCatalog.Entry) adhanCombo.getSelectedItem();
            adhanSelectionStatus.setText(entry == null || entry.id().isBlank()
                    ? "Using bundled adhan (Hassan II Mosque)"
                    : "Selected: " + entry.displayName());
        }
    }

    private JPanel buildDisplayTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = grid();

        gbc.gridy = 0;
        panel.add(new JLabel("Language:"), gbc);
        gbc.gridx = 1;
        panel.add(languageCombo, gbc);

        gbc.gridy = 1; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(checkRow(clock24Check, secondsCheck), gbc);

        gbc.gridy = 2; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(dimCheck, gbc);
        gbc.gridwidth = 1;

        gbc.gridy = 3; gbc.gridx = 0;
        panel.add(new JLabel("Dim duration (min after iqama):"), gbc);
        gbc.gridx = 1;
        panel.add(dimMinutes, gbc);

        gbc.gridy = 4; gbc.gridx = 0;
        panel.add(new JLabel("Hijri day offset (±3):"), gbc);
        gbc.gridx = 1;
        panel.add(hijriOffset, gbc);

        gbc.gridy = 5; gbc.gridx = 0;
        panel.add(new JLabel("Background theme:"), gbc);
        gbc.gridx = 1;
        panel.add(themeCombo, gbc);

        gbc.gridy = 7; gbc.gridx = 0;
        panel.add(new JLabel("Temperature unit:"), gbc);
        gbc.gridx = 1;
        panel.add(unitCombo, gbc);

        gbc.gridy = 6; gbc.gridx = 0;
        panel.add(new JLabel("Custom background image:"), gbc);
        gbc.gridx = 1;
        JPanel bgRow = new JPanel(new BorderLayout(4, 0));
        bgRow.add(customBgField, BorderLayout.CENTER);
        customBgBrowse.addActionListener(e -> chooseBackgroundImage());
        bgRow.add(customBgBrowse, BorderLayout.EAST);
        panel.add(bgRow, gbc);

        // ---- Live dashboard preview (#15) ----
        gbc.gridy = 8; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(new JLabel("Live preview (theme, background, mosque name):"), gbc);
        gbc.gridwidth = 1;
        gbc.gridy = 9; gbc.gridx = 0; gbc.gridwidth = 2;
        previewScreen.setPreferredSize(new Dimension(340, 192));
        previewScreen.setMosqueName(mosqueField.getText());
        previewScreen.applyTheme((String) themeCombo.getSelectedItem());
        seedPreviewWithPrayerData(); // realistic dashboard content
        themeCombo.addActionListener(e -> previewScreen.applyTheme(
                (String) themeCombo.getSelectedItem()));
        customBgField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { refreshPreview(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { refreshPreview(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { refreshPreview(); }
        });
        mosqueField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) {
                previewScreen.setMosqueName(mosqueField.getText());
            }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) {
                previewScreen.setMosqueName(mosqueField.getText());
            }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) {
                previewScreen.setMosqueName(mosqueField.getText());
            }
        });
        panel.add(previewScreen, gbc);
        return panel;
    }

    /**
     * Fills the mini dashboard with today's real times (computed from the
     * live settings) so the preview mirrors what the kiosk actually shows.
     */
    private void seedPreviewWithPrayerData() {
        try {
            AppSettings s = liveSettings != null ? liveSettings : new AppSettings();
            var loc = s.getLocation();
            double lat = loc != null ? loc.getLatitude() : 33.5731;
            double lon = loc != null ? loc.getLongitude() : -7.5898;
            double tz = com.tawkit.service.prayer.PrayerScheduler.resolveTimezone(loc);
            var raw = new com.tawkit.service.prayer.PrayerTimeCalculator().compute(
                    java.time.LocalDate.now(), lat, lon, tz,
                    s.getCalculationMethod(), s.getAsrMethod());
            var day = new com.tawkit.model.prayer.DailyPrayerTimes(java.time.LocalDate.now());
            for (PrayerType type : PrayerType.values()) {
                if (raw.get(type) != null) {
                    day.put(type, new com.tawkit.model.prayer.PrayerEntry(
                            type, raw.get(type), null));
                }
            }
            previewScreen.setPrayerTimes(day);
            previewScreen.setClockMode(s.isUse24HourClock(), s.isShowSeconds());
            var h = new com.tawkit.service.prayer.HijriCalendarService()
                    .toHijri(java.time.LocalDate.now());
            previewScreen.setDate(java.time.LocalDate.now(),
                    h.getDay(), h.getMonth(), h.getYear());
            previewScreen.setCountdown("--:--", "Next Prayer", 0.4);
        } catch (Exception e) {
            com.tawkit.infrastructure.Log.warn("Preview seeding failed: " + e.getMessage());
        }
    }

    /** Applies the custom background (or theme) to the mini preview (#15). */
    private void refreshPreview() {
        if (previewUpdating) return;
        previewUpdating = true;
        try {
            String path = customBgField.getText().trim();
            if ("custom".equals(themeCombo.getSelectedItem()) && !path.isEmpty()) {
                previewScreen.applyCustomImage(path);
            } else {
                previewScreen.applyTheme((String) themeCombo.getSelectedItem());
            }
        } finally {
            previewUpdating = false;
        }
    }

    private void chooseBackgroundImage() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Images", "jpg", "jpeg", "png"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            customBgField.setText(chooser.getSelectedFile().getAbsolutePath());
            themeCombo.setSelectedItem("custom");
        }
    }

    private JPanel buildContentTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = grid();

        gbc.gridy = 0; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(checkRow(slidesCheck, weatherCheck), gbc);
        gbc.gridwidth = 1;

        gbc.gridy = 1; gbc.gridx = 0;
        panel.add(new JLabel("Content rotation (seconds):"), gbc);
        gbc.gridx = 1;
        rotationSpinner.setPreferredSize(new Dimension(110, 30));
        rotationSpinner.setMaximumSize(new Dimension(110, 30));
        gbc.fill = GridBagConstraints.NONE;
        panel.add(rotationSpinner, gbc);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridy = 2; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(new JLabel("Announcements (one per line, shown in the ticker):"), gbc);
        gbc.gridwidth = 1;
        gbc.gridy = 3; gbc.gridx = 0; gbc.gridwidth = 2;
        announcementsArea.setLineWrap(true);
        JScrollPane announcementsScroll = new JScrollPane(announcementsArea);
        announcementsScroll.setPreferredSize(new Dimension(480, 160));
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1;
        gbc.weighty = 1;
        panel.add(announcementsScroll, gbc);
        return panel;
    }

    private JPanel buildRemindersTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        panel.add(new JLabel("<html><b>Sound alerts</b> — syntax: "
                + "<code>15.BEFORE.FAJR.(message)</code>, <code>10.AFTER.MAGHRIB.(msg)</code>, "
                + "<code>NISF.(msg)</code> midnight, <code>TULT.(msg)</code> last third of night.<br>"
                + "Prayers: FAJR, SHRQ, DOHR, ASSR, MGRB, ISHA</html>"), BorderLayout.NORTH);

        JList<String> list = new JList<>(reminderModel);
        panel.add(new JScrollPane(list), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JTextField newRule = new JTextField(24);
        JButton add = new JButton("Add");
        add.addActionListener(e -> {
            String rule = newRule.getText().trim();
            if (!rule.isBlank()) {
                reminderModel.addElement(rule);
                newRule.setText("");
            }
        });
        JButton remove = new JButton("Remove selected");
        remove.addActionListener(e -> {
            int idx = list.getSelectedIndex();
            if (idx >= 0) reminderModel.remove(idx);
        });
        buttons.add(newRule);
        buttons.add(add);
        buttons.add(remove);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildBackupTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = grid();

        gbc.gridy = 0; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(new JLabel("Copy your settings to another screen:"), gbc);
        gbc.gridwidth = 1;

        gbc.gridy = 1; gbc.gridx = 0;
        JButton exportBtn = new JButton("Export settings…");
        exportBtn.addActionListener(e -> exportSettings());
        panel.add(exportBtn, gbc);

        gbc.gridy = 2; gbc.gridx = 0;
        JButton importBtn = new JButton("Import settings…");
        importBtn.addActionListener(e -> importSettings());
        panel.add(importBtn, gbc);

        gbc.gridy = 3; gbc.gridx = 0; gbc.gridwidth = 2;
        panel.add(new JLabel("<html><i>The file is saved / loaded as "
                + "settings.properties (UTF-8).</i></html>"), gbc);
        return panel;
    }

    private void exportSettings() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("tawkit-settings.properties"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            AppSettings temp = new AppSettings();
            applyToSettings(temp);
            new SettingsService().exportTo(Path.of(chooser.getSelectedFile().getAbsolutePath()), temp);
            JOptionPane.showMessageDialog(this, "Settings exported.", "Export",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Export failed: " + ex.getMessage(),
                    "Export", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void importSettings() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Settings (*.properties)", "properties"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            AppSettings imported = new SettingsService()
                    .importFrom(Path.of(chooser.getSelectedFile().getAbsolutePath()));
            loadFromSettings(imported);
            JOptionPane.showMessageDialog(this, "Settings loaded. Press Save to apply.",
                    "Import", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Import failed: " + ex.getMessage(),
                    "Import", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static GridBagConstraints grid() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;
        return gbc;
    }

    // ---------------------------------------------------------- load/apply

    public void loadFromSettings(AppSettings settings) {
        if (settings == null) return;

        mosqueField.setText(settings.getMosqueName());
        var loc = settings.getLocation();
        if (loc != null) {
            // Keep exact/custom places (e.g. detected by "Use my location")
            // selectable even when they are not in the built-in city list.
            suppressCityEvents = true;
            try {
                if (loc.getCountry() != null && !loc.getCountry().isBlank()) {
                    if (!containsItem(countryCombo, loc.getCountry())) {
                        countryCombo.addItem(loc.getCountry());
                    }
                    countryCombo.setSelectedItem(loc.getCountry());
                    rebuildCityCombo(loc.getCountry(), loc.getName());
                }
            } finally {
                suppressCityEvents = false;
            }
            latField.setText(String.valueOf(loc.getLatitude()));
            lonField.setText(String.valueOf(loc.getLongitude()));
            timezoneField.setText(String.valueOf(loc.getTimezone()));
            detectedLocation = loc;
        }
        methodCombo.setSelectedItem(settings.getCalculationMethod());
        asrCombo.setSelectedItem(settings.getAsrMethod());

        for (PrayerType type : PrayerType.values()) {
            adjustSpinners.get(type).setValue(settings.getAdjust(type));
        }

        IqamaSettings iqama = settings.getIqamaSettings();
        for (PrayerType type : IQAMA_PRAYERS) {
            boolean fixed = iqama.getMode(type) == IqamaSettings.IqamaMode.FIXED
                    && iqama.getFixedTime(type) != null;
            fixedChecks.get(type).setSelected(fixed);
            fixedFields.get(type).setEnabled(fixed);
            fixedFields.get(type).setText(fixed ? iqama.getFixedTime(type).toString() : "");
            if (fixed) {
                iqamaOffsets.get(type).setValue(iqama.getOffsetMinutes(type));
            } else {
                iqamaOffsets.get(type).setValue(iqama.getOffsetMinutes(type));
            }
        }

        adhanCheck.setSelected(settings.isAdhanEnabled());
        volumeSlider.setValue((int) Math.round(settings.getAudioVolume() * 100));
        preFajrAlertCheck.setSelected(settings.isPreFajrAlertEnabled());
        preFajrMinutes.setValue(settings.getPreFajrAlertMinutes());
        adhanSearch.setText("");
        adhanCombo.setSelectedItem(AdhanCatalog.byId(settings.getAdhanSoundId()));
        if (adhanCombo.getSelectedItem() instanceof AdhanCatalog.Entry loadedAdhan) {
            lastAdhanSelection = loadedAdhan;
        }
        for (PrayerType type : IQAMA_PRAYERS) {
            adhanFileFields.get(type).setText(settings.getAdhanSoundFile(type)); // #6
        }
        updateAdhanSelectionStatus();

        jumuaCheck.setSelected(settings.isJumuaEnabled()); // #5
        jumuaKhutbaField.setText(settings.getJumuaKhutbaTime() != null
                ? settings.getJumuaKhutbaTime().toString() : "12:45");

        languageCombo.setSelectedItem(settings.getLanguage());
        clock24Check.setSelected(settings.isUse24HourClock());
        secondsCheck.setSelected(settings.isShowSeconds());
        dimCheck.setSelected(settings.isScreenDimDuringPrayer());
        dimMinutes.setValue(settings.getPrayerDimMinutes());
        hijriOffset.setValue(settings.getHijriOffsetDays());
        themeCombo.setSelectedItem(settings.getBackgroundTheme());
        customBgField.setText(settings.getCustomBackgroundPath());
        unitCombo.setSelectedItem(settings.getTemperatureUnit() == 'F' ? "°F" : "°C");

        slidesCheck.setSelected(settings.isSlidesEnabled());
        weatherCheck.setSelected(settings.isWeatherEnabled());
        rotationSpinner.setValue(settings.getContentRotationSeconds());
        announcementsArea.setText(String.join("\n", settings.getAnnouncements()));

        reminderModel.clear();
        for (String rule : settings.getReminders()) {
            reminderModel.addElement(rule);
        }
    }

    public void applyToSettings(AppSettings settings) {
        if (settings == null) return;

        settings.setMosqueName(mosqueField.getText().trim());
        try {
            double lat = latField.getText().isBlank() ? 0 : Double.parseDouble(latField.getText().trim());
            double lon = lonField.getText().isBlank() ? 0 : Double.parseDouble(lonField.getText().trim());
            double tz = timezoneField.getText().isBlank() ? 0 : Double.parseDouble(timezoneField.getText().trim());
            String city = (String) cityCombo.getSelectedItem();
            String country = (String) countryCombo.getSelectedItem();

            // A detected fix keeps its real place name and ISO country code
            // (needed for the flag) as long as the user did not move the
            // coordinates manually.
            Location detected = detectedLocation;
            boolean stillDetected = detected != null
                    && Math.abs(detected.getLatitude() - lat) < 1e-6
                    && Math.abs(detected.getLongitude() - lon) < 1e-6;

            Location location;
            if (stillDetected) {
                location = new Location(
                        city == null || city.isBlank() ? detected.getName() : city,
                        country == null || country.isBlank() ? detected.getCountry() : country,
                        lat, lon, tz);
                location.setCountryCode(detected.getCountryCode());
            } else {
                location = new Location(city, country, lat, lon, tz);
                location.setCountryCode(CountryCodes.fromCountryName(country));
            }
            settings.setLocation(location);
        } catch (NumberFormatException ignored) {
        }

        settings.setCalculationMethod((CalculationMethod) methodCombo.getSelectedItem());
        settings.setAsrMethod((AsrMethod) asrCombo.getSelectedItem());
        for (PrayerType type : PrayerType.values()) {
            settings.setAdjust(type, (Integer) adjustSpinners.get(type).getValue());
        }

        IqamaSettings iqama = settings.getIqamaSettings();
        for (PrayerType type : IQAMA_PRAYERS) {
            int offset = (Integer) iqamaOffsets.get(type).getValue();
            if (fixedChecks.get(type).isSelected()) {
                try {
                    String text = fixedFields.get(type).getText().trim();
                    if (!text.isBlank()) {
                        iqama.setFixed(type, LocalTime.parse(text));
                        continue;
                    }
                } catch (DateTimeParseException ignored) {
                }
            }
            iqama.setOffset(type, offset);
        }

        settings.setAdhanEnabled(adhanCheck.isSelected());
        settings.setAudioVolume(volumeSlider.getValue() / 100.0);
        settings.setPreFajrAlertEnabled(preFajrAlertCheck.isSelected());
        settings.setPreFajrAlertMinutes((Integer) preFajrMinutes.getValue());
        AdhanCatalog.Entry chosen = (AdhanCatalog.Entry) adhanCombo.getSelectedItem();
        if (chosen == null) chosen = lastAdhanSelection;
        settings.setAdhanSoundId(chosen == null ? "" : chosen.id());
        for (PrayerType type : IQAMA_PRAYERS) {
            settings.setAdhanSoundFile(type, adhanFileFields.get(type).getText().trim()); // #6
        }

        settings.setJumuaEnabled(jumuaCheck.isSelected()); // #5
        try {
            String kt = jumuaKhutbaField.getText().trim();
            if (!kt.isBlank()) settings.setJumuaKhutbaTime(LocalTime.parse(kt));
        } catch (DateTimeParseException ignored) {
        }

        settings.setLanguage((String) languageCombo.getSelectedItem());
        settings.setUse24HourClock(clock24Check.isSelected());
        settings.setShowSeconds(secondsCheck.isSelected());
        settings.setScreenDimDuringPrayer(dimCheck.isSelected());
        settings.setPrayerDimMinutes((Integer) dimMinutes.getValue());
        settings.setHijriOffsetDays((Integer) hijriOffset.getValue());
        settings.setBackgroundTheme((String) themeCombo.getSelectedItem());
        settings.setCustomBackgroundPath(customBgField.getText().trim());
        settings.setTemperatureUnit("°F".equals(unitCombo.getSelectedItem()) ? 'F' : 'C');

        settings.setSlidesEnabled(slidesCheck.isSelected());
        settings.setWeatherEnabled(weatherCheck.isSelected());
        settings.setContentRotationSeconds((Integer) rotationSpinner.getValue());

        List<String> announcements = new ArrayList<>();
        for (String line : announcementsArea.getText().split("\\n")) {
            if (!line.isBlank()) announcements.add(line.trim());
        }
        settings.setAnnouncements(announcements);

        List<String> reminders = new ArrayList<>();
        for (int i = 0; i < reminderModel.size(); i++) {
            reminders.add(reminderModel.get(i));
        }
        settings.setReminders(reminders);
    }

    public boolean isSaved() {
        return saved;
    }
}
