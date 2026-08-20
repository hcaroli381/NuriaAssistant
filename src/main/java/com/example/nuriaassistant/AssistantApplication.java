package com.example.nuriaassistant;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class AssistantApplication extends Application {
    private AssistantController controller;

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(AssistantApplication.class.getResource("hello-view.fxml"));
        // 1024x600 as requested for Raspberry Pi touchscreen
        Scene scene = new Scene(fxmlLoader.load(), 1024, 600);
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
