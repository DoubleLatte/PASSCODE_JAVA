package com.ddlatte.encryption;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import java.io.File;

/**
 * Represents a file item in the UI with cached size formatting.
 */
public class FileItem {
    private final File file;
    private final StringProperty nameProperty = new SimpleStringProperty();
    private final StringProperty typeProperty = new SimpleStringProperty();
    private final StringProperty sizeProperty = new SimpleStringProperty();
    private final StringProperty statusProperty = new SimpleStringProperty();
    private final DoubleProperty progressProperty = new SimpleDoubleProperty();
    private String cachedSize;

    public FileItem(File file) {
        this.file = file;
        this.nameProperty.set(file.getName());
        this.typeProperty.set(file.isDirectory() ? "폴더" : Utils.getFileExtension(file));
        this.cachedSize = Utils.formatFileSize(file.length());
        this.sizeProperty.set(cachedSize);
        this.statusProperty.set("대기 중");
        this.progressProperty.set(0);
    }

    public String getName() {
        return nameProperty.get();
    }

    public StringProperty nameProperty() {
        return nameProperty;
    }

    public StringProperty typeProperty() {
        return typeProperty;
    }

    public StringProperty sizeProperty() {
        return sizeProperty;
    }

    public StringProperty statusProperty() {
        return statusProperty;
    }

    public DoubleProperty progressProperty() {
        return progressProperty;
    }

    public void setProgress(double progress) {
        progressProperty.set(progress);
    }

    public void setStatus(String status) {
        statusProperty.set(status);
    }
}