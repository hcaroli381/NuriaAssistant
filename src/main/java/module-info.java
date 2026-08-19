module com.example.nuriaassistant {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.net.http;
    requires jdk.httpserver;
    requires se.michaelthelin.spotify;


    opens com.example.nuriaassistant to javafx.fxml;
    exports com.example.nuriaassistant;
}