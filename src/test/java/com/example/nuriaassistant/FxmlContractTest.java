package com.example.nuriaassistant;

import javafx.fxml.FXMLLoader;
import javafx.scene.shape.SVGPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the three-way contract between hello-view.fxml, AssistantController and
 * styles.css. The FXML is only ever exercised when the app starts on the Pi, so a
 * typo in an fx:id, a handler name or a style class used to surface as a blank
 * screen minutes into a deployment instead of as a red test.
 */
public class FxmlContractTest {

    private static final String FXML = "com/example/nuriaassistant/hello-view.fxml";
    private static final String CSS = "com/example/nuriaassistant/styles.css";

    /** Shape tags whose default paint is a solid black fill. */
    private static final Set<String> FILLED_SHAPES = Set.of("Circle", "Rectangle", "SVGPath", "Path", "Polygon");

    private static Document view;
    private static Set<String> cssClasses;
    private static Map<String, String> cssRules;
    private static Set<String> fields;
    private static Set<String> methods;

    @BeforeAll
    static void load() throws Exception {
        // Class-relative lookup (not ClassLoader): this project is a JPMS module,
        // so resources of a qualified-opened package are invisible to the
        // classloader but reachable through the module itself.
        try (InputStream in = AssistantController.class.getResourceAsStream("/" + FXML)) {
            assertNotNull(in, FXML + " must be on the classpath");
            view = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
        }

        String css;
        try (InputStream in = AssistantController.class.getResourceAsStream("/" + CSS)) {
            assertNotNull(in, CSS + " must be on the classpath");
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        cssClasses = new LinkedHashSet<>();
        Matcher selector = Pattern.compile("\\.([A-Za-z][A-Za-z0-9_-]*)").matcher(css);
        while (selector.find()) {
            cssClasses.add(selector.group(1));
        }

        // class -> the declarations of its simplest rule (the first one whose
        // whole selector is that single class)
        cssRules = new java.util.LinkedHashMap<>();
        Matcher rule = Pattern.compile("(?m)^\\s*\\.([A-Za-z][A-Za-z0-9_-]*)\\s*\\{([^}]*)\\}").matcher(css);
        while (rule.find()) {
            cssRules.putIfAbsent(rule.group(1), rule.group(2));
        }

        fields = new LinkedHashSet<>();
        for (Field field : AssistantController.class.getDeclaredFields()) {
            fields.add(field.getName());
        }
        methods = new LinkedHashSet<>();
        for (Method method : AssistantController.class.getDeclaredMethods()) {
            methods.add(method.getName());
        }
    }

    private static void walk(Node node, java.util.function.Consumer<Element> visitor) {
        if (node instanceof Element element) {
            visitor.accept(element);
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            walk(children.item(i), visitor);
        }
    }

    @Test
    void svgIconsAreAcceptedByTheRealFxmlLoader() throws Exception {
        java.util.List<String> paths = new java.util.ArrayList<>();
        walk(view, element -> {
            if ("SVGPath".equals(element.getTagName())) {
                paths.add(element.getAttribute("content"));
            }
        });

        assertFalse(paths.isEmpty(), "Expected SVG path icons in the FXML");
        for (String pathData : paths) {
            String fragment = "<?import javafx.scene.shape.SVGPath?>"
                    + "<SVGPath xmlns:fx=\"http://javafx.com/fxml\" content=\"" + pathData + "\"/>";
            SVGPath path = new FXMLLoader().load(new ByteArrayInputStream(
                    fragment.getBytes(StandardCharsets.UTF_8)));
            assertFalse(path.getLayoutBounds().isEmpty(), "SVG icon must have drawable geometry: " + pathData);
        }
    }

    @Test
    void everyFxIdHasAControllerField() {
        Set<String> missing = new LinkedHashSet<>();
        walk(view, element -> {
            String id = element.getAttribute("fx:id");
            if (!id.isBlank() && !fields.contains(id)) {
                missing.add(id);
            }
        });
        assertTrue(missing.isEmpty(), "fx:id values with no controller field: " + missing);
    }

    @Test
    void everyInjectedFieldIsStillInTheFxml() {
        Set<String> ids = new LinkedHashSet<>();
        walk(view, element -> {
            String id = element.getAttribute("fx:id");
            if (!id.isBlank()) {
                ids.add(id);
            }
        });

        Set<String> orphans = new LinkedHashSet<>();
        for (Field field : AssistantController.class.getDeclaredFields()) {
            if (field.isAnnotationPresent(javafx.fxml.FXML.class) && !ids.contains(field.getName())) {
                orphans.add(field.getName());
            }
        }
        assertTrue(orphans.isEmpty(), "@FXML fields the FXML no longer injects (they stay null): " + orphans);
    }

    @Test
    void everyEventHandlerExists() {
        Set<String> missing = new LinkedHashSet<>();
        walk(view, element -> {
            NamedNodeMap attributes = element.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                String name = attributes.item(i).getNodeName();
                if (name.startsWith("on")) {
                    String handler = attributes.item(i).getNodeValue().replace("#", "").trim();
                    if (!handler.isEmpty() && !methods.contains(handler)) {
                        missing.add(name + "=#" + handler);
                    }
                }
            }
        });
        assertTrue(missing.isEmpty(), "FXML handlers with no controller method: " + missing);
    }

    /**
     * A shape is painted only by its own rule. A node carrying several glyph
     * classes never resolves against styles.css: JavaFX keeps its style map empty
     * and marks the node CLEAN, so the shape silently renders with the default
     * black fill and no stroke — that is exactly what turned the alarm, calendar
     * and WiFi glyphs into black blobs. One class per glyph, and that class must
     * carry the paint: -fx-fill for a filled shape, -fx-stroke for a Line.
     */
    @Test
    void everyShapeIsPaintedByASingleCompleteRule() {
        Set<String> offenders = new LinkedHashSet<>();
        walk(view, element -> {
            String tag = element.getTagName();
            if (!FILLED_SHAPES.contains(tag) && !"Line".equals(tag)) {
                return;
            }
            String styleClass = element.getAttribute("styleClass").trim();
            if (styleClass.isBlank()) {
                return; // clips and other unpainted shapes
            }
            Set<String> classes = new LinkedHashSet<>(java.util.Arrays.asList(styleClass.split("\\s+")));
            if (classes.size() != 1) {
                offenders.add(tag + " styleClass=\"" + styleClass + "\" carries " + classes.size()
                        + " classes; a multi-class glyph node never matches styles.css and renders black");
                return;
            }
            String body = cssRules.getOrDefault(classes.iterator().next(), "");
            if (FILLED_SHAPES.contains(tag) && !body.contains("-fx-fill")) {
                offenders.add(tag + " ." + classes.iterator().next() + " has no -fx-fill (defaults to black)");
            }
            if ("Line".equals(tag) && !body.contains("-fx-stroke")) {
                offenders.add("Line ." + classes.iterator().next() + " has no -fx-stroke (defaults to black)");
            }
        });
        assertTrue(offenders.isEmpty(), "Shapes that would render unstyled: " + offenders);
    }

    private static double size(Element element, String attribute) {
        String value = element.getAttribute(attribute);
        assertFalse(value.isBlank(),
                "<" + element.getTagName() + " styleClass=\"" + element.getAttribute("styleClass")
                        + "\"> must declare " + attribute + ": the enclosing HBox resizes any child "
                        + "that leaves it open");
        return Double.parseDouble(value);
    }

    private static Element byStyleClass(String styleClass) {
        java.util.List<Element> found = new java.util.ArrayList<>();
        walk(view, element -> {
            for (String name : element.getAttribute("styleClass").trim().split("\\s+")) {
                if (name.equals(styleClass)) {
                    found.add(element);
                }
            }
        });
        assertEquals(1, found.size(), "Expected exactly one node styled ." + styleClass);
        return found.get(0);
    }

    /**
     * The album cover is square, so its card must be pinned to that same square:
     * an HBox stretches children to the full row height, and a card that is only
     * given prefWidth/prefHeight grows to ~480px and letterboxes the 320px cover
     * inside two navy bars. The min/max pair (and matching ImageView fit size) is
     * what keeps the cover flush with its frame.
     */
    @Test
    void spotifyArtCardStaysTheSizeOfItsCover() {
        Element card = byStyleClass("spotify-art-card");
        double prefWidth = size(card, "prefWidth");
        double prefHeight = size(card, "prefHeight");

        assertEquals(prefWidth, size(card, "maxWidth"), 0.0,
                "Without a maxWidth the HBox stretches the cover card horizontally");
        assertEquals(prefHeight, size(card, "maxHeight"), 0.0,
                "Without a maxHeight the HBox stretches the cover card to the row height, "
                        + "which frames the square cover in two navy bars");
        assertEquals(prefWidth, size(card, "minWidth"), 0.0,
                "The cover card must not shrink below the cover either");
        assertEquals(prefHeight, size(card, "minHeight"), 0.0,
                "The cover card must not shrink below the cover either");

        java.util.List<Element> covers = new java.util.ArrayList<>();
        walk(card, element -> {
            if ("ImageView".equals(element.getTagName())) {
                covers.add(element);
            }
        });
        assertEquals(1, covers.size(), "Expected one ImageView inside .spotify-art-card");
        assertEquals(prefWidth, size(covers.get(0), "fitWidth"), 0.0,
                "The cover must be drawn at the card's full width");
        assertEquals(prefHeight, size(covers.get(0), "fitHeight"), 0.0,
                "The cover must be drawn at the card's full height");
    }

    @Test
    void everyFxmlStyleClassIsStyled() {
        Set<String> missing = new LinkedHashSet<>();
        walk(view, element -> {
            String styleClass = element.getAttribute("styleClass");
            if (styleClass.isBlank()) {
                return;
            }
            for (String name : styleClass.trim().split("\\s+")) {
                if (!cssClasses.contains(name)) {
                    missing.add(name);
                }
            }
        });
        assertTrue(missing.isEmpty(), "style classes used in the FXML but never styled: " + missing);
    }
}
