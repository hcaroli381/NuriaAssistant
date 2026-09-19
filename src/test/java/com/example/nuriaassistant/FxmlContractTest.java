package com.example.nuriaassistant;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
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

    private static Document view;
    private static Set<String> cssClasses;
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
