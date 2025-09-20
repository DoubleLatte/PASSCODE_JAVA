package com.ddlatte.encryption;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.ArrayList;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Manages file system operations with improved memory management and error handling.
 * 
 * Key improvements:
 * - Better memory monitoring and management
 * - Enhanced error handling with proper cleanup
 * - Optimized thread pool management
 * - Improved backup file handling with atomic operations
 */
public class FileSystemManager {
    private static final Logger LOGGER = Logger.getLogger(FileSystemManager.class.getName());
    private static final int CORE_POOL_SIZE = 2;
    private static final int MAX_POOL_SIZE = Math.max(4, Runtime.getRuntime().availableProcessors());
    private static final long THREAD_KEEP_ALIVE_TIME = 60L;
    private static final int MEMORY_CHECK_INTERVAL = 3; // seconds
    private static final double MEMORY_WARNING_THRESHOLD = 0.85; // 85% memory usage warning
    
    private final EncryptedFileSystem efs;
    private File currentDirectory;
    private ScheduledExecutorService memoryMonitorExecutor;
    private ThreadPoolExecutor mainExecutor;

    public FileSystemManager() {
        efs = new EncryptedFileSystem();
        initializeThreadPool();
    }

    private void initializeThreadPool() {
        mainExecutor = new ThreadPoolExecutor(
            CORE_POOL_SIZE, MAX_POOL_SIZE,
            THREAD_KEEP_ALIVE_TIME, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, "FileSystem-Worker");
                t.setDaemon(true);
                return t;
            }
        );
        mainExecutor.allowCoreThreadTimeOut(true);
    }

    public void setCurrentDirectory(File directory) {
        this.currentDirectory = directory;
    }

    public File getCurrentDirectory() {
        return currentDirectory;
    }

    public void generateKey(File keyFile, String password) throws Exception {
        efs.generateKey(keyFile.getPath(), password);
    }

    public void loadKey(File keyFile, String password) throws Exception {
        efs.loadKey(keyFile.getPath(), password);
    }

    public void updateFileList(ObservableList<FileItem> fileItems, Label itemCountLabel) {
        synchronized (fileItems) {
            fileItems.clear();
            if (currentDirectory != null && currentDirectory.exists()) {
                File[] files = currentDirectory.listFiles();
                if (files != null) {
                    // Sort by size (largest first) with null safety
                    Arrays.sort(files, (f1, f2) -> {
                        if (f1 == null && f2 == null) return 0;
                        if (f1 == null) return 1;
                        if (f2 == null) return -1;
                        return Long.compare(f2.length(), f1.length());
                    });
                    
                    for (File file : files) {
                        if (file != null) {
                            fileItems.add(new FileItem(file));
                        }
                    }
                    
                    Platform.runLater(() -> itemCountLabel.setText("항목 수: " + fileItems.size() + "개"));
                }
            }
        }
    }

    public void secureDeleteFiles(ObservableList<FileItem> selectedItems, ObservableList<FileItem> fileItems,
                                 TableView<FileItem> fileTable, Label itemCountLabel) {
        List<FileItem> itemsToDelete = new ArrayList<>(selectedItems);
        
        mainExecutor.submit(() -> {
            for (FileItem item : itemsToDelete) {
                File file = new File(currentDirectory, item.getName());
                try {
                    efs.secureDelete(file.getPath());
                    Platform.runLater(() -> {
                        synchronized (fileItems) {
                            fileItems.remove(item);
                            fileTable.refresh();
                            itemCountLabel.setText("항목 수: " + fileItems.size() + "개");
                        }
                    });
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Failed to delete file: " + file.getPath(), e);
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("삭제 실패");
                        alert.setContentText("파일 삭제 중 오류: " + e.getMessage());
                        alert.showAndWait();
                    });
                }
            }
        });
    }

    private File createBackupFile(File file) throws IOException {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "encryption_backups");
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new IOException("Failed to create backup directory: " + tempDir.getAbsolutePath());
        }
        
        // Create unique backup filename to avoid conflicts
        String backupName = file.getName() + "_" + System.currentTimeMillis() + ".backup";
        File backupFile = new File(tempDir, backupName);
        
        // Use atomic copy operation
        Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        
        return backupFile;
    }

    private void cleanupBackupFiles(File... backupFiles) {
        for (File backup : backupFiles) {
            if (backup != null && backup.exists()) {
                try {
                    Files.delete(backup.toPath());
                    LOGGER.fine("Cleaned up backup file: " + backup.getPath());
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to cleanup backup file: " + backup.getPath(), e);
                }
            }
        }
    }

    public Task<Void> createEncryptionTask(ObservableList<FileItem> selectedItems, String chunkSizeStr,
                                          ObservableList<FileItem> fileItems, TableView<FileItem> fileTable) {
        return new Task<>() {
            @Override
            protected Void call() throws Exception {
                List<FileItem> itemsToProcess = new ArrayList<>(selectedItems);
                int chunkSize = Utils.parseChunkSize(chunkSizeStr);
                long totalSize = itemsToProcess.stream()
                    .mapToLong(item -> new File(currentDirectory, item.getName()).length())
                    .sum();
                long[] processedSize = {0};

                // Check available memory before processing
                checkMemoryBeforeOperation(totalSize);

                if (itemsToProcess.size() == 1 && !new File(currentDirectory, itemsToProcess.get(0).getName()).isDirectory()) {
                    // Single file encryption
                    processSingleFileEncryption(itemsToProcess.get(0), chunkSize, totalSize, processedSize, fileItems, fileTable);
                } else {
                    // Multi-file encryption (zip approach)
                    processMultiFileEncryption(itemsToProcess, chunkSize, totalSize, processedSize, fileItems, fileTable);
                }

                updateProgress(1, 1);
                updateMessage("암호화 완료");
                return null;
            }
        };
    }

    private void processSingleFileEncryption(FileItem item, int chunkSize, long totalSize, long[] processedSize,
                                           ObservableList<FileItem> fileItems, TableView<FileItem> fileTable) throws Exception {
        File file = new File(currentDirectory, item.getName());
        File backupFile = null;
        File tempDecrypted = null;

        try {
            backupFile = createBackupFile(file);
            tempDecrypted = new File(currentDirectory, "temp_decrypt_" + System.currentTimeMillis() + "_" + item.getName());

            updateMessage("암호화 중: " + item.getName());
            
            String encryptedPath = efs.encryptFile(file.getPath(), chunkSize);
            
            synchronized (this) {
                processedSize[0] += file.length();
                updateProgress(processedSize[0], totalSize);
                Platform.runLater(() -> item.setProgress((double) processedSize[0] / totalSize));
            }

            // Verify integrity
            updateMessage("무결성 검증 중: " + item.getName());
            String decryptedPath = efs.decryptFile(encryptedPath, tempDecrypted.getPath());
            
            String originalHash = Utils.calculateFileHash(file);
            String decryptedHash = Utils.calculateFileHash(tempDecrypted);

            if (originalHash.equals(decryptedHash)) {
                // Success - cleanup and update UI
                Files.delete(tempDecrypted.toPath());
                efs.secureDelete(file.getPath());
                cleanupBackupFiles(backupFile);
                
                Platform.runLater(() -> {
                    synchronized (fileItems) {
                        item.setStatus("암호화됨");
                        fileItems.clear();
                        fileItems.add(new FileItem(new File(encryptedPath)));
                        fileTable.refresh();
                    }
                });
            } else {
                // Integrity check failed - restore backup
                restoreFromBackup(file, backupFile);
                cleanupBackupFiles(tempDecrypted);
                new File(encryptedPath).delete();
                throw new Exception("무결성 검증 실패: " + item.getName());
            }

        } catch (Exception e) {
            // Error handling - restore backup if available
            if (backupFile != null && backupFile.exists()) {
                try {
                    restoreFromBackup(file, backupFile);
                } catch (IOException restoreError) {
                    LOGGER.log(Level.SEVERE, "Failed to restore backup for: " + file.getPath(), restoreError);
                }
            }
            cleanupBackupFiles(backupFile, tempDecrypted);
            
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("암호화 실패");
                alert.setContentText("암호화 중 오류: " + e.getMessage());
                alert.showAndWait();
            });
            throw e;
        }
    }

    private void processMultiFileEncryption(List<FileItem> items, int chunkSize, long totalSize, long[] processedSize,
                                           ObservableList<FileItem> fileItems, TableView<FileItem> fileTable) throws Exception {
        File zipFile = new File(currentDirectory, "encrypted_bundle_" + System.currentTimeMillis() + ".zip");
        File tempDecryptedZip = null;

        try {
            updateMessage("파일 압축 중...");
            Utils.zipFiles(FXCollections.observableList(items), zipFile, currentDirectory);

            tempDecryptedZip = new File(currentDirectory, "temp_decrypt_" + System.currentTimeMillis() + ".zip");

            updateMessage("압축 파일 암호화 중...");
            String encryptedPath = efs.encryptFile(zipFile.getPath(), chunkSize);
            
            synchronized (this) {
                processedSize[0] += zipFile.length();
                updateProgress(processedSize[0], totalSize);
                for (FileItem item : items) {
                    Platform.runLater(() -> item.setProgress(1.0));
                }
            }

            // Verify integrity
            updateMessage("무결성 검증 중...");
            String decryptedPath = efs.decryptFile(encryptedPath, tempDecryptedZip.getPath());
            
            String originalHash = Utils.calculateFileHash(zipFile);
            String decryptedHash = Utils.calculateFileHash(tempDecryptedZip);

            if (originalHash.equals(decryptedHash)) {
                // Success - cleanup and update UI
                Files.delete(zipFile.toPath());
                Files.delete(tempDecryptedZip.toPath());
                
                Platform.runLater(() -> {
                    synchronized (fileItems) {
                        fileItems.clear();
                        fileItems.add(new FileItem(new File(encryptedPath)));
                        fileTable.refresh();
                    }
                });
            } else {
                // Integrity check failed
                new File(encryptedPath).delete();
                throw new Exception("압축 파일 무결성 검증 실패");
            }

        } catch (Exception e) {
            cleanupBackupFiles(zipFile, tempDecryptedZip);
            
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("압축 암호화 실패");
                alert.setContentText("압축 파일 암호화 중 오류: " + e.getMessage());
                alert.showAndWait();
            });
            throw e;
        }
    }

    private void restoreFromBackup(File originalFile, File backupFile) throws IOException {
        if (backupFile != null && backupFile.exists()) {
            Files.copy(backupFile.toPath(), originalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Restored file from backup: " + originalFile.getPath());
        }
    }

    public Task<Void> createDecryptionTask(List<FileItem> encryptedFiles, ObservableList<FileItem> fileItems,
                                          TableView<FileItem> fileTable) {
        return new Task<>() {
            @Override
            protected Void call() throws Exception {
                List<FileItem> itemsToProcess = new ArrayList<>(encryptedFiles);
                long totalSize = itemsToProcess.stream()
                    .mapToLong(item -> new File(currentDirectory, item.getName()).length())
                    .sum();
                long[] processedSize = {0};

                // Check available memory before processing
                checkMemoryBeforeOperation(totalSize);

                for (FileItem item : itemsToProcess) {
                    if (isCancelled()) {
                        break;
                    }

                    File file = new File(currentDirectory, item.getName());
                    String outputPath = Utils.generateUniqueOutputPath(
                        file.getPath().substring(0, file.getPath().length() - 5)
                    );
                    long fileSize = file.length();

                    try {
                        updateMessage("복호화 중: " + item.getName());
                        String decryptedPath = efs.decryptFile(file.getPath(), outputPath);
                        
                        synchronized (this) {
                            processedSize[0] += fileSize;
                            updateProgress(processedSize[0], totalSize);
                            Platform.runLater(() -> item.setProgress((double) processedSize[0] / totalSize));
                        }

                        efs.deleteEncryptedFile(file.getPath());
                        
                        Platform.runLater(() -> {
                            synchronized (fileItems) {
                                item.setStatus("복호화 완료");
                                fileItems.clear();
                                fileItems.add(new FileItem(new File(decryptedPath)));
                                fileTable.refresh();
                            }
                        });

                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Failed to decrypt file: " + file.getPath(), e);
                        Platform.runLater(() -> {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.set