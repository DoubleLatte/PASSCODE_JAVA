package com.example.encryption;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.concurrent.Task;
import javafx.scene.control.Alert.AlertType;
import org.kordamp.ikonli.javafx.FontIcon;
import java.io.File;

public class ModernEncryptionController {
    @FXML private TableView<FileItem> fileTable;
    @FXML private ComboBox<String> chunkSizeCombo;
    @FXML private ProgressBar progressBar;
    @FXML private Label progressLabel;
    @FXML private Label keyStatusLabel;
    @FXML private Button encryptButton;
    @FXML private Button decryptButton;
    
    private FolderEncryption folderEncryption;
    private EncryptedFileSystem efs;
    private File currentDirectory;
    
    @FXML
    public void initialize() {
        efs = new EncryptedFileSystem();
        folderEncryption = new FolderEncryption(efs);
        
        setupChunkSizeCombo();
        setupFileTable();
        setupDragAndDrop();
        updateKeyStatus();
        
        // 초기에 암호화/복호화 버튼 비활성화
        encryptButton.setDisable(true);
        decryptButton.setDisable(true);
    }
    
    private void setupChunkSizeCombo() {
        chunkSizeCombo.getItems().addAll(
            "1 MB", "16 MB", "32 MB", "64 MB",
            "128 MB", "256 MB", "512 MB", "1 GB"
        );
        chunkSizeCombo.setValue("32 MB");
    }
    
    private void setupFileTable() {
        TableColumn<FileItem, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(data -> data.getValue().nameProperty());
        
        TableColumn<FileItem, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(data -> data.getValue().typeProperty());
        
        TableColumn<FileItem, String> sizeCol = new TableColumn<>("Size");
        sizeCol.setCellValueFactory(data -> data.getValue().sizeProperty());
        
        TableColumn<FileItem, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(data -> data.getValue().statusProperty());

        fileTable.getColumns().addAll(nameCol, typeCol, sizeCol, statusCol);
    }
    
    private void setupDragAndDrop() {
        fileTable.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });

        fileTable.setOnDragDropped(event -> {
            List<File> files = event.getDragboard().getFiles();
            if (files.size() > 0) {
                currentDirectory = files.get(0).getParentFile();
                updateFileList();
            }
            event.consume();
        });
    }
    
    private void updateKeyStatus() {
        boolean hasKey = efs.hasLoadedKey();
        keyStatusLabel.setText(hasKey ? "Key Loaded" : "No Key Loaded");
        encryptButton.setDisable(!hasKey);
        decryptButton.setDisable(!hasKey);
    }
    
    @FXML
    private void onCreateKey() {
        Dialog<Pair<String, String>> dialog = new Dialog<>();
        dialog.setTitle("Create New Key");
        dialog.setHeaderText("Create a new encryption key");

        ButtonType createButtonType = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createButtonType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));

        PasswordField password = new PasswordField();
        PasswordField confirmPassword = new PasswordField();

        grid.add(new Label("Password:"), 0, 0);
        grid.add(password, 1, 0);
        grid.add(new Label("Confirm Password:"), 0, 1);
        grid.add(confirmPassword, 1, 1);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == createButtonType) {
                return new Pair<>(password.getText(), confirmPassword.getText());
            }
            return null;
        });

        dialog.showAndWait().ifPresent(result -> {
            if (!result.getKey().equals(result.getValue())) {
                showAlert(AlertType.ERROR, "Error", "Passwords do not match");
                return;
            }

            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Save Key File");
            fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Key Files", "*.key")
            );

            File file = fileChooser.showSaveDialog(null);
            if (file != null) {
                try {
                    efs.generateKey(file.getPath(), result.getKey());
                    updateKeyStatus();
                    showAlert(AlertType.INFORMATION, "Success", "Key created successfully");
                } catch (Exception e) {
                    showAlert(AlertType.ERROR, "Error", "Failed to create key: " + e.getMessage());
                }
            }
        });
    }
    
    @FXML
    private void onLoadKey() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Load Key");
        dialog.setHeaderText("Load existing encryption key");

        ButtonType loadButtonType = new ButtonType("Load", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(loadButtonType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));

        PasswordField password = new PasswordField();
        grid.add(new Label("Password:"), 0, 0);
        grid.add(password, 1, 0);

        dialog.getDialogPane().setContent(grid);
        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == loadButtonType) {
                return password.getText();
            }
            return null;
        });

        dialog.showAndWait().ifPresent(password -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Select Key File");
            fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Key Files", "*.key")
            );

            File file = fileChooser.showOpenDialog(null);
            if (file != null) {
                try {
                    efs.loadKey(file.getPath(), password);
                    updateKeyStatus();
                    showAlert(AlertType.INFORMATION, "Success", "Key loaded successfully");
                } catch (Exception e) {
                    showAlert(AlertType.ERROR, "Error", "Failed to load key: " + e.getMessage());
                }
            }
        });
    }
    
    @FXML
    private void onEncryptFolder() {
        if (currentDirectory == null) {
            showAlert(AlertType.WARNING, "Warning", "Please select a folder first");
            return;
        }

        try {
            long chunkSize = parseChunkSize(chunkSizeCombo.getValue());
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    folderEncryption.encryptFolder(
                        currentDirectory.getPath(),
                        chunkSize,
                        progress -> updateProgress(progress, 100.0)
                    );
                    return null;
                }
            };
            
            progressBar.progressProperty().bind(task.progressProperty());
            progressLabel.textProperty().bind(
                task.progressProperty().multiply(100.0).asString("%.1f%%")
            );
            
            task.setOnSucceeded(e -> {
                updateFileList();
                showAlert(AlertType.INFORMATION, "Success", "Folder encrypted successfully");
                progressBar.progressProperty().unbind();
                progressLabel.textProperty().unbind();
            });
            
            task.setOnFailed(e -> {
                showAlert(AlertType.ERROR, "Error", task.getException().getMessage());
                progressBar.progressProperty().unbind();
                progressLabel.textProperty().unbind();
            });
            
            new Thread(task).start();
        } catch (Exception e) {
            showAlert(AlertType.ERROR, "Error", e.getMessage());
        }
    }
    
    @FXML
    private void onDecryptFolder() {
        if (currentDirectory == null) {
            showAlert(AlertType.WARNING, "Warning", "Please select a folder first");
            return;
        }

        if (!currentDirectory.getName().endsWith(".locked")) {
            showAlert(AlertType.WARNING, "Warning", "Selected folder is not encrypted");
            return;
        }

        try {
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    folderEncryption.decryptFolder(
                        currentDirectory.getPath(),
                        progress -> updateProgress(progress, 100.0)
                    );
                    return null;
                }
            };
            
            progressBar.progressProperty().bind(task.progressProperty());
            progressLabel.textProperty().bind(
                task.progressProperty().multiply(100.0).asString("%.1f%%")
            );
            
            task.setOnSucceeded(e -> {
                updateFileList();
                showAlert(AlertType.INFORMATION, "Success", "Folder decrypted successfully");
                progressBar.progressProperty().unbind();
                progressLabel.textProperty().unbind();
            });
            
            task.setOnFailed(e -> {
                showAlert(AlertType.ERROR, "Error", task.getException().getMessage());
                progressBar.progressProperty().unbind();
                progressLabel.textProperty().unbind();
            });
            
            new Thread(task).start();
        } catch (Exception e) {
            showAlert(AlertType.ERROR, "Error", e.getMessage());
        }
    }
    
    private long parseChunkSize(String sizeStr) {
        String[] parts = sizeStr.split(" ");
        long size = Long.parseLong(parts[0]);
        return size * 1024 * 1024; // Convert to bytes
    }
    
    private void showAlert(AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setContentText(content);
        alert.showAndWait();
    }
    
    @FXML
    private void onExit() {
        Platform.exit();
    }
}
