package com.ddlatte.encryption;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ComboBox;
import java.io.File;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Manages application settings using Java Preferences API with improved defaults and error handling.
 * 
 * Key improvements:
 * - Changed default chunk size to 1GB for better performance
 * - Enhanced error handling and validation
 * - Better memory management for large files
 * - Improved theme management
 * - Added settings validation and migration
 */
public class SettingsManager {
    private static final Logger LOGGER = Logger.getLogger(SettingsManager.class.getName());
    private static final String PREF_NODE = "/com/ddlatte/encryption";
    
    // Updated defaults for better performance
    private static final String DEFAULT_CHUNK_SIZE = "1 GB"; // Changed from 64 MB to 1 GB
    private static final String DEFAULT_DIRECTORY = System.getProperty("user.home");
    private static final String DEFAULT_KEY_PATH = System.getProperty("user.home");
    private static final String DEFAULT_THEME = "light";
    private static final String SETTINGS_VERSION = "2.0"; // Version for settings migration
    
    // Settings keys
    private static final String KEY_CHUNK_SIZE = "chunkSize";
    private static final String KEY_THEME = "theme";
    private static final String KEY_LAST_DIRECTORY = "lastDirectory";
    private static final String KEY_LAST_KEY_PATH = "lastKeyPath";
    private static final String KEY_SETTINGS_VERSION = "settingsVersion";
    private static final String KEY_WINDOW_WIDTH = "windowWidth";
    private static final String KEY_WINDOW_HEIGHT = "windowHeight";
    private static final String KEY_WINDOW_X = "windowX";
    private static final String KEY_WINDOW_Y = "windowY";
    private static final String KEY_WINDOW_MAXIMIZED = "windowMaximized";
    
    // Default window settings
    private static final double DEFAULT_WINDOW_WIDTH = 1000;
    private static final double DEFAULT_WINDOW_HEIGHT = 700;

    private final Preferences prefs;
    private boolean isDarkMode = false;
    private String lastKeyPath = DEFAULT_KEY_PATH;
    private boolean settingsLoaded = false;

    public SettingsManager() {
        prefs = Preferences.userRoot().node(PREF_NODE);
        migrateSettingsIfNeeded();
    }

    /**
     * Migrate settings from older versions if necessary
     */
    private void migrateSettingsIfNeeded() {
        String currentVersion = prefs.get(KEY_SETTINGS_VERSION, "1.0");
        
        if (!"2.0".equals(currentVersion)) {
            LOGGER.info("Migrating settings from version " + currentVersion + " to 2.0");
            
            try {
                // Migration logic for chunk size
                String oldChunkSize = prefs.get(KEY_CHUNK_SIZE, "64 MB");
                if ("64 MB".equals(oldChunkSize) || "128 MB".equals(oldChunkSize)) {
                    // Upgrade to 1GB for better performance
                    prefs.put(KEY_CHUNK_SIZE, DEFAULT_CHUNK_SIZE);
                    LOGGER.info("Upgraded chunk size from " + oldChunkSize + " to " + DEFAULT_CHUNK_SIZE);
                }
                
                // Set new version
                prefs.put(KEY_SETTINGS_VERSION, SETTINGS_VERSION);
                prefs.flush();
                
            } catch (BackingStoreException e) {
                LOGGER.log(Level.WARNING, "Failed to migrate settings", e);
            }
        }
    }

    /**
     * Save all application settings
     */
    public void saveSettings(String chunkSize, File currentDirectory) {
        try {
            // Validate and save chunk size
            String validatedChunkSize = validateChunkSize(chunkSize);
            prefs.put(KEY_CHUNK_SIZE, validatedChunkSize);
            
            // Save theme
            prefs.put(KEY_THEME, isDarkMode ? "dark" : "light");
            
            // Save directory with validation
            if (currentDirectory != null && currentDirectory.exists() && currentDirectory.isDirectory()) {
                prefs.put(KEY_LAST_DIRECTORY, currentDirectory.getAbsolutePath());
            }
            
            // Save key path with validation
            if (lastKeyPath != null && new File(lastKeyPath).exists()) {
                prefs.put(KEY_LAST_KEY_PATH, lastKeyPath);
            }
            
            // Save version
            prefs.put(KEY_SETTINGS_VERSION, SETTINGS_VERSION);
            
            // Force write to backing store
            prefs.flush();
            LOGGER.fine("Settings saved successfully");
            
        } catch (BackingStoreException e) {
            LOGGER.log(Level.WARNING, "Failed to save settings to backing store", e);
            showAlert(Alert.AlertType.WARNING, "설정 저장 실패", 
                "설정을 저장하지 못했습니다. 다음 실행 시 기본값이 사용됩니다.");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error saving settings", e);
            showAlert(Alert.AlertType.ERROR, "설정 오류", "설정 저장 중 예상치 못한 오류가 발생했습니다.");
        }
    }

    /**
     * Load all application settings
     */
    public void loadSettings(ComboBox<String> chunkSizeCombo, Consumer<File> directorySetter, Scene scene) {
        try {
            // Load chunk size with validation
            String chunkSize = prefs.get(KEY_CHUNK_SIZE, DEFAULT_CHUNK_SIZE);
            String validatedChunkSize = validateChunkSize(chunkSize);
            chunkSizeCombo.setValue(validatedChunkSize);
            
            // Load last directory with validation
            String lastDir = prefs.get(KEY_LAST_DIRECTORY, DEFAULT_DIRECTORY);
            File directory = new File(lastDir);
            if (!directory.exists() || !directory.isDirectory()) {
                LOGGER.warning("Last directory does not exist: " + lastDir + ", using default");
                directory = new File(DEFAULT_DIRECTORY);
            }
            directorySetter.accept(directory);
            
            // Load theme
            String theme = prefs.get(KEY_THEME, DEFAULT_THEME);
            isDarkMode = "dark".equals(theme);
            
            // Load key path with validation
            lastKeyPath = prefs.get(KEY_LAST_KEY_PATH, DEFAULT_KEY_PATH);
            File keyPathDir = new File(lastKeyPath);
            if (!keyPathDir.exists()) {
                LOGGER.warning("Last key path does not exist: " + lastKeyPath + ", using default");
                lastKeyPath = DEFAULT_KEY_PATH;
            }
            
            // Apply theme
            if (isDarkMode && scene != null) {
                Platform.runLater(() -> {
                    if (!scene.getRoot().getStyleClass().contains("dark-mode")) {
                        scene.getRoot().getStyleClass().add("dark-mode");
                    }
                });
            }
            
            settingsLoaded = true;
            LOGGER.fine("Settings loaded successfully");
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to load settings, using defaults", e);
            resetToDefaults(chunkSizeCombo, directorySetter, scene);
            showAlert(Alert.AlertType.WARNING, "설정 로드 실패", 
                "저장된 설정을 불러오지 못해 기본 설정을 사용합니다.");
        }
    }

    /**
     * Save window position and size settings
     */
    public void saveWindowSettings(double x, double y, double width, double height, boolean maximized) {
        try {
            prefs.putDouble(KEY_WINDOW_X, x);
            prefs.putDouble(KEY_WINDOW_Y, y);
            prefs.putDouble(KEY_WINDOW_WIDTH, width);
            prefs.putDouble(KEY_WINDOW_HEIGHT, height);
            prefs.putBoolean(KEY_WINDOW_MAXIMIZED, maximized);
            prefs.flush();
            LOGGER.fine("Window settings saved");
        } catch (BackingStoreException e) {
            LOGGER.log(Level.WARNING, "Failed to save window settings", e);
        }
    }

    /**
     * Load window position and size settings
     */
    public WindowSettings loadWindowSettings() {
        try {
            double x = prefs.getDouble(KEY_WINDOW_X, -1);
            double y = prefs.getDouble(KEY_WINDOW_Y, -1);
            double width = prefs.getDouble(KEY_WINDOW_WIDTH, DEFAULT_WINDOW_WIDTH);
            double height = prefs.getDouble(KEY_WINDOW_HEIGHT, DEFAULT_WINDOW_HEIGHT);
            boolean maximized = prefs.getBoolean(KEY_WINDOW_MAXIMIZED, false);
            
            return new WindowSettings(x, y, width, height, maximized);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to load window settings, using defaults", e);
            return new WindowSettings(-1, -1, DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT, false);
        }
    }

    /**
     * Toggle theme and return new state
     */
    public boolean toggleTheme(Scene scene) {
        isDarkMode = !isDarkMode;
        
        if (scene != null) {
            Platform.runLater(() -> {
                if (isDarkMode) {
                    if (!scene.getRoot().getStyleClass().contains("dark-mode")) {
                        scene.getRoot().getStyleClass().add("dark-mode");
                    }
                } else {
                    scene.getRoot().getStyleClass().remove("dark-mode");
                }
            });
        }
        
        LOGGER.fine("Theme toggled to: " + (isDarkMode ? "dark" : "light"));
        return isDarkMode;
    }

    /**
     * Validate chunk size and return corrected value if necessary
     */
    private String validateChunkSize(String chunkSize) {
        if (chunkSize == null || chunkSize.trim().isEmpty()) {
            LOGGER.warning("Empty chunk size, using default");
            return DEFAULT_CHUNK_SIZE;
        }
        
        // List of valid chunk sizes
        String[] validSizes = {"1 MB", "16 MB", "32 MB", "64 MB", "128 MB", "256 MB", "512 MB", "1 GB"};
        
        for (String validSize : validSizes) {
            if (validSize.equals(chunkSize.trim())) {
                return chunkSize.trim();
            }
        }
        
        LOGGER.warning("Invalid chunk size: " + chunkSize + ", using default");
        return DEFAULT_CHUNK_SIZE;
    }

    /**
     * Reset all settings to defaults
     */
    private void resetToDefaults(ComboBox<String> chunkSizeCombo, Consumer<File> directorySetter, Scene scene) {
        try {
            chunkSizeCombo.setValue(DEFAULT_CHUNK_SIZE);
            directorySetter.accept(new File(DEFAULT_DIRECTORY));
            lastKeyPath = DEFAULT_KEY_PATH;
            isDarkMode = false;
            
            if (scene != null) {
                Platform.runLater(() -> scene.getRoot().getStyleClass().remove("dark-mode"));
            }
            
            LOGGER.info("Settings reset to defaults");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to reset settings to defaults", e);
        }
    }

    /**
     * Clear all saved settings (for troubleshooting)
     */
    public void clearAllSettings() {
        try {
            prefs.clear();
            prefs.flush();
            LOGGER.info("All settings cleared");
        } catch (BackingStoreException e) {
            LOGGER.log(Level.SEVERE, "Failed to clear settings", e);
        }
    }

    /**
     * Check if settings have been loaded
     */
    public boolean isSettingsLoaded() {
        return settingsLoaded;
    }

    // Getters and setters
    public String getLastKeyPath() {
        return lastKeyPath;
    }

    public void setLastKeyPath(String path) {
        if (path != null && new File(path).exists()) {
            this.lastKeyPath = path;
        } else {
            LOGGER.warning("Invalid key path: " + path);
        }
    }

    public boolean isDarkMode() {
        return isDarkMode;
    }

    public String getCurrentChunkSize() {
        return prefs.get(KEY_CHUNK_SIZE, DEFAULT_CHUNK_SIZE);
    }

    /**
     * Get optimal chunk size based on available memory
     */
    public String getOptimalChunkSize() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long availableMemory = maxMemory / (1024 * 1024); // Convert to MB
        
        // Recommend chunk size based on available memory
        if (availableMemory > 4096) { // > 4GB
            return "1 GB";
        } else if (availableMemory > 2048) { // > 2GB
            return "512 MB";
        } else if (availableMemory > 1024) { // > 1GB
            return "256 MB";
        } else {
            return "128 MB";
        }
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.showAndWait();
        });
    }

    /**
     * Window settings container class
     */
    public static class WindowSettings {
        public final double x;
        public final double y; 
        public final double width;
        public final double height;
        public final boolean maximized;
        
        public WindowSettings(double x, double y, double width, double height, boolean maximized) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.maximized = maximized;
        }
        
        public boolean hasValidPosition() {
            return x >= 0 && y >= 0;
        }
        
        public boolean hasValidSize() {
            return width > 0 && height > 0;
        }
    }
}