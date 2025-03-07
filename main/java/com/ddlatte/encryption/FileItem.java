package com.ddlatte.encryption;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import java.io.File;


class FileItem {
    private final StringProperty name = new SimpleStringProperty();
    private final StringProperty type = new SimpleStringProperty();
    private final StringProperty size = new SimpleStringProperty();
    private final StringProperty status = new SimpleStringProperty();

    public FileItem(File file) {
        this.name.set(file.getName());
        this.type.set(file.isDirectory() ? "folder" : getFileExtension(file));
        this.size.set(file.isDirectory() ? "" : formatSize(file.length()));
        this.status.set("준비");
    }

    public StringProperty nameProperty() { return name; }
    public StringProperty typeProperty() { return type; }
    public StringProperty sizeProperty() { return size; }
    public StringProperty statusProperty() { return status; }

    public String getName() { return name.get(); }
    public void setStatus(String status) { this.status.set(status); }

    public String getFileExtension(File file) {
        String name = file.getName();
        int lastDot = name.lastIndexOf('.');
        return (lastDot == -1) ? "" : name.substring(lastDot + 1);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}