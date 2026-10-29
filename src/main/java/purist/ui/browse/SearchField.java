package purist.ui.browse;

import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.font.TextAttribute;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import purist.ui.kit.PlainText;
import purist.ui.theme.Fonts;
import purist.ui.theme.PuristTheme;
import purist.ui.theme.Skin;

/**
 * The search box (spec §9): an LCD strip, the skin's display colour with its segment-coloured text in Share Tech
 * Mono, a {@code SEARCH ▸} prompt, and the query typed after it. Typing is reported once, {@code debounceMillis}
 * after the last keystroke; Enter reports at once and Escape clears the box. Both then hand the keyboard back
 * (see {@link #onDone}), so Space and the arrows work as the window's shortcuts again. EDT only.
 */
public final class SearchField extends JPanel {

    /** The pause after the last keystroke before the query runs. */
    public static final int DEBOUNCE_MILLIS = 250;

    private static final char ARROW = '▸';

    private final JLabel prompt;
    private final JTextField field = new JTextField();
    private final Timer timer;
    private final Consumer<String> onQuery;
    private Runnable onDone = () -> { };

    public SearchField(int debounceMillis, Consumer<String> onQuery) {
        super(new java.awt.BorderLayout(0, 0));
        this.onQuery = onQuery;
        prompt = PlainText.of(new JLabel(promptText(promptArrow(Fonts.display(13f)))));
        prompt.setFont(Fonts.display(13f).deriveFont(Map.of(TextAttribute.TRACKING, 0.1f)));
        field.setFont(Fonts.display(13f));
        field.setOpaque(false);
        field.setBorder(BorderFactory.createEmptyBorder());
        field.setToolTipText("Search the library: artist HAS nugent, year GREATER 1980, format IS FLAC");
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
        add(prompt, java.awt.BorderLayout.WEST);
        add(field, java.awt.BorderLayout.CENTER);

        timer = new Timer(debounceMillis, e -> onQuery.accept(field.getText()));
        timer.setRepeats(false);
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                changed();
            }
        });
        field.addActionListener(e -> {
            timer.stop();
            onQuery.accept(field.getText());
            onDone.run();
        });
        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clear");
        field.getActionMap().put("clear", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                clear();
                onDone.run();
            }
        });
        restyle();
        PuristTheme.addListener(s -> restyle());
    }

    /** {@code ▸} when the LCD font can draw it, otherwise {@code >}. */
    static String promptArrow(Font lcd) {
        return lcd.canDisplay(ARROW) ? String.valueOf(ARROW) : ">";
    }

    static String promptText(String arrow) {
        return "SEARCH " + arrow + " ";
    }

    public String text() {
        return field.getText();
    }

    public void setText(String text) {
        field.setText(text);
    }

    /** Empties the box; that is reported like any edit. */
    public void clear() {
        field.setText("");
    }

    /** Called after Enter or Escape in the box: the owner moves the keyboard focus out of the text box. */
    public void onDone(Runnable handler) {
        onDone = handler;
    }

    /** The text box, for tests. */
    JTextField field() {
        return field;
    }

    public void focusField() {
        field.requestFocusInWindow();
        field.selectAll();
    }

    /** The prompt label, for tests. */
    JLabel prompt() {
        return prompt;
    }

    private void changed() {
        field.setFont(Fonts.forText(field.getText(), Fonts.display(13f)));
        timer.restart();
    }

    private void restyle() {
        Skin s = PuristTheme.skin();
        prompt.setForeground(s.dim);
        field.setForeground(s.segment);
        field.setCaretColor(s.segment);
        field.setSelectionColor(s.segment);
        field.setSelectedTextColor(s.lcd);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Skin s = PuristTheme.skin();
            g.setColor(s.lcd);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
            g.setColor(s.edge);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 6, 6);
        } finally {
            g.dispose();
        }
    }
}
