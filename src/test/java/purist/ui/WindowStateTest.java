package purist.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WindowStateTest {

    private static final Rectangle SCREEN = new Rectangle(0, 0, 1920, 1080);

    @Test
    void roundTrip() {
        Rectangle r = new Rectangle(120, 80, 980, 680);
        assertEquals("120,80,980,680", WindowState.format(r));
        assertEquals(Optional.of(r), WindowState.parse(WindowState.format(r)));
    }

    @Test
    void negativeCoordinatesOfAMonitorLeftOfTheMainOneSurvive() {
        Rectangle r = new Rectangle(-1800, -20, 900, 600);
        assertEquals(Optional.of(r), WindowState.parse(WindowState.format(r)));
    }

    @Test
    void badStringEmpty() {
        for (String bad : new String[] {null, "", "  ", "1,2,3", "1,2,3,4,5", "a,b,c,d", "1,2,0,5", "1,2,5,-3",
                "1;2;3;4", "1,2,3,x"}) {
            assertEquals(Optional.empty(), WindowState.parse(bad), String.valueOf(bad));
        }
    }

    @Test
    void spacesAroundTheNumbersAreTolerated() {
        assertEquals(Optional.of(new Rectangle(1, 2, 3, 4)), WindowState.parse(" 1, 2 ,3 , 4 "));
    }

    @Test
    void aWindowInsideTheScreenIsLeftAlone() {
        Rectangle r = new Rectangle(100, 50, 980, 680);
        assertEquals(r, WindowState.clampToScreen(r, SCREEN));
    }

    @Test
    void offScreenClamped() {
        Rectangle far = new Rectangle(5000, 4000, 980, 680);
        Rectangle got = WindowState.clampToScreen(far, SCREEN);
        assertEquals(new Rectangle(940, 400, 980, 680), got, "pushed back so the whole window shows");
        assertTrue(SCREEN.contains(got));
        Rectangle neg = WindowState.clampToScreen(new Rectangle(-3000, -3000, 980, 680), SCREEN);
        assertEquals(new Rectangle(0, 0, 980, 680), neg);
    }

    @Test
    void aWindowBiggerThanTheScreenIsShrunkToIt() {
        assertEquals(new Rectangle(0, 0, 1920, 1080),
                WindowState.clampToScreen(new Rectangle(-50, -50, 4000, 3000), SCREEN));
    }

    @Test
    void theScreenThatHoldsMostOfTheWindowDecides() {
        Rectangle left = new Rectangle(-1920, 0, 1920, 1080);
        List<Rectangle> screens = List.of(SCREEN, left);
        Rectangle onLeft = new Rectangle(-1000, 100, 980, 680);
        assertEquals(onLeft, WindowState.clamp(onLeft, screens), "already on the left monitor");
        Rectangle straddling = new Rectangle(-300, 100, 980, 680); // mostly on the main one
        assertEquals(new Rectangle(0, 100, 980, 680), WindowState.clamp(straddling, screens));
    }

    @Test
    void aWindowOnAMonitorThatIsGoneComesBackToTheFirstScreen() {
        List<Rectangle> only = List.of(SCREEN);
        assertEquals(new Rectangle(0, 400, 980, 680),
                WindowState.clamp(new Rectangle(-1000, 5000, 980, 680), only));
    }

    @Test
    void noScreensLeavesTheBoundsAsTheyAre() {
        Rectangle r = new Rectangle(1, 2, 3, 4);
        assertEquals(r, WindowState.clamp(r, List.of()));
    }
}
