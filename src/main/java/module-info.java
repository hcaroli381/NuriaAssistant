module com.example.nuriaassistant {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.media;
    requires java.desktop;
    requires java.logging;
    requires java.net.http;
    requires jdk.httpserver;
    requires se.michaelthelin.spotify;
    requires org.apache.httpcomponents.core5.httpcore5;
    requires com.google.zxing;


    opens com.example.nuriaassistant to javafx.fxml;
    exports com.example.nuriaassistant;
    exports com.example.nuriaassistant.models;
    exports com.example.nuriaassistant.spotify;
    exports com.example.nuriaassistant.services;
    exports com.example.nuriaassistant.config;
}