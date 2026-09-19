package com.example.nuriaassistant.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;

import java.util.Locale;

/**
 * Current-conditions glyphs for the home weather row.
 *
 * <p>Emoji used to do this job, which rendered differently on every machine and
 * never matched the rest of the UI. These are built from the same primitives as
 * the rest of Alpha's icon family (24px grid, colours from styles.css), so the
 * row looks like part of her and keeps working on a Pi without an emoji font.
 *
 * <p>All shapes are pure geometry — no toolkit, no images — so they can be unit
 * tested without starting JavaFX.
 */
public final class WeatherUi {

    /** Grid the glyphs are drawn on (the caller scales it to the row's size). */
    public static final double GRID = 24.0;

    private WeatherUi() {
    }

    /**
     * Icon for an OpenWeatherMap condition ("clear", "clouds", "rain", ...).
     * Unknown/absent conditions get the thermometer, never an empty slot.
     */
    public static Node iconFor(String description) {
        String condition = description == null ? "" : description.trim().toLowerCase(Locale.ROOT);
        return switch (condition) {
            case "clear" -> sun();
            case "clouds" -> cloud(0);
            case "rain" -> cloud(3);
            case "drizzle" -> cloud(2);
            case "thunderstorm" -> storm();
            case "snow" -> snow();
            default -> thermometer();
        };
    }

    /** Capitalizes the first letter and lowercases the rest ("Clear sky" → "Clear sky"). */
    public static String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1).toLowerCase();
    }

    /** Sun disc plus eight rays, all stroke-drawn. */
    private static Group sun() {
        Group group = new Group();
        group.getChildren().add(circle(12.0, 12.0, 4.6, "weather-sun"));

        double[][] rays = {
                {12.0, 1.4, 12.0, 4.2}, {12.0, 19.8, 12.0, 22.6},
                {1.4, 12.0, 4.2, 12.0}, {19.8, 12.0, 22.6, 12.0},
                {4.5, 4.5, 6.5, 6.5}, {17.5, 4.5, 19.5, 6.5},
                {4.5, 19.5, 6.5, 17.5}, {17.5, 19.5, 19.5, 17.5}
        };
        for (double[] ray : rays) {
            group.getChildren().add(line(ray[0], ray[1], ray[2], ray[3], "weather-sun"));
        }
        return group;
    }

    /**
     * Cloud silhouette built from three overlapping filled shapes: because they
     * share one colour, the union reads as a single clean blob (overlapping
     * strokes would show their seams).
     *
     * @param drops number of drops falling below the cloud (0 = plain cloud).
     */
    private static Group cloud(int drops) {
        Group group = new Group();
        group.getChildren().addAll(
                circle(9.4, 12.4, 4.6, "weather-cloud"),
                circle(14.8, 11.2, 5.6, "weather-cloud"),
                pill(5.0, 13.4, 14.6, 4.2, "weather-cloud"));

        double[] dropX = {8.6, 12.0, 15.4};
        for (int i = 0; i < drops && i < dropX.length; i++) {
            group.getChildren().add(line(dropX[i], 19.0, dropX[i] - 1.0, 22.4, "weather-drop"));
        }
        return group;
    }

    /** Cloud with a bolt punched through the bottom. */
    private static Group storm() {
        Group group = cloud(0);
        SVGPath bolt = new SVGPath();
        bolt.setContent("M13 17.4 L10.9 20.9 L12.3 20.9 L11.2 23.6 L14 19.7 L12.5 19.7 Z");
        bolt.getStyleClass().add("weather-bolt");
        group.getChildren().add(bolt);
        return group;
    }

    /** Cloud with three six-point flakes below it. */
    private static Group snow() {
        Group group = cloud(0);
        double[][] flakes = {{8.4, 20.6}, {12.0, 22.2}, {15.6, 20.6}};
        for (double[] flake : flakes) {
            double x = flake[0];
            double y = flake[1];
            double r = 1.5;
            group.getChildren().addAll(
                    line(x - r, y - r, x + r, y + r, "weather-flake"),
                    line(x - r, y + r, x + r, y - r, "weather-flake"),
                    line(x, y - r, x, y + r, "weather-flake"));
        }
        return group;
    }

    /** Fallback glyph: thermometer, also shown when the readings are unusable. */
    private static Group thermometer() {
        Group group = new Group();
        group.getChildren().addAll(
                pill(10.9, 3.2, 2.6, 12.0, "weather-thermo"),
                circle(12.2, 18.4, 3.1, "weather-thermo-bulb"));
        return group;
    }

    private static Circle circle(double x, double y, double radius, String styleClass) {
        Circle circle = new Circle(x, y, radius);
        circle.getStyleClass().add(styleClass);
        return circle;
    }

    private static Line line(double startX, double startY, double endX, double endY, String styleClass) {
        Line line = new Line(startX, startY, endX, endY);
        line.getStyleClass().add(styleClass);
        return line;
    }

    private static Rectangle pill(double x, double y, double width, double height, String styleClass) {
        Rectangle rectangle = new Rectangle(x, y, width, height);
        rectangle.setArcWidth(height);
        rectangle.setArcHeight(height);
        rectangle.getStyleClass().add(styleClass);
        return rectangle;
    }
}
