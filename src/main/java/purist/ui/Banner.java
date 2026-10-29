package purist.ui;

import java.awt.BorderLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import purist.ui.kit.PlainText;
import purist.ui.kit.TextButton;
import purist.ui.theme.Fonts;
import purist.ui.theme.PuristTheme;

/** The full-width device-loss warning under the top edge (spec §6, §10), in the skin's highlight colours. EDT only. */
public final class Banner extends JPanel {

    private final JLabel message = PlainText.of(new JLabel());
    private final TextButton retry = new TextButton("Retry", false);
    private Runnable onRetry = () -> {};

    public Banner() {
        super(new BorderLayout(12, 0));
        setBorder(new EmptyBorder(8, 14, 8, 10));
        message.setFont(Fonts.monoMedium(12f));
        retry.addActionListener(e -> onRetry.run());
        add(message, BorderLayout.CENTER);
        add(retry, BorderLayout.EAST);
        setVisible(false);
        restyle();
        PuristTheme.addListener(s -> restyle());
    }

    public void show(String text, Runnable retryAction) {
        message.setText(text);
        message.setFont(Fonts.forText(text, Fonts.monoMedium(12f)));
        onRetry = retryAction;
        setVisible(true);
        revalidate();
    }

    /** Removes the banner. (Not {@code hide()}: AWT's deprecated hide() is what setVisible(false) calls.) */
    public void dismiss() {
        setVisible(false);
        revalidate();
    }

    public boolean showing() {
        return isVisible();
    }

    String text() {
        return message.getText();
    }

    private void restyle() {
        setBackground(PuristTheme.skin().highlight);
        message.setForeground(PuristTheme.skin().highlightText);
    }
}
