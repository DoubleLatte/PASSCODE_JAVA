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

/**
 * Manages file system operations with optimized thread pooling and backup file handling.
 */
public class FileSystemManager {
    private final EncryptedFileSystem efs;
    private File currentDirectory;
    private ExecutorService memoryMonitorExecutor;

    public FileSystemManager() {
        efs = new EncryptedFileSystem();
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
                    Arrays.sort(files, (f1, f2) -> Long.compare(f2.length(), f1.length()));
                    for (File file : files) {
                        fileItems.add(new FileItem(file));
                    }
                    Platform.runLater(() -> itemCountLabel.setText("항목 수: " + files.length + "개"));
                }
            }
        }
    }

    public void secureDeleteFiles(ObservableList<FileItem> selectedItems, ObservableList<FileItem> fileItems,
                                 TableView<FileItem> fileTable, Label itemCountLabel) {
        for (FileItem item : selectedItems) {
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
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("삭제 실패");
                    alert.setContentText("파일 삭제 중 오류: " + e.getMessage());
                    alert.showAndWait();
                });
            }
        }
    }

    private File createBackupFile(File file) throws IOException {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "encryption_backups");
        tempDir.mkdirs();
        File backupFile = new File(tempDir, file.getName() + ".backup");
        Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return backupFile;
    }

    private void cleanupBackupFiles(File... backupFiles) {
        for (File backup : backupFiles) {
            if (backup.exists()) {
                try {
                    Files.delete(backup.toPath());
                } catch (IOException e) {
                    // 로깅만, 예외 무시
                }
            }
        }
    }

    public Task<Void> createEncryptionTask(ObservableList<FileItem> selectedItems, String chunkSizeStr,
                                          ObservableList<FileItem> fileItems, TableView<FileItem> fileTable) {
        return new Task<>() {
            @Override
            protected Void call() throws Exception {
                ThreadPoolExecutor executor = new ThreadPoolExecutor(
                    2, Math.max(4, Runtime.getRuntime().availableProcessors()),
                    60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>()
                );
                List<Future<?>> futures = new ArrayList<>();
                int chunkSize = Utils.parseChunkSize(chunkSizeStr);
                long totalSize = selectedItems.stream().map(item -> new File(currentDirectory, item.getName()).length()).reduce(0L, Long::sum);
                long[] processedSize = {0};

                if (selectedItems.size() == 1 && !new File(currentDirectory, selectedItems.get(0).getName()).isDirectory()) {
                    FileItem item = selectedItems.get(0);
                    File file = new File(currentDirectory, item.getName());
                    File backupFile = createBackupFile(file);
                    File tempDecrypted = new File(currentDirectory, "temp_" + item.getName());

                    try {
                        long fileSize = file.length();
                        Future<?> future = executor.submit(() -> {
                            try {
                                updateMessage("암호화 중: " + item.getName());
                                String encryptedPath = efs.encryptFile(file.getPath(), chunkSize);
                                synchronized (this) {
                                    processedSize[0] += fileSize;
                                    updateProgress(processedSize[0], totalSize);
                                    item.setProgress((double) processedSize[0] / totalSize);
                                }
                                String decryptedPath = efs.decryptFile(encryptedPath, tempDecrypted.getPath());
                                String originalHash = Utils.calculateFileHash(file);
                                String decryptedHash = Utils.calculateFileHash(tempDecrypted);

                                if (originalHash.equals(decryptedHash)) {
                                    efs.secureDelete(backupFile.getPath());
                                    efs.secureDelete(file.getPath());
                                    efs.secureDelete(tempDecrypted.getPath());
                                    Platform.runLater(() -> {
                                        synchronized (fileItems) {
                                            item.setStatus("암호화됨");
                                            fileItems.clear();
                                            fileItems.add(new FileItem(new File(encryptedPath)));
                                            fileTable.refresh();
                                        }
                                    });
                                } else {
                                    Files.copy(backupFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                                    efs.secureDelete(backupFile.getPath());
                                    efs.secureDelete(encryptedPath);
                                    efs.secureDelete(tempDecrypted.getPath());
                                    throw new Exception("무결성 검증 실패: " + item.getName());
                                }
                            } catch (Exception e) {
                                Platform.runLater(() -> {
                                    Alert alert = new Alert(Alert.AlertType.ERROR);
                                    alert.setTitle("암호화 실패");
                                    alert.setContentText("암호화 중 오류: " + e.getMessage());
                                    alert.showAndWait();
                                });
                            }
                        });
                        futures.add(future);
                    } finally {
                        if (tempDecrypted.exists()) {
                            efs.secureDelete(tempDecrypted.getPath());
                        }
                        cleanupBackupFiles(backupFile);
                    }
                } else {
                    File zipFile = new File(currentDirectory, "encrypted_bundle.zip");
                    File tempDecryptedZip = new File(currentDirectory, "temp_encrypted_bundle.zip");
                    File backupZip = createBackupFile(zipFile);

                    Utils.zipFiles(selectedItems, zipFile, currentDirectory);

                    Future<?> future = executor.submit(() -> {
                        try {
                            updateMessage("압축 파일 암호화 중...");
                            String encryptedPath = efs.encryptFile(zipFile.getPath(), chunkSize);
                            synchronized (this) {
                                processedSize[0] += zipFile.length();
                                updateProgress(processedSize[0], totalSize);
                                selectedItems.forEach(item -> item.setProgress(1.0));
                            }
                            String decryptedPath = efs.decryptFile(encryptedPath, tempDecryptedZip.getPath());
                            String originalHash = Utils.calculateFileHash(zipFile);
                            String decryptedHash = Utils.calculateFileHash(tempDecryptedZip);

                            if (originalHash.equals(decryptedHash)) {
                                efs.secureDelete(backupZip.getPath());
                                efs.secureDelete(zipFile.getPath());
                                efs.secureDelete(tempDecryptedZip.getPath());
                                Platform.runLater(() -> {
                                    synchronized (fileItems) {
                                        fileItems.clear();
                                        fileItems.add(new FileItem(new File(encryptedPath)));
                                        fileTable.refresh();
                                    }
                                });
                            } else {
                                Files.copy(backupZip.toPath(), zipFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                                efs.secureDelete(backupZip.getPath());
                                efs.secureDelete(encryptedPath);
                                efs.secureDelete(tempDecryptedZip.getPath());
                                throw new Exception("압축 파일 무결성 검증 실패");
                            }
                        } catch (Exception e) {
                            Platform.runLater(() -> {
                                Alert alert = new Alert(Alert.AlertType.ERROR);
                                alert.setTitle("압축 암호화 실패");
                                alert.setContentText("압축 파일 암호화 중 오류: " + e.getMessage());
                                alert.showAndWait();
                            });
                        }
                    });
                    futures.add(future);
                    cleanupBackupFiles(backupZip, tempDecryptedZip);
                }

                for (Future<?> future : futures) {
                    future.get();
                }
                executor.shutdown();
                executor.awaitTermination(10, TimeUnit.SECONDS);
                updateProgress(1, 1);
                updateMessage("암호화 완료");
                return null;
            }
        };
    }

    public Task<Void> createDecryptionTask(List<FileItem> encryptedFiles, ObservableList<FileItem> fileItems,
                                          TableView<FileItem> fileTable) {
        return new Task<>() {
            @Override
            protected Void call() throws Exception {
                ThreadPoolExecutor executor = new ThreadPoolExecutor(
                    2, Math.max(4, Runtime.getRuntime().availableProcessors()),
                    60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>()
                );
                List<Future<?>> futures = new ArrayList<>();
                int total = encryptedFiles.size();
                long totalSize = encryptedFiles.stream().map(item -> new File(currentDirectory, item.getName()).length()).reduce(0L, Long::sum);
                long[] processedSize = {0};

                for (int i = 0; i < total; i++) {
                    FileItem item = encryptedFiles.get(i);
                    File file = new File(currentDirectory, item.getName());
                    String outputPath = Utils.generateUniqueOutputPath(file.getPath().substring(0, file.getPath().length() - 5));
                    long fileSize = file.length();

                    Future<?> future = executor.submit(() -> {
                        try {
                            updateMessage("복호화 중: " + item.getName());
                            String decryptedPath = efs.decryptFile(file.getPath(), outputPath);
                            synchronized (this) {
                                processedSize[0] += fileSize;
                                updateProgress(processedSize[0], totalSize);
                                item.setProgress((double) processedSize[0] / totalSize);
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
                            Platform.runLater(() -> {
                                Alert alert = new Alert(Alert.AlertType.ERROR);
                                alert.setTitle("복호화 실패");
                                alert.setContentText("복호화 중 오류: " + e.getMessage());
                                alert.showAndWait();
                            });
                        }
                    });
                    futures.add(future);
                }

                for (Future<?> future : futures) {
                    future.get();
                }
                executor.shutdown();
                executor.awaitTermination(10, TimeUnit.SECONDS);
                updateProgress(1, 1);
                updateMessage("복호화 완료");
                return null;
            }
        };
    }

    public void startMemoryMonitoring(Label memoryLabel) {
        memoryMonitorExecutor = Executors.newSingleThreadScheduledExecutor();
        memoryMonitorExecutor.scheduleAtFixedRate(() -> {
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
            long maxMemory = runtime.maxMemory() / (1024 * 1024);
            String memoryInfo = String.format("사용 %d MB / 최대 %d MB", usedMemory, maxMemory);
            Platform.runLater(() -> memoryLabel.setText(memoryInfo));
        }, 0, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    public void shutdown() {
        if (memoryMonitorExecutor != null && !memoryMonitorExecutor.isShutdown()) {
            memoryMonitorExecutor.shutdownNow();
        }
    }
}