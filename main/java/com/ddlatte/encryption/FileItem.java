package com.ddlatte.encryption;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import java.io.File;

/**
 * Model class for file items with dynamic progress tracking.
 */
public class FileItem {
    private final StringProperty name;
    private final StringProperty size;
    private final StringProperty status;
    private final DoubleProperty progress;

    public FileItem(File file) {
        this.name = new SimpleStringProperty(file.getName());
        this.size = new SimpleStringProperty(Utils.formatFileSize(file.length()));
        this.status = new SimpleStringProperty(file.getName().endsWith(".lock") ? "암호화됨" : "");
        this.progress = new SimpleDoubleProperty(0.0);
    }

    public StringProperty nameProperty() {
        return name;
    }

    public StringProperty typeProperty() {
        return new SimpleStringProperty(getFileType(new File(name.get())));
    }

    public StringProperty sizeProperty() {
        return size;
    }

    public StringProperty statusProperty() {
        return status;
    }

    public DoubleProperty progressProperty() {
        return progress;
    }

    public String getName() {
        return name.get();
    }

    public void setStatus(String status) {
        this.status.set(status);
    }

    public void setProgress(double progress) {
        this.progress.set(progress);
    }

    private String getFileType(File file) {
        return file.isDirectory() ? "폴더" : Utils.getFileExtension(file);
    }
}