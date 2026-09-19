package com.example.nuriaassistant.ui;

import com.example.nuriaassistant.AssistantController;
import org.junit.jupiter.api.Test;

import javafx.scene.Node;
import javafx.scene.Parent;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

public class WeatherUiTest {

    private static int shapes(Node node) {
        if (node instanceof Parent parent) {
            int count = 0;
            for (Node child : parent.getChildrenUnmodifiable()) {
                count += 1 + shapes(child);
            }
            return count;
        }
        return 0;
    }

    @Test
    void everyConditionProducesAGlyph() {
        for (String condition : new String[]{"clear", "clouds", "rain", "drizzle",
                "thunderstorm", "snow", "Clear", "  RAIN  "}) {
            Node icon = WeatherUi.iconFor(condition);
            assertNotNull(icon, condition);
            assertTrue(shapes(icon) > 0, condition + " must draw something");
        }
    }

    @Test
    void unknownConditionsFallBackToTheThermometer() {
        Node fallback = WeatherUi.iconFor(null);
        assertEquals(2, shapes(fallback), "thermometer = tube + bulb");

        assertEquals(shapes(fallback), shapes(WeatherUi.iconFor("haze")));
        assertEquals(shapes(fallback), shapes(WeatherUi.iconFor("")));
    }

    @Test
    void conditionGlyphsDifferFromEachOther() {
        assertEquals(9, shapes(WeatherUi.iconFor("clear")), "sun = disc + 8 rays");
        assertEquals(3, shapes(WeatherUi.iconFor("clouds")), "cloud = two puffs + base");
        assertEquals(6, shapes(WeatherUi.iconFor("rain")), "cloud + three drops");
        assertEquals(5, shapes(WeatherUi.iconFor("drizzle")), "cloud + two drops");
        assertEquals(4, shapes(WeatherUi.iconFor("thunderstorm")), "cloud + bolt");
        assertEquals(12, shapes(WeatherUi.iconFor("snow")), "cloud + three flakes of three spokes each");
    }

    @Test
    void glyphStyleClassesAreAllStyled() throws Exception {
        String css;
        try (InputStream in = AssistantController.class.getResourceAsStream(
                "/com/example/nuriaassistant/styles.css")) {
            assertNotNull(in, "styles.css must be on the classpath");
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        Set<String> used = new LinkedHashSet<>();
        for (String condition : new String[]{"clear", "clouds", "rain", "drizzle",
                "thunderstorm", "snow", "unknown"}) {
            collectStyleClasses(WeatherUi.iconFor(condition), used);
        }

        Set<String> missing = new LinkedHashSet<>();
        for (String styleClass : used) {
            if (!Pattern.compile("\\." + Pattern.quote(styleClass) + "\\b").matcher(css).find()) {
                missing.add(styleClass);
            }
        }
        assertFalse(used.isEmpty(), "the glyphs must carry style classes");
        assertTrue(missing.isEmpty(), "weather glyph classes with no CSS rule: " + missing);
    }

    private static void collectStyleClasses(Node node, Set<String> target) {
        target.addAll(node.getStyleClass());
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectStyleClasses(child, target);
            }
        }
    }
}
