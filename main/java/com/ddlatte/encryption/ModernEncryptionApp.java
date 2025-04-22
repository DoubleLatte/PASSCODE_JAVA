package com.ddlatte.encryption;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.concurrent.Task;

/**
 * Main application class with asynchronous FXML loading.
 */
public class ModernEncryptionApp extends Application {
    private static final String VERSION = "1.0.0";

    @Override
    public void start(Stage primaryStage) {
        Task<Parent> loadTask = new Task<>() {
            @Override
            protected Parent call() throws Exception {
                return FXMLLoader.load(getClass().getResource("/com/ddlatte/encryption/main.fxml"));
            }
        };
        loadTask.setOnSucceeded(e -> {
            Scene scene = new Scene(loadTask.getValue());
            scene.getStylesheets().add(getClass().getResource("/css/styles.css").toExternalForm());
            primaryStage.setScene(scene);
            primaryStage.setTitle("PASSCODE v" + getVersion());
            primaryStage.show();
        });
        loadTask.setOnFailed(e -> {
            e.getSource().getException().printStackTrace();
            Platform.exit();
        });
        new Thread(loadTask).start();
    }

    public static String getVersion() {
        return VERSION;
    }

    public static void main(String[] args) {
        launch(args);
    }
}