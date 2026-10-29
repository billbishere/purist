package purist.ui;

import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import purist.app.Main;
import purist.ui.kit.PlainText;
import purist.ui.kit.SectionLabel;
import purist.ui.kit.TextButton;
import purist.ui.theme.Fonts;
import purist.ui.theme.PuristTheme;
import purist.ui.theme.Skin;

/**
 * Settings (spec §9). It has three sections:
 * <ul>
 *   <li><b>Output</b>: one list of every ASIO driver and WASAPI device, {@code OPEN DRIVER PANEL} (ASIO only), for a
 *       WASAPI device the opt-in "This DAC supports DoP (DSD over PCM)" (off by default: DoP sent to a DAC that does
 *       not decode it is full-scale noise) and a status line;</li>
 *   <li><b>Skin</b>: Paper LCD, Phosphor or Ink;</li>
 *   <li><b>Library</b>: the section {@link #setLibrary} is given;</li>
 *   <li><b>About</b>: the version, the licence and the ASIO trademark notice.</li>
 * </ul>
 * MainWindow owns what the choices do. EDT only.
 */
public final class SettingsView extends JPanel {

    private final JComboBox<OutputChoice> outputs;
    private final Predicate<OutputChoice> dopOf;
    private final TextButton panel = new TextButton("Open driver panel", false);
    private final JCheckBox dop = PlainText.of(new JCheckBox(DOP_TEXT));
    private final JLabel dopHint = PlainText.of(new JLabel(
            "Leave it off unless your DAC decodes DoP; otherwise DSD would play as loud noise. Off: DSD tracks are skipped."));
    private final JLabel status = PlainText.of(new JLabel(" "));
    private final List<TextButton> skinButtons = new ArrayList<>();
    private final SectionLabel libraryTitle = new SectionLabel("Library");
    private final JPanel libraryHolder = new JPanel(new java.awt.BorderLayout());
    private boolean quiet;
    private OutputChoice shown;
    private Consumer<OutputChoice> onOutput;
    /** The output the app is using; a pick of any other one is a change. */
    private OutputChoice committed;
    /** True from the moment the list opens until it closes; a change of selection inside that window is a pick. */
    private boolean popupOpen;

    static final String DOP_TEXT = "This DAC supports DoP (DSD over PCM)";

    /**
     * @param dopOf whether the user has said a WASAPI device decodes DoP
     * @param onDop called when the user ticks or clears that box for a device
     */
    public SettingsView(List<OutputChoice> choices, OutputChoice selected, Consumer<OutputChoice> onOutput,
            Runnable onPanel, Consumer<Skin> onSkin, Predicate<OutputChoice> dopOf,
            BiConsumer<OutputChoice, Boolean> onDop) {
        super(new GridBagLayout());
        setBorder(new EmptyBorder(16, 24, 16, 24));
        outputs = new JComboBox<>(choices.toArray(OutputChoice[]::new));
        outputs.setRenderer(new javax.swing.DefaultListCellRenderer() {
            {
                PlainText.of(this);
            }

            @Override
            public Component getListCellRendererComponent(javax.swing.JList<?> l, Object v, int i, boolean s,
                    boolean f) {
                return super.getListCellRendererComponent(l, v instanceof OutputChoice c ? c.label() : "None", i,
                        s, f);
            }
        });
        outputs.setPrototypeDisplayValue(new OutputChoice(OutputChoice.Type.WASAPI, "",
                "Speakers (2- TOPPING USB DAC) (not connected)", true));
        if (selected != null) {
            outputs.setSelectedItem(selected);
        }
        // The combo takes no keyboard focus, so a stray arrow key or letter cannot change the output. Its selection
        // can then only change while the list is open (the mouse picking an item) or by code (refreshOutputs,
        // restore), so a change while the list is open is the user's choice. It is decided in the ActionListener,
        // which the look and feel fires BEFORE it closes the popup; a mouse listener on the list would run after the
        // popup has closed and could never see the pick.
        outputs.setFocusable(false);
        this.onOutput = onOutput;
        this.committed = selected;
        outputs.addActionListener(e -> {
            if (outputs.getSelectedItem() instanceof OutputChoice c) {
                markPanel(c);
                markDop(c, dopOf);
                if (popupOpen && !quiet && !c.equals(committed)) {
                    committed = c;
                    onOutput.accept(c);
                }
            }
        });
        outputs.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                popupOpen = true;
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                popupOpen = false;
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                popupOpen = false;
            }
        });
        panel.addActionListener(e -> onPanel.run());
        markPanel(selected);
        this.dopOf = dopOf;
        dop.setOpaque(false);
        dop.setFocusable(false); // Space must not toggle DoP: sent to a DAC that cannot decode it, DoP is loud noise
        dop.setFocusPainted(false);
        dop.setFont(Fonts.mono(12f));
        dopHint.setFont(Fonts.mono(12f));
        dop.addActionListener(e -> {
            if (shown != null) {
                onDop.accept(shown, dop.isSelected());
            }
        });
        markDop(selected, dopOf);

        JPanel dopRow = row();
        dopRow.add(dop);
        JPanel hintRow = row();
        hintRow.add(dopHint);
        JPanel driverRow = row();
        driverRow.add(outputs);
        driverRow.add(panel);
        status.setFont(Fonts.mono(12f));
        if (choices.isEmpty()) {
            status.setText("No output device found. Connect a DAC or install its ASIO driver, then restart Purist.");
        }

        JPanel skinGrid = new JPanel(new GridLayout(0, 4, 8, 8)); // eight skins: two rows of four
        skinGrid.setOpaque(false);
        JPanel skinRow = row();
        skinRow.add(skinGrid);
        for (Skin s : Skin.values()) {
            TextButton b = new TextButton(s.label, false);
            b.addActionListener(e -> {
                onSkin.accept(s);
                markSkin(s);
            });
            skinButtons.add(b);
            skinGrid.add(b);
        }
        markSkin(PuristTheme.skin());

        JLabel about = PlainText.of(new JLabel("Purist " + Main.version()
                + " · GPL-3.0-or-later · ASIO is a trademark of Steinberg Media Technologies GmbH"));
        about.setFont(Fonts.mono(12f));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        add(new SectionLabel("Output"), at(c, 0, 0));
        add(driverRow, at(c, 8, 0));
        add(dopRow, at(c, 4, 0));
        add(hintRow, at(c, 0, 0));
        add(status, at(c, 8, 0));
        add(new SectionLabel("Skin"), at(c, 28, 0));
        add(skinRow, at(c, 8, 0));
        libraryTitle.setVisible(false);
        libraryHolder.setVisible(false);
        add(libraryTitle, at(c, 28, 0));
        add(libraryHolder, at(c, 8, 0));
        add(new SectionLabel("About"), at(c, 28, 0));
        c.weighty = 1;
        add(about, at(c, 8, 0));
        restyle(about);
        PuristTheme.addListener(s -> restyle(about));
    }

    /** Shows a one-line output status such as "Topping USB Audio Device ready" or an error. */
    public void setStatus(String text) {
        status.setText(text == null || text.isEmpty() ? " " : text);
        status.setFont(Fonts.forText(status.getText(), Fonts.mono(12f)));
    }

    /** Replaces the output list (e.g. a DAC was switched on) and selects {@code selected}, without firing callbacks. */
    public void setChoices(List<OutputChoice> choices, OutputChoice selected) {
        quiet = true;
        try {
            outputs.setModel(new javax.swing.DefaultComboBoxModel<>(choices.toArray(OutputChoice[]::new)));
            OutputChoice match = selected == null ? null : choices.stream()
                    .filter(c -> c.type() == selected.type() && c.key().equals(selected.key()))
                    .findFirst().orElse(selected);
            outputs.setSelectedItem(match);
            committed = match;
            markPanel(match);
            markDop(match, dopOf);
        } finally {
            quiet = false;
        }
    }

    /** Selects {@code c} without firing the output callback. */
    public void selectOutput(OutputChoice c) {
        quiet = true;
        try {
            outputs.setSelectedItem(c);
            committed = c;
            markPanel(c);
            markDop(c, dopOf);
        } finally {
            quiet = false;
        }
    }

    /** Shows {@code library} (the LIBRARY section) between Skin and About. */
    public void setLibrary(javax.swing.JComponent library) {
        libraryHolder.removeAll();
        libraryHolder.setOpaque(false);
        libraryHolder.add(library, java.awt.BorderLayout.CENTER);
        libraryTitle.setVisible(true);
        libraryHolder.setVisible(true);
        revalidate();
    }

    /** The output combo, for tests. */
    javax.swing.JComboBox<OutputChoice> outputsBox() {
        return outputs;
    }

    /** The DoP opt-in box, for tests. */
    JCheckBox dopBox() {
        return dop;
    }

    /** {@code OPEN DRIVER PANEL} belongs to ASIO drivers; WASAPI devices have none. */
    private void markPanel(OutputChoice c) {
        panel.setEnabled(c != null && c.type() == OutputChoice.Type.ASIO);
    }

    /** The DoP opt-in belongs to WASAPI devices (ASIO plays DSD natively); it shows that device's saved answer. */
    private void markDop(OutputChoice c, Predicate<OutputChoice> dopOf) {
        shown = c != null && c.type() == OutputChoice.Type.WASAPI ? c : null;
        dop.setVisible(shown != null);
        dopHint.setVisible(shown != null);
        dop.setSelected(shown != null && dopOf.test(shown));
    }

    private void markSkin(Skin s) {
        for (int i = 0; i < skinButtons.size(); i++) {
            skinButtons.get(i).setSelected(Skin.values()[i] == s);
        }
    }

    private static JPanel row() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.setOpaque(false);
        return p;
    }

    private static GridBagConstraints at(GridBagConstraints c, int top, int left) {
        c.gridy = GridBagConstraints.RELATIVE;
        c.insets = new Insets(top, left, 0, 0);
        return c;
    }

    private void restyle(JLabel about) {
        Skin s = PuristTheme.skin();
        setBackground(s.chassis);
        status.setForeground(s.listDim);
        about.setForeground(s.listDim);
        dop.setForeground(s.buttonText);
        dopHint.setForeground(s.listDim);
    }
}
