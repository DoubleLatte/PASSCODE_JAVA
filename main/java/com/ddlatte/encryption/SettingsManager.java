package com.ddlatte.encryption;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ComboBox;
import java.io.File;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

/**
 * Manages application settings using Java Preferences API with 1GB default chunk size.
 */
public class SettingsManager {
    private static final Logger LOGGER = Logger.getLogger(SettingsManager.class.getName());
    private static final String PREF_NODE = "/com/ddlatte/encryption";
    private static final String DEFAULT_CHUNK_SIZE = "64 MB";
    private static final String DEFAULT_DIRECTORY = System.getProperty("user.home");
    private static final String DEFAULT_KEY_PATH = System.getProperty("user.home");
    private static final String DEFAULT_THEME = "light";

    private final Preferences prefs;
    private boolean isDarkMode = false;
    private String lastKeyPath = DEFAULT_KEY_PATH;

    public SettingsManager() {
        prefs = Preferences.userRoot().node(PREF_NODE);
    }

    public void saveSettings(String chunkSize, File currentDirectory) {
        try {
            prefs.put("chunkSize", chunkSize != null ? chunkSize : DEFAULT_CHUNK_SIZE);
            prefs.put("theme", isDarkMode ? "dark" : "light");
            if (currentDirectory != null) {
                prefs.put("lastDirectory", currentDirectory.getAbsolutePath());
            }
            prefs.put("lastKeyPath", lastKeyPath);
            prefs.flush();
            LOGGER.info("Settings saved successfully");
        } catch (Exception e) {
            LOGGER.warning("Failed to save settings: " + e.getMessage());
            showAlert(Alert.AlertType.WARNING, "설정 저장 실패", "설정을 저장하지 못했습니다. 기본값이 사용됩니다.");
        }
    }

    public void loadSettings(ComboBox<String> chunkSizeCombo, Consumer<File> directorySetter, Scene scene) {
        try {
            chunkSizeCombo.setValue(prefs.get("chunkSize", DEFAULT_CHUNK_SIZE));
            String lastDir = prefs.get("lastDirectory", DEFAULT_DIRECTORY);
            directorySetter.accept(new File(lastDir));
            isDarkMode = "dark".equals(prefs.get("theme", DEFAULT_THEME));
            lastKeyPath = prefs.get("lastKeyPath", DEFAULT_KEY_PATH);
            if (isDarkMode) {
                scene.getRoot().getStyleClass().add("dark-mode");
            }
            LOGGER.info("Settings loaded successfully");
        } catch (Exception e) {
            LOGGER.warning("Failed to load settings: " + e.getMessage());
            resetToDefaults(chunkSizeCombo, directorySetter);
            showAlert(Alert.AlertType.WARNING, "설정 로드 실패", "기본 설정을 로드했습니다.");
        }
    }

    public boolean toggleTheme(Scene scene) {
        isDarkMode = !isDarkMode;
        if (isDarkMode) {
            scene.getRoot().getStyleClass().add("dark-mode");
        } else {
            scene.getRoot().getStyleClass().remove("dark-mode");
        }
        return isDarkMode;
    }

    public String getLastKeyPath() {
        return lastKeyPath;
    }

    public void setLastKeyPath(String path) {
        this.lastKeyPath = path;
    }

    private void resetToDefaults(ComboBox<String> chunkSizeCombo, Consumer<File> directorySetter) {
        chunkSizeCombo.setValue(DEFAULT_CHUNK_SIZE);
        directorySetter.accept(new File(DEFAULT_DIRECTORY));
        lastKeyPath = DEFAULT_KEY_PATH;
        isDarkMode = false;
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setContentText(content);
            alert.showAndWait();
        });
    }
}
