package com.ddlatte.encryption;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Main application class for PASSCODE with optimized initialization and shutdown.
 */
public class ModernEncryptionApp extends Application {
    private static final Logger LOGGER = Logger.getLogger(ModernEncryptionApp.class.getName());
    private static final String VERSION = "1.0.0";
    private static final String FXML_PATH = "/fxml/MainView.fxml";
    private static final String CSS_PATH = "/css/modern.css";
    private static final double MIN_WIDTH = 800.0;
    private static final double MIN_HEIGHT = 600.0;

    private ModernEncryptionController controller;

    @Override
    public void start(Stage primaryStage) {
        try {
            LOGGER.info("Initializing application...");
            FXMLLoader loader = new FXMLLoader(getClass().getResource(FXML_PATH));
            Scene scene = new Scene(loader.load());
            scene.getStylesheets().add(getClass().getResource(CSS_PATH).toExternalForm());
            controller = loader.getController();

            primaryStage.setScene(scene);
            primaryStage.setTitle("PASSCODE v" + VERSION);
            primaryStage.setMinWidth(MIN_WIDTH);
            primaryStage.setMinHeight(MIN_HEIGHT);
            primaryStage.setOnCloseRequest(event -> shutdown());
            primaryStage.show();
            LOGGER.info("Application started successfully");
        } catch (IOException e) {
            LOGGER.severe("Initialization failed: " + e.getMessage());
            showErrorAndExit("애플리케이션 초기화 실패: " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        shutdown();
    }

    private void shutdown() {
        if (controller != null) {
            controller.shutdown();
            LOGGER.info("Controller shutdown completed");
        }
        LOGGER.info("Application shutdown");
    }

    public static String getVersion() {
        return VERSION;
    }

    private void showErrorAndExit(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("초기화 오류");
            alert.setContentText(message);
            alert.showAndWait();
            Platform.exit();
        });
    }

    public static void main(String[] args) {
        launch(args);
    }
}