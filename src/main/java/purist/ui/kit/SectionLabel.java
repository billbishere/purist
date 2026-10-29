package purist.ui.kit;

import java.awt.font.TextAttribute;
import java.util.Locale;
import java.util.Map;
import javax.swing.JLabel;
import purist.ui.theme.Fonts;
import purist.ui.theme.PuristTheme;

/** A section heading such as {@code OUTPUT}: IBM Plex Mono Medium 11 px, UPPERCASE, tracking 0.18, dim. */
public class SectionLabel extends JLabel {

    public SectionLabel(String text) {
        super(text.toUpperCase(Locale.ROOT));
        PlainText.of(this);
        restyle();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        restyle();
    }

    private void restyle() {
        // A look-and-feel switch resets font and colour, so both are reapplied after every updateUI.
        setFont(Fonts.monoMedium(11f).deriveFont(Map.of(TextAttribute.TRACKING, 0.18f)));
        setForeground(PuristTheme.skin().listDim);
    }
}
