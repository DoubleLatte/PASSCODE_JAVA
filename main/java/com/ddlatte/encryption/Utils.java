package com.ddlatte.encryption;

import javafx.collections.ObservableList;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Utility methods for file operations with optimized ZIP and hash performance.
 */
public class Utils {
    public static String calculateFileHash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileChannel inChannel = new FileInputStream(file).getChannel()) {
            ByteBuffer buffer = ByteBuffer.allocate(65536); // 64KB
            while (inChannel.read(buffer) > 0) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }
        return Base64.getEncoder().encodeToString(digest.digest());
    }

    public static void zipFiles(ObservableList<FileItem> items, File zipFile, File currentDirectory) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            for (FileItem item : items) {
                File file = new File(currentDirectory, item.getName());
                addToZip(file, zos, "");
            }
        }
    }

    public static void addToZip(File file, ZipOutputStream zos, String parentPath) throws IOException {
        String zipEntryName = parentPath + file.getName();
        if (zipEntryName.length() > 65535) {
            throw new IllegalArgumentException("ZIP 엔트리 이름이 너무 길어요: " + zipEntryName);
        }
        if (file.isDirectory()) {
            zipEntryName += "/";
            zos.putNextEntry(new ZipEntry(zipEntryName));
            zos.closeEntry();
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    addToZip(child, zos, zipEntryName);
                }
            }
        } else {
            zos.putNextEntry(new ZipEntry(zipEntryName));
            try (FileChannel inChannel = new FileInputStream(file).getChannel()) {
                ByteBuffer buffer = ByteBuffer.allocate(65536); // 64KB
                while (inChannel.read(buffer) > 0) {
                    buffer.flip();
                    zos.write(buffer.array(), 0, buffer.limit());
                    buffer.clear();
                }
            }
            zos.closeEntry();
        }
    }

    public static int parseChunkSize(String sizeStr) {
        try {
            String[] parts = sizeStr.split(" ");
            int size = Integer.parseInt(parts[0]);
            if (parts[1].equals("GB")) size *= 1024;
            return size * 1024 * 1024;
        } catch (Exception e) {
            return 1024 * 1024 * 1024; // 기본값 1GB
        }
    }

    public static String getFileExtension(File file) {
        String name = file.getName();
        int lastIndex = name.lastIndexOf('.');
        return lastIndex > 0 ? name.substring(lastIndex + 1) : "";
    }

    public static String generateUniqueOutputPath(String basePath) {
        File file = new File(basePath);
        if (!file.exists()) return basePath;
        int counter = 1;
        String newPath;
        do {
            newPath = basePath + "-" + counter++;
            file = new File(newPath);
            if (counter > 100) {
                throw new RuntimeException("너무 많은 파일 이름 충돌");
            }
        } while (file.exists());
        return newPath;
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}