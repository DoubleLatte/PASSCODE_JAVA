package com.ddlatte.encryption;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.concurrent.Task;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.kordamp.ikonli.javafx.FontIcon;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.nio.file.AccessDeniedException;

public class ModernEncryptionController {
    @FXML private TableView<FileItem> fileTable;
    @FXML private ComboBox<String> chunkSizeCombo;
    @FXML private Label statusLabel;
    @FXML private Button encryptButton;
    @FXML private Button decryptButton;
    @FXML private Label memoryLabel;
    @FXML private Label itemCountLabel;
    @FXML private ProgressBar progressBar;
    @FXML private Label progressLabel;

    private EncryptedFileSystem efs;
    private File currentDirectory;
    private ObservableList<FileItem> fileItems;
    private ScheduledExecutorService executorService;
    private Task<Void> currentTask;
    private boolean isDarkMode = false;

    @FXML
    public void initialize() {
        efs = new EncryptedFileSystem();
        fileItems = FXCollections.synchronizedObservableList(FXCollections.observableArrayList());

        try {
            setupUI();
            Platform.runLater(this::setupTableColumns);
            setupChunkSizeCombo();
            setupMemoryMonitoring();
            fileTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            loadSettings();
        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "초기화 오류", "UI 로드 실패: " + e.getMessage());
            Platform.exit();
        }
    }

    private void setupUI() {
        fileTable.setItems(fileItems);
        try {
            encryptButton.setGraphic(new FontIcon("fas-lock"));
            decryptButton.setGraphic(new FontIcon("fas-unlock"));
        } catch (IllegalArgumentException e) {
            showAlert(Alert.AlertType.WARNING, "아이콘 오류", "아이콘 로드 실패: " + e.getMessage());
        }
        memoryLabel.setText("메모리: 초기화 중...");
        itemCountLabel.setText("항목 수: 0개");
        progressBar.setVisible(false);
        progressLabel.setVisible(false);
    }

    private void setupTableColumns() {
        TableColumn<FileItem, String> nameCol = new TableColumn<>("이름");
        nameCol.setCellValueFactory(data -> data.getValue().nameProperty());
        nameCol.prefWidthProperty().bind(fileTable.widthProperty().multiply(0.4));

        TableColumn<FileItem, String> typeCol = new TableColumn<>("유형");
        typeCol.setCellValueFactory(data -> data.getValue().typeProperty());
        typeCol.prefWidthProperty().bind(fileTable.widthProperty().multiply(0.2));

        TableColumn<FileItem, String> sizeCol = new TableColumn<>("크기");
        sizeCol.setCellValueFactory(data -> data.getValue().sizeProperty());
        sizeCol.prefWidthProperty().bind(fileTable.widthProperty().multiply(0.2));

        TableColumn<FileItem, String> statusCol = new TableColumn<>("상태");
        statusCol.setCellValueFactory(data -> data.getValue().statusProperty());
        statusCol.prefWidthProperty().bind(fileTable.widthProperty().multiply(0.2));

        fileTable.getColumns().setAll(nameCol, typeCol, sizeCol, statusCol);
    }

    private void setupChunkSizeCombo() {
        chunkSizeCombo.getItems().addAll("1 MB", "16 MB", "32 MB", "64 MB", "128 MB", "256 MB", "512 MB", "1 GB");
        chunkSizeCombo.setValue("32 MB");
    }

    private void setupMemoryMonitoring() {
        executorService = Executors.newSingleThreadScheduledExecutor();
        executorService.scheduleAtFixedRate(() -> {
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory() / (1024 * 1024);
            long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
            long freeMemory = runtime.freeMemory() / (1024 * 1024);
            String memoryInfo = String.format("사용 %d MB / 최대 %d MB", usedMemory, maxMemory);
            Platform.runLater(() -> memoryLabel.setText(memoryInfo));
        }, 0, 5, TimeUnit.SECONDS);
    }

    @FXML
    private void toggleTheme() {
        isDarkMode = !isDarkMode;
        Scene scene = fileTable.getScene();
        if (isDarkMode) {
            scene.getRoot().getStyleClass().add("dark-mode");
        } else {
            scene.getRoot().getStyleClass().remove("dark-mode");
        }
        saveSettings();
    }

    public void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
            if (!executorService.isShutdown()) {
                showAlert(Alert.AlertType.WARNING, "종료 경고", "메모리 모니터링 종료 실패");
            }
        }
    }

    @FXML
    private void onOpenFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("폴더 선택");
        try {
            File directory = chooser.showDialog(null);
            if (directory != null) {
                currentDirectory = directory;
                updateFileList();
            }
        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "폴더 선택 오류", "디렉토리 선택 실패: " + e.getMessage());
        }
    }

    @FXML
    private void refreshFileList() {
        if (currentDirectory != null) {
            updateFileList();
        } else {
            showAlert(Alert.AlertType.WARNING, "경고", "먼저 폴더를 선택해주세요.");
        }
    }

    @FXML
    private void onCreateKey() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("새 키 생성");
        dialog.setHeaderText("새 키를 위한 비밀번호 입력");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        PasswordField password = new PasswordField();
        PasswordField confirm = new PasswordField();

        grid.add(new Label("비밀번호:"), 0, 0);
        grid.add(password, 1, 0);
        grid.add(new Label("확인:"), 0, 1);
        grid.add(confirm, 1, 1);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Stage dialogStage = (Stage) dialog.getDialogPane().getScene().getWindow();
        dialogStage.getIcons().add(new javafx.scene.image.Image(getClass().getResourceAsStream("/icons/favicon.png")));

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == ButtonType.OK) {
                if (password.getText().equals(confirm.getText())) {
                    return password.getText();
                } else {
                    showAlert(Alert.AlertType.ERROR, "오류", "비밀번호가 일치하지 않습니다");
                    return null;
                }
            }
            return null;
        });

        Optional<String> result = dialog.showAndWait();
        if (result.isPresent()) {
            String pwd = result.get();
            FileChooser keyChooser = new FileChooser();
            keyChooser.setTitle("키 파일 저장");
            keyChooser.setInitialFileName("mykey.key");
            keyChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Encryption Key (*.key)", "*.key"));
            File keyFile = keyChooser.showSaveDialog(fileTable.getScene().getWindow());
            if (keyFile != null) {
                try {
                    efs.generateKey(keyFile.getPath(), pwd);
                    showAlert(Alert.AlertType.INFORMATION, "성공", "키가 성공적으로 생성되었습니다");
                    statusLabel.setText("키 로드됨: " + keyFile.getName());
                } catch (Exception e) {
                    showAlert(Alert.AlertType.ERROR, "오류", e.getMessage());
                }
            } else {
                showAlert(Alert.AlertType.INFORMATION, "취소", "키 파일 저장이 취소되었습니다");
            }
        } else {
            showAlert(Alert.AlertType.INFORMATION, "취소", "키 생성이 취소되었습니다");
        }
    }

    @FXML
    private void onLoadKey() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("키 파일 선택");
        File keyFile = chooser.showOpenDialog(null);

        if (keyFile != null) {
            Dialog<String> dialog = new Dialog<>();
            dialog.setTitle("키 로드");
            dialog.setHeaderText("선택한 키 파일을 위한 비밀번호 입력");

            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(10);

            PasswordField password = new PasswordField();
            grid.add(new Label("비밀번호:"), 0, 0);
            grid.add(password, 1, 0);

            dialog.getDialogPane().setContent(grid);
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

            Stage dialogStage = (Stage) dialog.getDialogPane().getScene().getWindow();
            dialogStage.getIcons().add(new javafx.scene.image.Image(getClass().getResourceAsStream("/icons/favicon.png")));

            dialog.setResultConverter(dialogButton -> dialogButton == ButtonType.OK ? password.getText() : null);

            Optional<String> result = dialog.showAndWait();
            if (result.isPresent()) {
                try {
                    efs.loadKey(keyFile.getPath(), result.get());
                    showAlert(Alert.AlertType.INFORMATION, "성공", "키가 성공적으로 로드되었습니다");
                    statusLabel.setText("키 로드됨: " + keyFile.getName());
                } catch (Exception e) {
                    showAlert(Alert.AlertType.ERROR, "오류", e.getMessage());
                }
            }
        }
    }

    @FXML
    private void onEncrypt() {
        ObservableList<FileItem> selectedItems = fileTable.getSelectionModel().getSelectedItems();
        if (selectedItems.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "경고", "선택된 파일이 없습니다");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("암호화 확인");
        confirm.setHeaderText("선택한 항목을 암호화하시겠습니까?");
        Stage alertStage = (Stage) confirm.getDialogPane().getScene().getWindow();
        alertStage.getIcons().add(new javafx.scene.image.Image(getClass().getResourceAsStream("/icons/favicon.png")));

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) return;

        ExecutorService executor;
        try {
            executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "스레드 오류", "작업 스레드 생성 실패: " + e.getMessage());
            return;
        }
        List<Future<?>> futures = new ArrayList<>();

        currentTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                Platform.runLater(() -> {
                    progressBar.setVisible(true);
                    progressLabel.setVisible(true);
                });
                int total = selectedItems.size();
                if (total == 1 && !new File(currentDirectory, selectedItems.get(0).getName()).isDirectory()) {
                    final FileItem item = selectedItems.get(0);
                    final File file = new File(currentDirectory, item.getName());
                    final File backupFile = new File(file.getPath() + ".backup");
                    File tempDecrypted = null;
                    try {
                        Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        showAlert(Alert.AlertType.ERROR, "백업 오류", "백업 생성 실패: " + e.getMessage());
                        return null;
                    }

                    try {
                        final File tempDecryptedFinal = new File(currentDirectory, "temp_" + item.getName());
                        tempDecrypted = tempDecryptedFinal;
                        Future<?> future = executor.submit(() -> {
                            try {
                                updateMessage("암호화 중: " + item.getName());
                                int chunkSize;
                                try {
                                    chunkSize = parseChunkSize(chunkSizeCombo.getValue());
                                } catch (NumberFormatException e) {
                                    chunkSize = 32 * 1024 * 1024;
                                    Platform.runLater(() -> showAlert(Alert.AlertType.WARNING, "청크 오류", "청크 크기 형식이 잘못됨, 기본값 32MB 사용"));
                                }
                                String encryptedPath = efs.encryptFile(file.getPath(), chunkSize);
                                String decryptedPath = efs.decryptFile(encryptedPath, tempDecryptedFinal.getPath());
                                String originalHash = calculateFileHash(file);
                                String decryptedHash = calculateFileHash(tempDecryptedFinal);

                                if (originalHash.equals(decryptedHash)) {
                                    efs.secureDelete(backupFile.getPath());
                                    efs.secureDelete(file.getPath());
                                    efs.secureDelete(tempDecryptedFinal.getPath());
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
                                    efs.secureDelete(tempDecryptedFinal.getPath());
                                    throw new Exception("무결성 검증 실패: " + item.getName());
                                }
                            } catch (AccessDeniedException e) {
                                Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "권한 오류", item.getName() + "에 대한 접근 권한이 없습니다"));
                            } catch (Exception e) {
                                Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "오류", e.getMessage()));
                            }
                        });
                        futures.add(future);
                    } finally {
                        if (tempDecrypted != null && tempDecrypted.exists()) {
                            efs.secureDelete(tempDecrypted.getPath());
                        }
                    }
                } else {
                    final File zipFile = new File(currentDirectory, "encrypted_bundle.zip");
                    final File tempDecryptedZip = new File(currentDirectory, "temp_encrypted_bundle.zip");
                    final File backupZip = new File(zipFile.getPath() + ".backup");

                    try {
                        zipFiles(selectedItems, zipFile);
                        Files.copy(zipFile.toPath(), backupZip.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } catch (AccessDeniedException e) {
                        Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "권한 오류", "압축 중 접근 권한이 없는 파일이 있습니다"));
                        return null;
                    } catch (IOException e) {
                        Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "백업 오류", "압축 파일 백업 실패: " + e.getMessage()));
                        return null;
                    }

                    Future<?> future = executor.submit(() -> {
                        try {
                            updateProgress(0.5, 1);
                            updateMessage("암호화 중: " + zipFile.getName());
                            int chunkSize;
                            try {
                                chunkSize = parseChunkSize(chunkSizeCombo.getValue());
                            } catch (NumberFormatException e) {
                                chunkSize = 32 * 1024 * 1024;
                                Platform.runLater(() -> showAlert(Alert.AlertType.WARNING, "청크 오류", "청크 크기 형식이 잘못됨, 기본값 32MB 사용"));
                            }
                            String encryptedPath = efs.encryptFile(zipFile.getPath(), chunkSize);
                            String decryptedPath = efs.decryptFile(encryptedPath, tempDecryptedZip.getPath());
                            String originalHash = calculateFileHash(zipFile);
                            String decryptedHash = calculateFileHash(tempDecryptedZip);

                            if (originalHash.equals(decryptedHash)) {
                                efs.secureDelete(backupZip.getPath());
                                efs.secureDelete(zipFile.getPath());
                                efs.secureDelete(tempDecryptedZip.getPath());
                                showAlert(Alert.AlertType.INFORMATION, "성공", "성공적으로 암호화 되었습니다");
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
                        } catch (AccessDeniedException e) {
                            Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "권한 오류", zipFile.getName() + "에 대한 접근 권한이 없습니다"));
                        } catch (Exception e) {
                            Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "오류", e.getMessage()));
                        }
                    });
                    futures.add(future);
                }

                for (Future<?> future : futures) {
                    future.get();
                }
                executor.shutdownNow();

                updateProgress(1, 1);
                updateMessage("암호화 완료");
                Platform.runLater(() -> {
                    progressBar.setVisible(false);
                    progressLabel.setVisible(false);
                });
                return null;
            }
        };

        progressBar.progressProperty().bind(currentTask.progressProperty());
        progressLabel.textProperty().bind(currentTask.messageProperty());
        new Thread(currentTask).start();
    }

    @FXML
    private void onDecrypt() {
        List<FileItem> encryptedFiles = fileTable.getSelectionModel().getSelectedItems().filtered(item ->
                item.getName().endsWith(".lock"));

        if (encryptedFiles.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "경고", "선택된 암호화 파일이 없습니다");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("복호화 확인");
        confirm.setHeaderText("선택한 파일을 복호화하시겠습니까?");
        Stage alertStage = (Stage) confirm.getDialogPane().getScene().getWindow();
        alertStage.getIcons().add(new javafx.scene.image.Image(getClass().getResourceAsStream("/icons/favicon.png")));

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) return;

        ExecutorService executor;
        try {
            executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "스레드 오류", "작업 스레드 생성 실패: " + e.getMessage());
            return;
        }
        List<Future<?>> futures = new ArrayList<>();

        currentTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                Platform.runLater(() -> {
                    progressBar.setVisible(true);
                    progressLabel.setVisible(true);
                });
                final int total = encryptedFiles.size();
                for (int i = 0; i < total; i++) {
                    final FileItem item = encryptedFiles.get(i);
                    final File file = new File(currentDirectory, item.getName());
                    final String outputPath = generateUniqueOutputPath(file.getPath().substring(0, file.getPath().length() - 5));
                    final int currentIndex = i;

                    Future<?> future = executor.submit(() -> {
                        try {
                            updateProgress(currentIndex, total);
                            updateMessage("복호화 중: " + item.getName());
                            String decryptedPath = efs.decryptFile(file.getPath(), outputPath);
                            efs.deleteEncryptedFile(file.getPath());
                            Platform.runLater(() -> {
                                synchronized (fileItems) {
                                    item.setStatus("복호화 완료");
                                    fileItems.clear();
                                    fileItems.add(new FileItem(new File(decryptedPath)));
                                    fileTable.refresh();
                                }
                            });
                        } catch (AccessDeniedException e) {
                            Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "권한 오류", item.getName() + "에 대한 접근 권한이 없습니다"));
                        } catch (Exception e) {
                            Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "복호화 오류", "복호화 실패: " + e.getMessage()));
                        }
                    });
                    futures.add(future);
                }

                for (Future<?> future : futures) {
                    future.get();
                }
                executor.shutdownNow();

                updateProgress(1, 1);
                updateMessage("복호화 완료");
                Platform.runLater(() -> {
                    progressBar.setVisible(false);
                    progressLabel.setVisible(false);
                });
                return null;
            }
        };

        progressBar.progressProperty().bind(currentTask.progressProperty());
        progressLabel.textProperty().bind(currentTask.messageProperty());
        new Thread(currentTask).start();
    }

    private void zipFiles(ObservableList<FileItem> items, File zipFile) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            for (FileItem item : items) {
                File file = new File(currentDirectory, item.getName());
                addToZip(file, zos, "");
            }
        } catch (AccessDeniedException e) {
            throw new AccessDeniedException("파일 접근 권한 부족: " + zipFile.getName());
        } catch (IOException e) {
            throw new IOException("압축 오류: " + e.getMessage());
        }
    }

    @FXML
    private void onExit() {
        saveSettings();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("종료 확인");
        confirm.setHeaderText("프로그램을 종료하시겠습니까?");
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            shutdown();
            Platform.exit();
        }
    }

    @FXML
    private void cancelTask() {
        if (currentTask != null && currentTask.isRunning()) {
            if (currentTask.cancel(true)) {
                Platform.runLater(() -> {
                    progressLabel.setText("작업 취소됨");
                    progressBar.setProgress(0);
                    progressBar.setVisible(false);
                    progressLabel.setVisible(false);
                });
            } else {
                showAlert(Alert.AlertType.WARNING, "취소 오류", "작업 취소에 실패했습니다");
            }
        }
    }

    private void updateFileList() {
        synchronized (fileItems) {
            fileItems.clear();
            if (currentDirectory != null && currentDirectory.exists()) {
                File[] files = currentDirectory.listFiles();
                if (files != null) {
                    Arrays.sort(files, (f1, f2) -> {
                        long size1 = f1.isDirectory() ? 0 : f1.length();
                        long size2 = f2.isDirectory() ? 0 : f2.length();
                        return Long.compare(size2, size1);
                    });

                    for (File file : files) {
                        fileItems.add(new FileItem(file) {
                            @Override
                            public StringProperty typeProperty() {
                                return new SimpleStringProperty(file.isDirectory() ? "folder" : getFileExtension(file));
                            }
                        });
                    }
                    Platform.runLater(() -> itemCountLabel.setText("항목 수: " + files.length + "개"));
                } else {
                    showAlert(Alert.AlertType.ERROR, "목록 오류", "디렉토리 접근 권한 없음 또는 I/O 오류 발생");
                    Platform.runLater(() -> itemCountLabel.setText("항목 수: 0개"));
                }
            }
        }
    }

    private int parseChunkSize(String sizeStr) {
        try {
            String[] parts = sizeStr.split(" ");
            int size = Integer.parseInt(parts[0]);
            if (parts[1].equals("GB")) size *= 1024;
            return size * 1024 * 1024;
        } catch (Exception e) {
            throw new NumberFormatException("청크 크기 파싱 오류: " + e.getMessage());
        }
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        try {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setContentText(content);

            Stage alertStage = (Stage) alert.getDialogPane().getScene().getWindow();
            alertStage.getIcons().add(new javafx.scene.image.Image(getClass().getResourceAsStream("/icons/favicon.png")));
            alert.getDialogPane().setPrefSize(300, 100);

            alert.showAndWait();
        } catch (Exception e) {
            System.err.println("알림 표시 실패: " + e.getMessage());
        }
    }

    private void addToZip(File file, ZipOutputStream zos, String parentPath) throws IOException {
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
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[1024];
                int len;
                while ((len = fis.read(buffer)) > 0) {
                    zos.write(buffer, 0, len);
                }
            }
            zos.closeEntry();
        }
    }

    private String calculateFileHash(File file) throws Exception {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new Exception("해시 알고리즘 오류: " + e.getMessage());
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        return Base64.getEncoder().encodeToString(digest.digest());
    }

    private void saveSettings() {
        Properties props = new Properties();
        props.setProperty("chunkSize", chunkSizeCombo.getValue());
        props.setProperty("theme", isDarkMode ? "dark" : "light");
        try (FileOutputStream fos = new FileOutputStream("settings.properties")) {
            props.store(fos, "PASSCODE Settings");
        } catch (IOException e) {
            showAlert(Alert.AlertType.ERROR, "설정 저장 실패", e.getMessage());
        }
    }

    private void loadSettings() {
        Properties props = new Properties();
        File settingsFile = new File("settings.properties");
        try (FileInputStream fis = new FileInputStream(settingsFile)) {
            props.load(fis);
            chunkSizeCombo.setValue(props.getProperty("chunkSize", "32 MB"));
            currentDirectory = new File(props.getProperty("lastDirectory", System.getProperty("user.home")));
            isDarkMode = "dark".equals(props.getProperty("theme", "light"));
            if (isDarkMode) {
                Platform.runLater(() -> toggleTheme());
            }
            updateFileList();
        } catch (IOException e) {
            if (!settingsFile.exists()) {
                try (FileOutputStream fos = new FileOutputStream(settingsFile)) {
                    props.setProperty("chunkSize", "32 MB");
                    props.setProperty("lastDirectory", System.getProperty("user.home"));
                    props.setProperty("theme", "light");
                    props.store(fos, "PASSCODE Default Settings");
                } catch (IOException ex) {
                    showAlert(Alert.AlertType.ERROR, "기본 설정 생성 실패", ex.getMessage());
                }
            }
            currentDirectory = new File(System.getProperty("user.home"));
            updateFileList();
        }
    }

    @FXML
    private void showInfo() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("정보");
        dialog.setHeaderText("프로그램 정보");

        VBox mainLayout = new VBox(10);
        mainLayout.setPadding(new Insets(10));

        VBox infoBox = new VBox(5);
        Label titleLabel = new Label("PASSCODE v" + ModernEncryptionApp.getVersion());
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        Label devLabel = new Label("개발자: DDLATTE");

        infoBox.getChildren().addAll(titleLabel, devLabel);

        TextArea textArea = new TextArea(
                "사용법:\n" +
                        "1. '폴더 열기'를 통해 폴더를 선택하세요.\n" +
                        "2. '새 키 생성' 또는 '키 로드'를 통해 암호화 키를 설정하세요.\n" +
                        "3. '암호화' 버튼으로 파일/폴더를 암호화하거나, '복호화' 버튼으로 복원하세요.\n\n" +
                        "이용 약관:\n" +
                        "이 프로그램은 '있는 그대로' 제공되며, 명시적이거나 묵시적인 어떠한 보증도 제공하지 않습니다. " +
                        "개발자 DDLATTE는 이 프로그램의 사용으로 인한 데이터 손실, 손상 또는 기타 문제에 대해 책임을 지지 않습니다. " +
                        "암호화된 파일의 키를 분실할 경우 복구가 불가능할 수 있으므로, 반드시 키를 안전한 곳에 백업하시기 바랍니다. " +
                        "중요한 데이터는 암호화 전에 별도로 백업하는 것을 권장합니다. " +
                        "사용자는 본 프로그램을 사용함으로써 이러한 조건에 동의한 것으로 간주됩니다."
        );
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setPrefHeight(200);

        mainLayout.getChildren().addAll(infoBox, textArea);

        dialog.getDialogPane().setContent(mainLayout);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.OK);
        dialog.getDialogPane().setPrefWidth(450);

        Stage dialogStage = (Stage) dialog.getDialogPane().getScene().getWindow();
        dialogStage.getIcons().add(new javafx.scene.image.Image(getClass().getResourceAsStream("/icons/favicon.png")));

        try {
            dialog.showAndWait();
        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "정보 표시 오류", "정보 다이얼로그 표시 실패: " + e.getMessage());
        }
    }

    @FXML
    private void showlibrary() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("사용된 라이브러리");
        dialog.setHeaderText("PASSCODE에서 사용된 라이브러리");

        TextArea libraryText = new TextArea();
        libraryText.setEditable(false);
        libraryText.setText(
                "사용된 라이브러리:\n" +
                        "- JavaFX: UI 구현\n" +
                        "- Ikonli: 아이콘 제공\n" +
                        "- Java Cryptography Architecture (JCA): 암호화/복호화\n"
        );

        dialog.getDialogPane().setContent(libraryText);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.OK);

        Stage dialogStage = (Stage) dialog.getDialogPane().getScene().getWindow();
        dialogStage.getIcons().add(new javafx.scene.image.Image(getClass().getResourceAsStream("/icons/favicon.png")));

        try {
            dialog.showAndWait();
        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "라이브러리 표시 오류", "라이브러리 다이얼로그 표시 실패: " + e.getMessage());
        }
    }

    @FXML
    private void onSecureDelete() {
        ObservableList<FileItem> selectedItems = fileTable.getSelectionModel().getSelectedItems();
        if (selectedItems.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "경고", "선택된 파일이 없습니다");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("안전 삭제 확인");
        confirm.setHeaderText("선택한 파일을 안전하게 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.");
        Stage alertStage = (Stage) confirm.getDialogPane().getScene().getWindow();
        alertStage.getIcons().add(new javafx.scene.image.Image(getClass().getResourceAsStream("/icons/favicon.png")));

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) return;

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
                showAlert(Alert.AlertType.ERROR, "삭제 오류", "파일 삭제 실패: " + e.getMessage());
            }
        }
    }

    private String generateUniqueOutputPath(String basePath) {
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
}
