package com.ddlatte.encryption;

import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import java.io.*;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Manages application settings including theme, chunk size, and last directory.
 */
public class SettingsManager {
    private static final String SETTINGS_FILE = "settings.properties";
    private boolean isDarkMode = false;
    private String lastKeyPath = System.getProperty("user.home");

    public void saveSettings(String chunkSize, File currentDirectory) {
        Properties props = new Properties();
        props.setProperty("chunkSize", chunkSize);
        props.setProperty("theme", isDarkMode ? "dark" : "light");
        if (currentDirectory != null) {
            props.setProperty("lastDirectory", currentDirectory.getAbsolutePath());
        }
        props.setProperty("lastKeyPath", lastKeyPath);

        try (FileOutputStream fos = new FileOutputStream(SETTINGS_FILE)) {
            props.store(fos, "PASSCODE Settings");
        } catch (IOException e) {
            // 로깅 또는 사용자 알림 추가 가능
        }
    }

    public void loadSettings(ComboBox<String> chunkSizeCombo, Consumer<File> directorySetter, Scene scene) {
        Properties props = new Properties();
        File settingsFile = new File(SETTINGS_FILE);
        try (FileInputStream fis = new FileInputStream(settingsFile)) {
            props.load(fis);
            chunkSizeCombo.setValue(props.getProperty("chunkSize", "32 MB"));
            String lastDir = props.getProperty("lastDirectory", System.getProperty("user.home"));
            directorySetter.accept(new File(lastDir));
            isDarkMode = "dark".equals(props.getProperty("theme", "light"));
            lastKeyPath = props.getProperty("lastKeyPath", System.getProperty("user.home"));
            if (isDarkMode) {
                scene.getRoot().getStyleClass().add("dark-mode");
            }
        } catch (IOException e) {
            if (!settingsFile.exists()) {
                createDefaultSettings();
            }
            chunkSizeCombo.setValue("32 MB");
            directorySetter.accept(new File(System.getProperty("user.home")));
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

    private void createDefaultSettings() {
        Properties props = new Properties();
        props.setProperty("chunkSize", "32 MB");
        props.setProperty("lastDirectory", System.getProperty("user.home"));
        props.setProperty("theme", "light");
        props.setProperty("lastKeyPath", System.getProperty("user.home"));
        try (FileOutputStream fos = new FileOutputStream(SETTINGS_FILE)) {
            props.store(fos, "PASSCODE Default Settings");
        } catch (IOException e) {
            // 로깅 또는 사용자 알림 추가 가능
        }
    }
}