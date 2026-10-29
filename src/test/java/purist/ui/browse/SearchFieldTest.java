package purist.ui.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import purist.ui.kit.PlainText;
import purist.ui.theme.Fonts;

class SearchFieldTest {

    @Test
    void thePromptArrowIsOnlyUsedWhenTheLcdFontCanDrawIt() {
        Font lcd = Fonts.display(13f);
        String glyph = SearchField.promptArrow(lcd);
        assertEquals(lcd.canDisplay('▸') ? "▸" : ">", glyph);
        // A font that certainly lacks the arrow gets the plain one.
        Font none = new Font(Font.MONOSPACED, Font.PLAIN, 13) {
            @Override
            public boolean canDisplay(char c) {
                return false;
            }
        };
        assertEquals(">", SearchField.promptArrow(none));
    }

    @Test
    void thePromptReadsSearchThenTheArrow() {
        assertEquals("SEARCH > ", SearchField.promptText(">"));
    }

    @Test
    void typingIsReportedOnceAfterThePauseWithTheFinalText() throws Exception {
        List<String> got = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        SearchField[] f = new SearchField[1];
        SwingUtilities.invokeAndWait(() -> {
            f[0] = new SearchField(30, text -> {
                got.add(text);
                done.countDown();
            });
            f[0].setText("a");
            f[0].setText("ar");
            f[0].setText("art");
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        Thread.sleep(150);
        assertEquals(List.of("art"), got, "three edits, one query");
    }

    @Test
    void clearingTheFieldReportsTheEmptyText() throws Exception {
        List<String> got = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(2);
        SearchField[] f = new SearchField[1];
        SwingUtilities.invokeAndWait(() -> {
            f[0] = new SearchField(20, text -> {
                got.add(text);
                done.countDown();
            });
            f[0].setText("x");
        });
        Thread.sleep(120);
        SwingUtilities.invokeAndWait(() -> f[0].clear());
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("x", ""), got);
    }

    @Test
    void theFieldTextIsNeverTreatedAsHtml() throws Exception {
        SearchField[] f = new SearchField[1];
        SwingUtilities.invokeAndWait(() -> f[0] = new SearchField(250, t -> { }));
        assertTrue(PlainText.is(f[0].prompt()));
    }

    @Test
    void enterAndEscapeHandTheKeyboardBack() throws Exception {
        List<String> got = new CopyOnWriteArrayList<>();
        int[] released = {0};
        SwingUtilities.invokeAndWait(() -> {
            SearchField f = new SearchField(10_000, got::add);
            f.onDone(() -> released[0]++);
            f.setText("nugent");
            f.field().postActionEvent(); // Enter
            assertEquals(1, released[0], "Enter lets go of the keyboard");
            f.field().getActionMap().get("clear").actionPerformed(
                    new java.awt.event.ActionEvent(f.field(), java.awt.event.ActionEvent.ACTION_PERFORMED, "clear"));
            assertEquals(2, released[0], "so does Escape");
            assertEquals("", f.text());
        });
        assertEquals("nugent", got.get(0), "Enter ran the query at once");
    }
}
