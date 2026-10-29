package purist.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.DoubleConsumer;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import purist.audio.Kind;
import purist.audio.SignalPath;
import purist.audio.StreamFormat;
import purist.decode.TrackInfo;
import purist.player.PlayerState;
import purist.player.PlayerStatus;
import purist.ui.kit.BitPerfectIndicator;
import purist.ui.kit.Icons;
import purist.ui.kit.PlainText;
import purist.ui.kit.SquareButton;
import purist.ui.theme.Fonts;
import purist.ui.theme.PuristTheme;
import purist.ui.theme.Skin;

/**
 * The deck (spec §9), always visible at the top of the window. It holds:
 * <ul>
 *   <li>the LCD: big time digits, the title marquee, the format readouts, the bit-perfect light and the signal
 *       path;</li>
 *   <li>the seek line;</li>
 *   <li>the transport row.</li>
 * </ul>
 * {@link #update} is called from a 10 Hz Swing timer.
 */
public final class Deck extends JPanel {

    /** What the deck's controls do; MainWindow wires them to the Player. */
    public interface Actions {
        default void prev() {}
        default void play() {}
        default void pause() {}
        default void stop() {}
        default void next() {}
        default void open() {}
        default void seek(double seconds) {}
        default void shuffle(boolean on) {}
        default void repeat(boolean on) {}
    }

    private static final int MARQUEE_GAP = 5;

    private final JLabel header = small("PURIST");
    private final JLabel device = small("");
    private final JLabel time = PlainText.of(new JLabel("--:--"));
    private final JLabel stateLine = small("STOP");
    private final JLabel title = PlainText.of(new JLabel(" "));
    private final JLabel codec = readout("");
    private final JLabel rate = readout("");
    private final JLabel channels = readout("");
    private final BitPerfectIndicator indicator = new BitPerfectIndicator();
    private final JLabel path = small(" ");
    private final JLabel dropouts = small("");
    private final SeekLine seek;
    private final SquareButton shuffle;
    private final SquareButton repeat;

    private String badge = "";
    private String marqueeText = "";
    private int marqueeOffset;

    public Deck(Actions a) {
        super(new BorderLayout(0, 10));
        setBorder(new EmptyBorder(10, 12, 10, 12));
        seek = new SeekLine(a::seek);

        JPanel top = transparent(new BorderLayout());
        top.add(header, BorderLayout.WEST);
        top.add(device, BorderLayout.EAST);

        JPanel lcd = new Lcd();
        lcd.setLayout(new GridBagLayout());
        lcd.setBorder(new EmptyBorder(12, 14, 12, 14));
        time.setFont(Fonts.display(52f));
        title.setFont(Fonts.display(17f));
        indicator.setFont(Fonts.display(12f).deriveFont(Map.of(TextAttribute.TRACKING, 0.08f)));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 0, 22);
        JPanel left = transparent(null);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.add(time);
        left.add(stateLine);
        lcd.add(left, c);
        JPanel right = transparent(null);
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        title.setAlignmentX(LEFT_ALIGNMENT);
        right.add(title);
        right.add(Box.createVerticalStrut(8));
        JPanel readouts = transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        readouts.setAlignmentX(LEFT_ALIGNMENT);
        for (JComponent r : new JComponent[] {codec, rate, channels, indicator}) {
            r.setBorder(new EmptyBorder(0, 0, 0, 16));
            readouts.add(r);
        }
        right.add(readouts);
        right.add(Box.createVerticalStrut(6));
        JPanel pathRow = transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        pathRow.setAlignmentX(LEFT_ALIGNMENT);
        dropouts.setBorder(new EmptyBorder(0, 14, 0, 0));
        pathRow.add(path);
        pathRow.add(dropouts);
        right.add(pathRow);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 0);
        lcd.add(right, c);

        JPanel transport = transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        transport.add(button(Icons::prev, "Previous (Ctrl+Left)", a::prev));
        transport.add(button(Icons::play, "Play", a::play));
        transport.add(button(Icons::pause, "Pause (Space)", a::pause));
        transport.add(button(Icons::stop, "Stop", a::stop));
        transport.add(button(Icons::next, "Next (Ctrl+Right)", a::next));
        transport.add(Box.createHorizontalStrut(8));
        transport.add(button(Icons::eject, "Open files (Ctrl+O)", a::open));
        shuffle = new SquareButton(Icons::shuffle, 30, "Shuffle");
        shuffle.addActionListener(e -> {
            shuffle.setSelected(!shuffle.isSelected());
            a.shuffle(shuffle.isSelected());
        });
        repeat = new SquareButton(Icons::repeat, 30, "Repeat all");
        repeat.addActionListener(e -> {
            repeat.setSelected(!repeat.isSelected());
            a.repeat(repeat.isSelected());
        });
        JPanel modes = transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        modes.add(shuffle);
        modes.add(repeat);
        JPanel controls = transparent(new BorderLayout());
        controls.add(transport, BorderLayout.WEST);
        controls.add(modes, BorderLayout.EAST);

        JPanel middle = transparent(new BorderLayout(0, 12));
        middle.add(lcd, BorderLayout.NORTH);
        middle.add(seek, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);
        add(middle, BorderLayout.CENTER);
        add(controls, BorderLayout.SOUTH);
        restyle();
        PuristTheme.addListener(s -> restyle());
        update((PlayerStatus) null);
    }

    /** Shows {@code s}; null means nothing is loaded. */
    public void update(PlayerStatus s) {
        boolean active = s != null && s.state() != PlayerState.STOPPED && s.track() != null;
        TrackInfo t = s == null ? null : s.track();
        SignalPath p = s == null ? null : s.signalPath();
        time.setText(active ? time(s.positionSeconds()) : "--:--");
        stateLine.setText(stateText(s));
        stateLine.setIcon(stateIcon(s));
        setMarquee(t == null ? "PURIST — NO TRACK LOADED" : marquee(t));
        badge = t == null ? "" : t.badge();
        codec.setText(t == null ? "" : t.codec());
        rate.setText(t == null || t.format() == null ? "" : rateText(t.format()));
        channels.setText(t == null || t.format() == null ? "" : channelText(t.format().channels()));
        indicator.update(s == null ? Optional.empty() : s.notBitPerfectReason(), active && p != null);
        device.setText(p == null ? "ASIO" : "ASIO · " + p.deviceName().toUpperCase(Locale.ROOT) + " · "
                + p.bufferFrames());
        // The LCD font has no arrow glyph, so the path reads "24-bit 96 kHz > INT32_LSB @ 96000 Hz".
        path.setText(p == null || !active ? " " : p.describe().replace(" → ", " > "));
        long n = s == null ? 0 : s.dropoutsThisTrack();
        dropouts.setText(n == 1 ? "1 DROPOUT" : n + " DROPOUTS");
        dropouts.setVisible(active && n > 0);
        seek.set(active ? s.positionSeconds() : 0, active ? s.durationSeconds() : 0);
    }

    /** {@code m:ss}, or {@code h:mm:ss} from one hour. */
    public static String time(double seconds) {
        long t = (long) Math.max(0, seconds);
        long h = t / 3600;
        long m = t / 60 % 60;
        long sec = t % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    String badgeText() {
        return badge;
    }

    String rateText() {
        return rate.getText();
    }

    String stateText() {
        return stateLine.getText();
    }

    String pathText() {
        return path.getText();
    }

    String indicatorText() {
        return indicator.getText();
    }

    boolean dropoutsShown() {
        return dropouts.isVisible();
    }

    String titleText() {
        return marqueeText;
    }

    private static String stateText(PlayerStatus s) {
        if (s == null || s.state() == PlayerState.STOPPED) {
            return "STOP";
        }
        String track = String.format(Locale.ROOT, " · TRACK %02d", s.index() + 1);
        return (s.state() == PlayerState.PAUSED ? "PAUSE" : "PLAY") + track;
    }

    /** The state as a drawn icon: the LCD font has no ▶ or ■. */
    private static javax.swing.Icon stateIcon(PlayerStatus s) {
        java.util.function.Supplier<Color> c = () -> PuristTheme.skin().dim;
        if (s == null || s.state() == PlayerState.STOPPED) {
            return Icons.stop(11, c);
        }
        return s.state() == PlayerState.PAUSED ? Icons.pause(11, c) : Icons.play(11, c);
    }

    private static String marquee(TrackInfo t) {
        String name = t.tags().first("TITLE").orElse(fileName(t));
        StringBuilder sb = new StringBuilder(name);
        t.tags().first("ARTIST").ifPresent(a -> sb.append(" — ").append(a));
        t.tags().first("ALBUM").ifPresent(a -> sb.append(" — ").append(a));
        return sb.toString().toUpperCase(Locale.ROOT);
    }

    private static String fileName(TrackInfo t) {
        String f = t.path().getFileName() == null ? t.path().toString() : t.path().getFileName().toString();
        int dot = f.lastIndexOf('.');
        return dot > 0 ? f.substring(0, dot) : f;
    }

    private static String rateText(StreamFormat f) {
        if (f.kind() == Kind.PCM) {
            BigDecimal k = BigDecimal.valueOf(f.sampleRate()).movePointLeft(3).stripTrailingZeros();
            String khz = k.scale() <= 0 ? k.setScale(1).toPlainString() : k.toPlainString();
            return f.bits() + " BIT   " + khz + " kHz";
        }
        return f.label() + "   "
                + BigDecimal.valueOf(f.sampleRate()).movePointLeft(6).stripTrailingZeros().toPlainString() + " MHz";
    }

    private static String channelText(int n) {
        return switch (n) {
            case 1 -> "MONO";
            case 2 -> "STEREO";
            default -> n + " CH";
        };
    }

    /** Scrolls the title one character per update when it is wider than the LCD (the Winamp marquee). */
    private void setMarquee(String text) {
        if (!text.equals(marqueeText)) {
            marqueeText = text;
            marqueeOffset = 0;
        }
        title.setFont(Fonts.forText(text, Fonts.display(17f)));
        int room = title.getWidth();
        if (room <= 0 || title.getFontMetrics(title.getFont()).stringWidth(text) <= room) {
            title.setText(text);
            return;
        }
        String loop = text + " ".repeat(MARQUEE_GAP) + "***" + " ".repeat(MARQUEE_GAP);
        marqueeOffset = (marqueeOffset + 1) % loop.length();
        title.setText(loop.substring(marqueeOffset) + loop.substring(0, marqueeOffset));
    }

    private void restyle() {
        Skin s = PuristTheme.skin();
        setBackground(s.chassis);
        header.setForeground(s.listDim);
        device.setForeground(s.listDim);
        for (JComponent c : new JComponent[] {time, title, codec, rate, channels, indicator}) {
            c.setForeground(s.segment);
        }
        stateLine.setForeground(s.dim);
        path.setForeground(s.dim);
        dropouts.setForeground(s.segment);
        repaint();
    }

    private static JPanel transparent(java.awt.LayoutManager layout) {
        JPanel p = layout == null ? new JPanel() : new JPanel(layout);
        p.setOpaque(false);
        return p;
    }

    private static JLabel small(String text) {
        JLabel l = PlainText.of(new JLabel(text));
        l.setFont(Fonts.display(11f).deriveFont(Map.of(TextAttribute.TRACKING, 0.15f)));
        return l;
    }

    private static JLabel readout(String text) {
        JLabel l = PlainText.of(new JLabel(text));
        l.setFont(Fonts.display(12f).deriveFont(Map.of(TextAttribute.TRACKING, 0.08f)));
        return l;
    }

    private static SquareButton button(java.util.function.BiFunction<Integer, java.util.function.Supplier<Color>,
            javax.swing.Icon> icon, String tip, Runnable action) {
        SquareButton b = new SquareButton(icon, 30, tip);
        b.addActionListener(e -> action.run());
        return b;
    }

    /** The LCD panel: the skin's display colour with a 3 px radius. */
    private static final class Lcd extends JPanel {
        Lcd() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(PuristTheme.skin().lcd);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
            } finally {
                g.dispose();
            }
        }
    }

    /** A 3 px seek line; click or drag to seek. */
    private static final class SeekLine extends JComponent {
        private final DoubleConsumer onSeek;
        private double position;
        private double duration;

        SeekLine(DoubleConsumer onSeek) {
            this.onSeek = onSeek;
            setPreferredSize(new Dimension(100, 12));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Seek");
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent e) {
                    if (duration > 0 && getWidth() > 0) {
                        double f = Math.clamp((double) e.getX() / getWidth(), 0.0, 1.0);
                        onSeek.accept(f * duration);
                    }
                }
            };
            addMouseListener(m);
        }

        void set(double pos, double dur) {
            position = pos;
            duration = dur;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Skin s = PuristTheme.skin();
            int y = getHeight() / 2 - 1;
            g.setColor(s.edge);
            g.fillRect(0, y, getWidth(), 3);
            if (duration > 0) {
                g.setColor(s.dark ? s.segment : s.highlight);
                g.fillRect(0, y, (int) Math.round(getWidth() * Math.min(1, position / duration)), 3);
            }
        }
    }
}
