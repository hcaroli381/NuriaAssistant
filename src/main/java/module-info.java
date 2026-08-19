module com.example.nuriaassistant {
    requires javafx.controls;
    requires javafx.fxml;


    opens com.example.nuriaassistant to javafx.fxml;
    exports com.example.nuriaassistant;
}