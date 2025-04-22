package com.ddlatte.encryption;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.concurrent.Task;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.kordamp.ikonli.javafx.FontIcon;
import java.io.File;
import java.util.Optional;

/**
 * Main controller for the encryption application UI with user-selectable chunk sizes.
 */
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
    @FXML private Button cancelButton;

    private final FileSystemManager fileSystemManager;
    private final SettingsManager settingsManager;
    private ObservableList<FileItem> fileItems;
    private Task<Void> currentTask;

    public ModernEncryptionController() {
        fileSystemManager = new FileSystemManager();
        settingsManager = new SettingsManager();
    }

    @FXML
    public void initialize() {
        fileItems = FXCollections.synchronizedObservableList(FXCollections.observableArrayList());
        setupUI();
        setupTableColumns();
        setupChunkSizeCombo();
        fileSystemManager.startMemoryMonitoring(memoryLabel);
        loadSettings();
    }

    private void setupUI() {
        fileTable.setItems(fileItems);
        fileTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        encryptButton.setGraphic(new FontIcon("fas-lock"));
        decryptButton.setGraphic(new FontIcon("fas-unlock"));
        cancelButton.setGraphic(new FontIcon("fas-times"));
        cancelButton.setVisible(false);
        memoryLabel.setText("메모리: 초기화 중...");
        itemCountLabel.setText("항목 수: 0개");
        progressBar.setVisible(false);
        progressLabel.setVisible(false);
    }

    private void setupTableColumns() {
        TableColumn<FileItem, String> nameCol = new TableColumn<>("이름");
        nameCol.setCellValueFactory(data -> data.getValue().nameProperty());
        nameCol.prefWidthProperty().bind(fileTable.widthProperty().multiply(0.3));

        TableColumn<FileItem, String> typeCol = new TableColumn<>("유형");
        typeCol.setCellValueFactory(data -> data.getValue().typeProperty());
        typeCol.prefWidthProperty().bind(fileTable.widthProperty().multiply(0.15));

        TableColumn<FileItem, String> sizeCol = new TableColumn<>("크기");
        sizeCol.setCellValueFactory(data -> data.getValue().sizeProperty());
        typeCol.prefWidthProperty().bind(fileTable.widthProperty().multiply(0.15));

        TableColumn<FileItem, String> statusCol = new TableColumn<>("상태");
        statusCol.setCellValueFactory(data -> data.getValue().statusProperty());
        statusCol.prefWidthProperty().bind(fileTable.widthProperty().multiply(0.15));

        TableColumn<FileItem, Number> progressCol = new TableColumn<>("진행률");
        progressCol.setCellValueFactory(data -> data.getValue().progressProperty());
        progressCol.setCellFactory(col -> new TableCell<>() {
            private final ProgressBar progressBar = new ProgressBar(0);
            {
                progressBar.setPrefWidth(100);
            }
            @Override
            protected void updateItem(Number progress, boolean empty) {
                super.updateItem(progress, empty);
                if (empty || progress == null) {
                    setGraphic(null);
                } else {
                    progressBar.setProgress(progress.doubleValue());
                    setGraphic(progressBar);
                }
            }
        });
        progressCol.prefWidthProperty().bind(fileTable.widthProperty().multiply(0.25));

        fileTable.getColumns().setAll(nameCol, typeCol, sizeCol, statusCol, progressCol);
    }

    private void setupChunkSizeCombo() {
        chunkSizeCombo.getItems().addAll("1 MB", "16 MB", "32 MB", "64 MB", "128 MB", "256 MB", "512 MB", "1 GB");
        chunkSizeCombo.setValue("64 MB"); 
        chunkSizeCombo.valueProperty().addListener((obs, oldValue, newValue) -> saveSettings());
    }

    @FXML
    private void toggleTheme() {
        boolean isDarkMode = settingsManager.toggleTheme(fileTable.getScene());
        saveSettings();
    }

    public void shutdown() {
        fileSystemManager.shutdown();
    }

    @FXML
    private void onOpenFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("폴더 선택");
        File directory = chooser.showDialog(null);
        if (directory != null) {
            fileSystemManager.setCurrentDirectory(directory);
            updateFileList();
            saveSettings();
        }
    }

    @FXML
    private void refreshFileList() {
        if (fileSystemManager.getCurrentDirectory() == null) {
            showAlert(Alert.AlertType.WARNING, "폴더 미선택", "먼저 폴더를 선택해주세요.");
            return;
        }
        updateFileList();
    }

    @FXML
    private void onCreateKey() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("새 키 생성");
        dialog.setHeaderText("새 키를 위한 비밀번호를 입력하세요");
        dialog.setContentText("비밀번호:");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Optional<String> password = dialog.showAndWait();
        if (!password.isPresent()) return;

        FileChooser keyChooser = new FileChooser();
        keyChooser.setTitle("키 파일 저장");
        keyChooser.setInitialFileName("mykey.key");
        keyChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Encryption Key (*.key)", "*.key"));
        File keyFile = keyChooser.showSaveDialog(fileTable.getScene().getWindow());

        if (keyFile != null) {
            try {
                fileSystemManager.generateKey(keyFile, password.get());
                showAlert(Alert.AlertType.INFORMATION, "키 생성 완료", "키가 성공적으로 생성되었습니다: " + keyFile.getName());
                statusLabel.setText("키 로드됨: " + keyFile.getName());
                settingsManager.setLastKeyPath(keyFile.getParent());
                saveSettings();
            } catch (Exception e) {
                showAlert(Alert.AlertType.ERROR, "키 생성 실패", "키 생성 중 오류: " + e.getMessage());
            }
        }
    }

    @FXML
    private void onLoadKey() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("키 파일 선택");
        chooser.setInitialDirectory(new File(settingsManager.getLastKeyPath()));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Encryption Key (*.key)", "*.key"));
        File keyFile = chooser.showOpenDialog(null);

        if (keyFile != null) {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("키 로드");
            dialog.setHeaderText("키 파일의 비밀번호를 입력하세요");
            dialog.setContentText("비밀번호:");
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

            Optional<String> password = dialog.showAndWait();
            if (password.isPresent()) {
                try {
                    fileSystemManager.loadKey(keyFile, password.get());
                    showAlert(Alert.AlertType.INFORMATION, "키 로드 완료", "키가 성공적으로 로드되었습니다: " + keyFile.getName());
                    statusLabel.setText("키 로드됨: " + keyFile.getName());
                    settingsManager.setLastKeyPath(keyFile.getParent());
                    saveSettings();
                } catch (Exception e) {
                    showAlert(Alert.AlertType.ERROR, "키 로드 실패", "키 로드 중 오류: " + e.getMessage());
                }
            }
        }
    }

    @FXML
    private void onEncrypt() {
        ObservableList<FileItem> selectedItems = fileTable.getSelectionModel().getSelectedItems();
        if (selectedItems.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "파일 미선택", "암호화할 파일을 선택해주세요.");
            return;
        }

        if (!confirmAction("암호화 확인", "선택한 항목을 암호화하시겠습니까?")) return;

        currentTask = fileSystemManager.createEncryptionTask(selectedItems, chunkSizeCombo.getValue(), fileItems, fileTable);
        startTask();
    }

    @FXML
    private void onDecrypt() {
        ObservableList<FileItem> encryptedFiles = fileTable.getSelectionModel().getSelectedItems().filtered(item ->
                item.getName().endsWith(".lock"));
        if (encryptedFiles.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "암호화 파일 미선택", "복호화할 암호화 파일을 선택해주세요.");
            return;
        }

        if (!confirmAction("복호화 확인", "선택한 파일을 복호화하시겠습니까?")) return;

        currentTask = fileSystemManager.createDecryptionTask(encryptedFiles, fileItems, fileTable);
        startTask();
    }

    @FXML
    private void onSecureDelete() {
        ObservableList<FileItem> selectedItems = fileTable.getSelectionModel().getSelectedItems();
        if (selectedItems.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "파일 미선택", "삭제할 파일을 선택해주세요.");
            return;
        }

        if (!confirmAction("안전 삭제 확인", "선택한 파일을 영구적으로 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.")) return;

        fileSystemManager.secureDeleteFiles(selectedItems, fileItems, fileTable, itemCountLabel);
    }

    @FXML
    private void cancelTask() {
        if (currentTask != null && currentTask.isRunning()) {
            if (currentTask.cancel(true)) {
                Platform.runLater(() -> {
                    progressLabel.setText("작업이 취소되었습니다.");
                    progressBar.setProgress(0);
                    progressBar.setVisible(false);
                    progressLabel.setVisible(false);
                    cancelButton.setVisible(false);
                });
            } else {
                showAlert(Alert.AlertType.WARNING, "취소 실패", "작업 취소에 실패했습니다.");
            }
        }
    }

    @FXML
    private void onExit() {
        if (confirmAction("종료 확인", "프로그램을 종료하시겠습니까?")) {
            saveSettings();
            shutdown();
            Platform.exit();
        }
    }

    @FXML
    private void showInfo() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("정보");
        dialog.setHeaderText("PASSCODE v" + ModernEncryptionApp.getVersion());

        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.getChildren().addAll(
                new Label("개발자: DDLATTE").setStyle("-fx-font-weight: bold;"),
                new TextArea(
                        "사용법:\n" +
                                "1. 폴더를 선택하여 파일 목록을 불러옵니다.\n" +
                                "2. 새 키를 생성하거나 기존 키를 로드합니다.\n" +
                                "3. 파일을 선택하고 암호화 또는 복호화합니다.\n\n" +
                                "주의사항:\n" +
                                "- 키 파일을 분실하면 복호화가 불가능합니다.\n" +
                                "- 중요 데이터는 반드시 백업하세요."
                ).setEditable(false).setWrapText(true).setPrefHeight(150)
        );

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.OK);
        dialog.showAndWait();
    }

    @FXML
    private void showlibrary() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("사용된 라이브러리");
        dialog.setHeaderText("PASSCODE 라이브러리");

        TextArea textArea = new TextArea(
                "- JavaFX: 사용자 인터페이스\n" +
                        "- Ikonli: 아이콘\n" +
                        "- JCA: 암호화/복호화"
        );
        textArea.setEditable(false);
        dialog.getDialogPane().setContent(textArea);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.OK);
        dialog.showAndWait();
    }

    private void updateFileList() {
        fileSystemManager.updateFileList(fileItems, itemCountLabel);
    }

    private void startTask() {
        progressBar.progressProperty().bind(currentTask.progressProperty());
        progressLabel.textProperty().bind(currentTask.messageProperty());
        Platform.runLater(() -> {
            progressBar.setVisible(true);
            progressLabel.setVisible(true);
            cancelButton.setVisible(true);
        });

        currentTask.setOnSucceeded(e -> Platform.runLater(() -> {
            progressBar.setVisible(false);
            progressLabel.setVisible(false);
            cancelButton.setVisible(false);
        }));
        currentTask.setOnFailed(e -> Platform.runLater(() -> {
            showAlert(Alert.AlertType.ERROR, "작업 실패", "작업 중 오류 발생: " + currentTask.getException().getMessage());
            progressBar.setVisible(false);
            progressLabel.setVisible(false);
            cancelButton.setVisible(false);
        }));
        currentTask.setOnCancelled(e -> Platform.runLater(() -> {
            progressLabel.setText("작업이 취소되었습니다.");
            progressBar.setVisible(false);
            progressLabel.setVisible(false);
            cancelButton.setVisible(false);
        }));

        new Thread(currentTask).start();
    }

    private boolean confirmAction(String title, String message) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(title);
        confirm.setHeaderText(message);
        Optional<ButtonType> result = confirm.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setContentText(content);
            alert.showAndWait();
        });
    }

    private void saveSettings() {
        settingsManager.saveSettings(chunkSizeCombo.getValue(), fileSystemManager.getCurrentDirectory());
    }

    private void loadSettings() {
        settingsManager.loadSettings(chunkSizeCombo, fileSystemManager::setCurrentDirectory, fileTable.getScene());
        updateFileList();
    }
}
