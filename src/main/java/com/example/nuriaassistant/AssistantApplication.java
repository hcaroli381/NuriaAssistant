package com.example.nuriaassistant;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.io.IOException;

public class AssistantApplication extends Application {
    private AssistantController controller;

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(AssistantApplication.class.getResource("hello-view.fxml"));
        Parent root = fxmlLoader.load();
        
        // 1024x600 as requested for Raspberry Pi touchscreen
        Scene scene = new Scene(root, 1024, 600);
        scene.setFill(Color.web("#0a192f"));
        
        // Explicitly attach stylesheet to Scene
        String stylesheet = AssistantApplication.class.getResource("styles.css") != null
                ? AssistantApplication.class.getResource("styles.css").toExternalForm()
                : null;
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet);
        }

        controller = fxmlLoader.getController();

        stage.setTitle("Nuria Assistant");
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() throws Exception {
        if (controller != null) {
            controller.shutdown();
        }
        super.stop();
    }
}
