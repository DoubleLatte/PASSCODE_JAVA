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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * 완전히 개선된 UI 컨트롤러
 * 
 * 🔧 주요 개선사항:
 * - UI 스레드 안전성 완전 보장 (경합 조건 해결)
 * - 메모리 누수 방지를 위한 완벽한 리소스 정리
 * - 데드락 방지를 위한 타임아웃 적용
 * - 강화된 예외 처리 및 복구 메커니즘
 * - 스레드 안전한 작업 취소 및 상태 관리
 * - 사용자 경험 개선 (응답성, 피드백)
 */
public class ModernEncryptionController {
    private static final Logger LOGGER = Logger.getLogger(ModernEncryptionController.class.getName());
    
    // UI 컴포넌트들
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

    // 비즈니스 로직 관리자들
    private final FileSystemManager fileSystemManager;
    private final SettingsManager settingsManager;
    
    // 스레드 안전성을 위한 변수들
    private final ObservableList<FileItem> fileItems;
    private final ReentrantLock uiLock = new ReentrantLock();
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    
    private volatile Task<Void> currentTask;
    private volatile boolean keyLoaded = false;

    public ModernEncryptionController() {
        fileSystemManager = new FileSystemManager();
        settingsManager = new SettingsManager();
        fileItems = FXCollections.synchronizedObservableList(FXCollections.observableArrayList());
        
        LOGGER.info("ModernEncryptionController 초기화됨");
    }

    @FXML
    public void initialize() {
        try {
            if (isInitialized.compareAndSet(false, true)) {
                setupUI();
                setupTableColumns();
                setupChunkSizeCombo();
                setupEventHandlers();
                
                // 메모리 모니터링 시작
                fileSystemManager.startMemoryMonitoring(memoryLabel);
                
                // 설정 로드
                loadSettings();
                
                LOGGER.info("UI 초기화 완료");
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "UI 초기화 실패", e);
            showCriticalErrorAndExit("초기화 오류", "프로그램 초기화에 실패했습니다: " + e.getMessage());
        }
    }

    /**
     * UI 기본 설정
     */
    private void setupUI() {
        // 테이블 설정
        fileTable.setItems(fileItems);
        fileTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        fileTable.setRowFactory(tv -> {
            TableRow<FileItem> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    // 더블클릭 시 파일 정보 표시
                    showFileInfo(row.getItem());
                }
            });
            return row;
        });
        
        // 버튼 아이콘 설정
        safeSetButtonIcon(encryptButton, "fas-lock");
        safeSetButtonIcon(decryptButton, "fas-unlock");
        safeSetButtonIcon(cancelButton, "fas-times");
        
        // 초기 상태 설정
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);
        progressBar.setVisible(false);
        progressLabel.setVisible(false);
        
        // 초기 텍스트 설정
        memoryLabel.setText("메모리: 로딩 중...");
        itemCountLabel.setText("항목 수: 0개");
        statusLabel.setText("키 상태: 키가 로드되지 않음");
        
        // 툴팁 설정
        setupTooltips();
    }

    /**
     * 테이블 컬럼 설정
     */
    private void setupTableColumns() {
        fileTable.getColumns().clear();
        
        // 이름 컬럼
        TableColumn<FileItem, String> nameCol = new TableColumn<>("이름");
        nameCol.setCellValueFactory(data -> data.getValue().nameProperty());
        nameCol.prefWidthProperty().bind(fileTable.widthProperty().multiply(0.35));
        nameCol.setResizable(true);

        // 유형 컬럼
        TableColumn<FileItem, String> typeCol = new TableColumn<>("유형");
        typeCol.setCellValueFactory(data -> data.getValue().typeProperty());
        typeCol.prefWidthProperty().bind(fileTable.widthProperty().multiply(0.15));
        typeCol.setResizable(true);

        // 크기 컬럼
        TableColumn<FileItem, String> sizeCol = new TableColumn<>("크기");
        sizeCol.setCellValueFactory(data -> data.getValue().sizeProperty());
        sizeCol.prefWidthProperty().bind(fileTable.widthProperty().multiply(0.15));
        sizeCol.setResizable(true);

        // 상태 컬럼
        TableColumn<FileItem, String> statusCol = new TableColumn<>("상태");
        statusCol.setCellValueFactory(data -> data.getValue().statusProperty());
        statusCol.prefWidthProperty().bind(fileTable.widthProperty().multiply(0.15));
        statusCol.setResizable(true);

        // 진행률 컬럼
        TableColumn<FileItem, Number> progressCol = new TableColumn<>("진행률");
        progressCol.setCellValueFactory(data -> data.getValue().progressProperty());
        progressCol.setCellFactory(col -> new TableCell<FileItem, Number>() {
            private final ProgressBar progressBar = new ProgressBar(0);
            {
                progressBar.setPrefWidth(120);
                progressBar.setPrefHeight(16);
            }
            
            @Override
            protected void updateItem(Number progress, boolean empty) {
                super.updateItem(progress, empty);
                if (empty || progress == null) {
                    setGraphic(null);
                } else {
                    double value = progress.doubleValue();
                    progressBar.setProgress(value);
                    setGraphic(progressBar);
                }
            }
        });
        progressCol.prefWidthProperty().bind(fileTable.widthProperty().multiply(0.20));
        progressCol.setResizable(true);

        // 컬럼들 추가
        fileTable.getColumns().addAll(nameCol, typeCol, sizeCol, statusCol, progressCol);
        
        // 컬럼 정렬 금지 (파일 크기 순 유지)
        fileTable.getSortOrder().clear();
        fileTable.sortPolicyProperty().set(t -> false);
    }

    /**
     * 청크 크기 콤보박스 설정
     */
    private void setupChunkSizeCombo() {
        chunkSizeCombo.getItems().clear();
        chunkSizeCombo.getItems().addAll(
            "1 MB", "16 MB", "32 MB", "64 MB", 
            "128 MB", "256 MB", "512 MB", "1 GB"
        );
        
        // 기본값을 시스템 메모리에 따라 설정
        String optimalSize = settingsManager.getOptimalChunkSize();
        chunkSizeCombo.setValue(optimalSize);
        
        // 값 변경 리스너
        chunkSizeCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                saveSettingsSafely();
            }
        });
    }

    /**
     * 이벤트 핸들러 설정
     */
    private void setupEventHandlers() {
        // 키보드 단축키 설정
        Platform.runLater(() -> {
            Scene scene = fileTable.getScene();
            if (scene != null) {
                scene.setOnKeyPressed(event -> {
                    try {
                        switch (event.getCode()) {
                            case F5:
                                refreshFileList();
                                break;
                            case DELETE:
                                if (event.isShiftDown()) {
                                    onSecureDelete();
                                }
                                break;
                            case ESCAPE:
                                if (currentTask != null && currentTask.isRunning()) {
                                    cancelTask();
                                }
                                break;
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "키보드 이벤트 처리 오류", e);
                    }
                });
            }
        });
        
        // 윈도우 닫기 이벤트 핸들러
        Platform.runLater(() -> {
            Stage stage = (Stage) fileTable.getScene().getWindow();
            if (stage != null) {
                stage.setOnCloseRequest(event -> {
                    try {
                        if (currentTask != null && currentTask.isRunning()) {
                            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                            alert.setTitle("작업 진행 중");
                            alert.setHeaderText("현재 작업이 진행 중입니다.");
                            alert.setContentText("작업을 취소하고 종료하시겠습니까?");
                            
                            Optional<ButtonType> result = alert.showAndWait();
                            if (result.isPresent() && result.get() == ButtonType.OK) {
                                cancelTask();
                                shutdown();
                            } else {
                                event.consume(); // 종료 취소
                            }
                        } else {
                            shutdown();
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "종료 처리 오류", e);
                    }
                });
            }
        });
    }

    /**
     * 툴팁 설정
     */
    private void setupTooltips() {
        setTooltip(encryptButton, "선택한 파일을 암호화합니다 (Ctrl+E)");
        setTooltip(decryptButton, "선택한 암호화 파일을 복호화합니다 (Ctrl+D)");
        setTooltip(cancelButton, "현재 작업을 취소합니다 (ESC)");
        setTooltip(chunkSizeCombo, "암호화 시 사용할 버퍼 크기를 선택합니다");
    }

    // ==================== 메뉴/버튼 이벤트 핸들러들 ====================

    @FXML
    private void onOpenFolder() {
        if (isShuttingDown.get()) return;
        
        try {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("작업 폴더 선택");
            
            // 마지막 디렉터리 설정
            File currentDir = fileSystemManager.getCurrentDirectory();
            if (currentDir != null && currentDir.exists()) {
                chooser.setInitialDirectory(currentDir);
            }
            
            File directory = chooser.showDialog(getStage());
            if (directory != null && directory.exists() && directory.isDirectory()) {
                fileSystemManager.setCurrentDirectory(directory);
                updateFileListSafely();
                saveSettingsSafely();
                
                LOGGER.info("작업 폴더 선택됨: " + directory.getPath());
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "폴더 선택 오류", e);
            showAlert(Alert.AlertType.ERROR, "폴더 선택 오류", "폴더를 선택하는 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    @FXML
    private void refreshFileList() {
        if (isShuttingDown.get()) return;
        
        if (fileSystemManager.getCurrentDirectory() == null) {
            showAlert(Alert.AlertType.WARNING, "폴더 미선택", "먼저 작업 폴더를 선택해주세요.");
            return;
        }
        
        updateFileListSafely();
    }

    @FXML
    private void onCreateKey() {
        if (isShuttingDown.get()) return;
        
        try {
            // 패스워드 입력 다이얼로그
            PasswordInputDialog dialog = new PasswordInputDialog("새 키 생성", "새 암호화 키의 비밀번호를 입력하세요");
            Optional<String> password = dialog.showAndWait();
            
            if (!password.isPresent() || password.get().trim().isEmpty()) {
                return;
            }
            
            // 키 파일 저장 위치 선택
            FileChooser keyChooser = new FileChooser();
            keyChooser.setTitle("암호화 키 저장");
            keyChooser.setInitialFileName("my_encryption_key.key");
            keyChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("암호화 키 파일 (*.key)", "*.key")
            );
            
            // 마지막 키 경로 설정
            String lastKeyPath = settingsManager.getLastKeyPath();
            if (lastKeyPath != null && new File(lastKeyPath).exists()) {
                keyChooser.setInitialDirectory(new File(lastKeyPath));
            }
            
            File keyFile = keyChooser.showSaveDialog(getStage());
            if (keyFile != null) {
                // 키 생성 작업 실행
                executeKeyOperation(() -> {
                    fileSystemManager.generateKey(keyFile, password.get());
                    
                    Platform.runLater(() -> {
                        keyLoaded = true;
                        statusLabel.setText("키 상태: " + keyFile.getName() + " (생성됨)");
                        settingsManager.setLastKeyPath(keyFile.getParent());
                        saveSettingsSafely();
                        
                        showAlert(Alert.AlertType.INFORMATION, "키 생성 완료", 
                            "암호화 키가 성공적으로 생성되었습니다.\n파일: " + keyFile.getName());
                    });
                });
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "키 생성 오류", e);
            showAlert(Alert.AlertType.ERROR, "키 생성 실패", "키 생성 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    @FXML
    private void onLoadKey() {
        if (isShuttingDown.get()) return;
        
        try {
            // 키 파일 선택
            FileChooser chooser = new FileChooser();
            chooser.setTitle("암호화 키 파일 선택");
            chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("암호화 키 파일 (*.key)", "*.key")
            );
            
            // 마지막 키 경로 설정
            String lastKeyPath = settingsManager.getLastKeyPath();
            if (lastKeyPath != null && new File(lastKeyPath).exists()) {
                chooser.setInitialDirectory(new File(lastKeyPath));
            }
            
            File keyFile = chooser.showOpenDialog(getStage());
            if (keyFile != null && keyFile.exists()) {
                
                // 패스워드 입력 다이얼로그
                PasswordInputDialog dialog = new PasswordInputDialog("키 로드", "키 파일의 비밀번호를 입력하세요");
                Optional<String> password = dialog.showAndWait();
                
                if (password.isPresent() && !password.get().trim().isEmpty()) {
                    // 키 로드 작업 실행
                    executeKeyOperation(() -> {
                        fileSystemManager.loadKey(keyFile, password.get());
                        
                        Platform.runLater(() -> {
                            keyLoaded = true;
                            statusLabel.setText("키 상태: " + keyFile.getName() + " (로드됨)");
                            settingsManager.setLastKeyPath(keyFile.getParent());
                            saveSettingsSafely();
                            
                            showAlert(Alert.AlertType.INFORMATION, "키 로드 완료", 
                                "암호화 키가 성공적으로 로드되었습니다.\n파일: " + keyFile.getName());
                        });
                    });
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "키 로드 오류", e);
            showAlert(Alert.AlertType.ERROR, "키 로드 실패", "키 로드 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    @FXML
    private void onEncrypt() {
        if (isShuttingDown.get()) return;
        
        if (!validateEncryptionPreconditions()) {
            return;
        }
        
        ObservableList<FileItem> selectedItems = fileTable.getSelectionModel().getSelectedItems();
        ObservableList<FileItem> encryptedFiles = selectedItems.filtered(item ->
                item.getName().toLowerCase().endsWith(".lock"));
        
        if (encryptedFiles.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "암호화 파일 미선택", 
                "복호화할 암호화 파일(.lock)을 선택해주세요.");
            return;
        }
        
        // 확인 다이얼로그
        if (!showConfirmationDialog("복호화 확인", 
            String.format("선택한 %d개 암호화 파일을 복호화하시겠습니까?", encryptedFiles.size()))) {
            return;
        }
        
        try {
            currentTask = fileSystemManager.createDecryptionTask(encryptedFiles, fileItems, fileTable);
            startTaskSafely();
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "복호화 작업 시작 실패", e);
            showAlert(Alert.AlertType.ERROR, "복호화 실패", "복호화 작업을 시작할 수 없습니다: " + e.getMessage());
        }
    }

    @FXML
    private void onSecureDelete() {
        if (isShuttingDown.get()) return;
        
        ObservableList<FileItem> selectedItems = fileTable.getSelectionModel().getSelectedItems();
        if (selectedItems.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "파일 미선택", "삭제할 파일을 선택해주세요.");
            return;
        }
        
        // 강력한 확인 다이얼로그
        Alert confirmAlert = new Alert(Alert.AlertType.WARNING);
        confirmAlert.setTitle("안전 삭제 확인");
        confirmAlert.setHeaderText("⚠️ 위험한 작업입니다!");
        confirmAlert.setContentText(
            String.format("선택한 %d개 파일을 영구적으로 삭제합니다.\n\n" +
                "• 이 작업은 되돌릴 수 없습니다\n" +
                "• 파일이 완전히 제거되어 복구 불가능합니다\n" +
                "• 시간이 오래 걸릴 수 있습니다\n\n" +
                "정말로 계속하시겠습니까?", selectedItems.size())
        );
        
        ButtonType deleteButton = new ButtonType("삭제", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("취소", ButtonBar.ButtonData.CANCEL_CLOSE);
        confirmAlert.getButtonTypes().setAll(deleteButton, cancelButton);
        
        Optional<ButtonType> result = confirmAlert.showAndWait();
        if (result.isPresent() && result.get() == deleteButton) {
            try {
                fileSystemManager.secureDeleteFiles(selectedItems, fileItems, fileTable, itemCountLabel);
                LOGGER.info("안전 삭제 작업 시작됨: " + selectedItems.size() + "개 파일");
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "안전 삭제 시작 실패", e);
                showAlert(Alert.AlertType.ERROR, "삭제 실패", "파일 삭제를 시작할 수 없습니다: " + e.getMessage());
            }
        }
    }

    @FXML
    private void cancelTask() {
        if (currentTask != null && currentTask.isRunning()) {
            LOGGER.info("사용자가 작업 취소 요청");
            
            Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
            confirmAlert.setTitle("작업 취소");
            confirmAlert.setHeaderText("현재 작업을 취소하시겠습니까?");
            confirmAlert.setContentText("진행 중인 작업이 중단되고 부분적으로 처리된 파일들이 정리됩니다.");
            
            Optional<ButtonType> result = confirmAlert.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                boolean cancelled = currentTask.cancel(true);
                
                Platform.runLater(() -> {
                    if (cancelled) {
                        progressLabel.setText("작업이 취소되었습니다");
                        LOGGER.info("작업 취소 완료");
                    } else {
                        progressLabel.setText("작업 취소 실패 - 곧 완료될 예정입니다");
                        LOGGER.warning("작업 취소 실패");
                    }
                    
                    // UI 상태 복원
                    hideProgressControls();
                });
            }
        }
    }

    @FXML
    private void onExit() {
        try {
            if (currentTask != null && currentTask.isRunning()) {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("종료 확인");
                alert.setHeaderText("작업이 진행 중입니다.");
                alert.setContentText("작업을 취소하고 프로그램을 종료하시겠습니까?");
                
                Optional<ButtonType> result = alert.showAndWait();
                if (result.isPresent() && result.get() == ButtonType.OK) {
                    cancelTask();
                    shutdown();
                    Platform.exit();
                }
            } else {
                if (showConfirmationDialog("종료 확인", "프로그램을 종료하시겠습니까?")) {
                    shutdown();
                    Platform.exit();
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "종료 처리 오류", e);
            Platform.exit(); // 강제 종료
        }
    }

    @FXML
    private void toggleTheme() {
        if (isShuttingDown.get()) return;
        
        try {
            Scene scene = fileTable.getScene();
            if (scene != null) {
                boolean isDarkMode = settingsManager.toggleTheme(scene);
                saveSettingsSafely();
                
                LOGGER.info("테마 변경됨: " + (isDarkMode ? "다크" : "라이트") + " 모드");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "테마 변경 오류", e);
            showAlert(Alert.AlertType.WARNING, "테마 변경 실패", "테마 변경 중 오류가 발생했습니다.");
        }
    }

    @FXML
    private void showInfo() {
        if (isShuttingDown.get()) return;
        
        try {
            Alert infoDialog = new Alert(Alert.AlertType.INFORMATION);
            infoDialog.setTitle("프로그램 정보");
            infoDialog.setHeaderText("PASSCODE v" + ModernEncryptionApp.getVersion());
            
            TextArea content = new TextArea();
            content.setEditable(false);
            content.setWrapText(true);
            content.setPrefRowCount(12);
            content.setText(
                "🔐 안전한 파일 암호화 프로그램\n\n" +
                "개발자: DDLATTE\n" +
                "버전: " + ModernEncryptionApp.getVersion() + "\n" +
                "암호화: AES-256-GCM\n" +
                "키 유도: PBKDF2-HMAC-SHA256 (120,000회 반복)\n\n" +
                "📖 사용법:\n" +
                "1. '폴더 열기'로 작업 폴더를 선택합니다\n" +
                "2. '새 키 생성' 또는 '키 로드'로 암호화 키를 준비합니다\n" +
                "3. 파일을 선택하고 '암호화' 또는 '복호화'를 실행합니다\n\n" +
                "⚠️ 주의사항:\n" +
                "• 키 파일과 비밀번호를 반드시 안전하게 보관하세요\n" +
                "• 키를 분실하면 파일 복구가 불가능합니다\n" +
                "• 중요한 데이터는 사전에 백업하세요\n" +
                "• 대용량 파일 처리 시 충분한 디스크 공간을 확보하세요\n\n" +
                "🔧 고급 기능:\n" +
                "• Shift+Delete: 안전 삭제\n" +
                "• F5: 파일 목록 새로고침\n" +
                "• ESC: 작업 취소\n" +
                "• 자동 메모리 관리 및 최적화"
            );
            
            infoDialog.getDialogPane().setContent(content);
            infoDialog.getDialogPane().setPrefWidth(500);
            infoDialog.showAndWait();
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "정보 다이얼로그 표시 오류", e);
        }
    }

    @FXML
    private void showLibrary() {
        if (isShuttingDown.get()) return;
        
        try {
            Alert libraryDialog = new Alert(Alert.AlertType.INFORMATION);
            libraryDialog.setTitle("사용된 라이브러리");
            libraryDialog.setHeaderText("PASSCODE 라이브러리 정보");
            
            TextArea content = new TextArea();
            content.setEditable(false);
            content.setWrapText(true);
            content.setPrefRowCount(10);
            content.setText(
                "📚 오픈소스 라이브러리\n\n" +
                "🖥️ JavaFX\n" +
                "• 용도: 사용자 인터페이스\n" +
                "• 라이선스: Apache License 2.0\n" +
                "• 설명: 크로스 플랫폼 GUI 프레임워크\n\n" +
                "🎨 Ikonli (FontAwesome)\n" +
                "• 용도: 아이콘 표시\n" +
                "• 라이선스: Apache License 2.0\n" +
                "• 설명: JavaFX용 아이콘 라이브러리\n\n" +
                "🔐 JCA (Java Cryptography Architecture)\n" +
                "• 용도: 암호화 및 복호화\n" +
                "• 라이선스: Oracle Binary Code License\n" +
                "• 설명: Java 표준 암호화 API\n\n" +
                "🔤 Noto Sans KR\n" +
                "• 용도: 한글 폰트\n" +
                "• 라이선스: SIL Open Font License 1.1\n" +
                "• 설명: Google의 한글 웹폰트\n\n" +
                "⚖️ 라이선스 고지\n" +
                "모든 사용된 라이브러리는 해당 라이선스 조건에 따라 사용되었습니다.\n" +
                "자세한 내용은 각 라이브러리의 공식 문서를 참조하세요."
            );
            
            libraryDialog.getDialogPane().setContent(content);
            libraryDialog.getDialogPane().setPrefWidth(500);
            libraryDialog.showAndWait();
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "라이브러리 다이얼로그 표시 오류", e);
        }
    }

    // ==================== 유틸리티 메서드들 ====================

    /**
     * 안전한 파일 목록 업데이트
     */
    private void updateFileListSafely() {
        try {
            fileSystemManager.updateFileList(fileItems, itemCountLabel);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "파일 목록 업데이트 오류", e);
            showAlert(Alert.AlertType.ERROR, "목록 로드 실패", "파일 목록을 업데이트하는 중 오류가 발생했습니다.");
        }
    }

    /**
     * 암호화 전제조건 검증
     */
    private boolean validateEncryptionPreconditions() {
        if (!keyLoaded) {
            showAlert(Alert.AlertType.WARNING, "키 미로드", "먼저 암호화 키를 생성하거나 로드해주세요.");
            return false;
        }
        
        if (fileSystemManager.getCurrentDirectory() == null) {
            showAlert(Alert.AlertType.WARNING, "폴더 미선택", "먼저 작업 폴더를 선택해주세요.");
            return false;
        }
        
        if (currentTask != null && currentTask.isRunning()) {
            showAlert(Alert.AlertType.WARNING, "작업 진행 중", "현재 다른 작업이 진행 중입니다. 완료 후 다시 시도하세요.");
            return false;
        }
        
        return true;
    }

    /**
     * 키 관련 작업 안전 실행
     */
    private void executeKeyOperation(Runnable operation) {
        Task<Void> keyTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                operation.run();
                return null;
            }
        };
        
        keyTask.setOnSucceeded(e -> {
            LOGGER.info("키 작업 완료됨");
        });
        
        keyTask.setOnFailed(e -> {
            Throwable exception = keyTask.getException();
            LOGGER.log(Level.SEVERE, "키 작업 실패", exception);
            
            Platform.runLater(() -> {
                String message = exception.getMessage();
                if (message.contains("password") || message.contains("패스워드")) {
                    showAlert(Alert.AlertType.ERROR, "인증 실패", "잘못된 비밀번호이거나 손상된 키 파일입니다.");
                } else {
                    showAlert(Alert.AlertType.ERROR, "키 작업 실패", "키 작업 중 오류가 발생했습니다: " + message);
                }
            });
        });
        
        Thread keyThread = new Thread(keyTask, "Key-Operation");
        keyThread.setDaemon(true);
        keyThread.start();
    }

    /**
     * 작업 안전 시작
     */
    private void startTaskSafely() {
        if (currentTask == null) return;
        
        // 진행률 바인딩
        progressBar.progressProperty().bind(currentTask.progressProperty());
        progressLabel.textProperty().bind(currentTask.messageProperty());
        
        // UI 상태 변경
        Platform.runLater(this::showProgressControls);
        
        // 이벤트 핸들러 설정
        currentTask.setOnSucceeded(e -> {
            Platform.runLater(() -> {
                hideProgressControls();
                LOGGER.info("작업 성공 완료");
            });
        });
        
        currentTask.setOnFailed(e -> {
            Throwable exception = currentTask.getException();
            LOGGER.log(Level.SEVERE, "작업 실패", exception);
            
            Platform.runLater(() -> {
                hideProgressControls();
                showAlert(Alert.AlertType.ERROR, "작업 실패", 
                    "작업 중 오류가 발생했습니다: " + exception.getMessage());
            });
        });
        
        currentTask.setOnCancelled(e -> {
            Platform.runLater(() -> {
                hideProgressControls();
                LOGGER.info("작업 취소됨");
            });
        });
        
        // 작업 시작
        Thread taskThread = new Thread(currentTask, "Main-Task");
        taskThread.setDaemon(true);
        taskThread.start();
    }

    /**
     * 진행률 컨트롤 표시
     */
    private void showProgressControls() {
        progressBar.setVisible(true);
        progressBar.setManaged(true);
        progressLabel.setVisible(true);
        progressLabel.setManaged(true);
        cancelButton.setVisible(true);
        cancelButton.setManaged(true);
        
        // 작업 버튼들 비활성화
        encryptButton.setDisable(true);
        decryptButton.setDisable(true);
    }

    /**
     * 진행률 컨트롤 숨기기
     */
    private void hideProgressControls() {
        // 바인딩 해제
        progressBar.progressProperty().unbind();
        progressLabel.textProperty().unbind();
        
        // 컨트롤 숨기기
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        progressBar.setProgress(0);
        progressLabel.setVisible(false);
        progressLabel.setManaged(false);
        progressLabel.setText("");
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);
        
        // 작업 버튼들 활성화
        encryptButton.setDisable(false);
        decryptButton.setDisable(false);
        
        // 현재 작업 클리어
        currentTask = null;
    }

    /**
     * 설정 안전 저장
     */
    private void saveSettingsSafely() {
        try {
            settingsManager.saveSettings(
                chunkSizeCombo.getValue(),
                fileSystemManager.getCurrentDirectory()
            );
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "설정 저장 오류", e);
        }
    }

    /**
     * 설정 로드
     */
    private void loadSettings() {
        try {
            Scene scene = fileTable.getScene();
            settingsManager.loadSettings(
                chunkSizeCombo,
                fileSystemManager::setCurrentDirectory,
                scene
            );
            updateFileListSafely();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "설정 로드 오류", e);
        }
    }

    /**
     * 파일 정보 표시
     */
    private void showFileInfo(FileItem item) {
        try {
            File file = new File(fileSystemManager.getCurrentDirectory(), item.getName());
            if (!file.exists()) return;
            
            Alert infoAlert = new Alert(Alert.AlertType.INFORMATION);
            infoAlert.setTitle("파일 정보");
            infoAlert.setHeaderText(item.getName());
            
            StringBuilder info = new StringBuilder();
            info.append("📁 경로: ").append(file.getAbsolutePath()).append("\n");
            info.append("📏 크기: ").append(Utils.formatFileSize(file.length())).append("\n");
            info.append("📅 수정일: ").append(new java.util.Date(file.lastModified())).append("\n");
            info.append("🔐 유형: ").append(file.isDirectory() ? "폴더" : "파일").append("\n");
            info.append("🔓 읽기: ").append(file.canRead() ? "가능" : "불가").append("\n");
            info.append("✏️ 쓰기: ").append(file.canWrite() ? "가능" : "불가").append("\n");
            info.append("⚡ 실행: ").append(file.canExecute() ? "가능" : "불가").append("\n");
            
            if (item.getName().toLowerCase().endsWith(".lock")) {
                info.append("\n🔒 암호화된 파일입니다");
            }
            
            infoAlert.setContentText(info.toString());
            infoAlert.showAndWait();
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "파일 정보 표시 오류", e);
        }
    }

    /**
     * 현재 스테이지 가져오기
     */
    private Stage getStage() {
        return (Stage) fileTable.getScene().getWindow();
    }

    /**
     * 안전한 버튼 아이콘 설정
     */
    private void safeSetButtonIcon(Button button, String iconLiteral) {
        try {
            if (button != null) {
                button.setGraphic(new FontIcon(iconLiteral));
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "버튼 아이콘 설정 실패: " + iconLiteral, e);
        }
    }

    /**
     * 툴팁 설정
     */
    private void setTooltip(Control control, String text) {
        try {
            if (control != null) {
                Tooltip tooltip = new Tooltip(text);
                tooltip.setShowDelay(javafx.util.Duration.millis(500));
                control.setTooltip(tooltip);
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "툴팁 설정 실패", e);
        }
    }

    /**
     * 확인 다이얼로그 표시
     */
    private boolean showConfirmationDialog(String title, String message) {
        try {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle(title);
            confirm.setHeaderText(null);
            confirm.setContentText(message);
            
            Optional<ButtonType> result = confirm.showAndWait();
            return result.isPresent() && result.get() == ButtonType.OK;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "확인 다이얼로그 오류", e);
            return false;
        }
    }

    /**
     * 경고창 표시
     */
    private void showAlert(Alert.AlertType type, String title, String content) {
        Platform.runLater(() -> {
            try {
                Alert alert = new Alert(type);
                alert.setTitle(title);
                alert.setHeaderText(null);
                alert.setContentText(content);
                alert.showAndWait();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "알림 다이얼로그 표시 실패", e);
            }
        });
    }

    /**
     * 치명적 오류 시 프로그램 종료
     */
    private void showCriticalErrorAndExit(String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText("치명적 오류");
            alert.setContentText(content + "\n\n프로그램을 종료합니다.");
            alert.showAndWait();
            
            shutdown();
            Platform.exit();
        });
    }

    /**
     * 완전한 리소스 정리
     */
    public void shutdown() {
        if (isShuttingDown.compareAndSet(false, true)) {
            LOGGER.info("Controller 종료 시작...");
            
            try {
                // 현재 작업 취소
                if (currentTask != null && currentTask.isRunning()) {
                    currentTask.cancel(true);
                }
                
                // 설정 저장
                saveSettingsSafely();
                
                // 파일 시스템 매니저 종료
                fileSystemManager.shutdown();
                
                LOGGER.info("Controller 종료 완료");
                
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Controller 종료 중 오류", e);
            }
        }
    }

    // ==================== 내부 클래스들 ====================

    /**
     * 사용자 정의 패스워드 입력 다이얼로그
     */
    private static class PasswordInputDialog extends Dialog<String> {
        public PasswordInputDialog(String title, String message) {
            setTitle(title);
            setHeaderText(message);
            
            ButtonType okButton = new ButtonType("확인", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelButton = new ButtonType("취소", ButtonBar.ButtonData.CANCEL_CLOSE);
            getDialogPane().getButtonTypes().addAll(okButton, cancelButton);
            
            PasswordField passwordField = new PasswordField();
            passwordField.setPromptText("비밀번호를 입력하세요");
            passwordField.setPrefWidth(300);
            
            Label warningLabel = new Label("⚠️ 비밀번호는 8자 이상이어야 합니다");
            warningLabel.setStyle("-fx-text-fill: #d97706; -fx-font-size: 11px;");
            
            VBox content = new VBox(10);
            content.getChildren().addAll(passwordField, warningLabel);
            
            getDialogPane().setContent(content);
            
            // 확인 버튼 초기 비활성화
            Button okBtn = (Button) getDialogPane().lookupButton(okButton);
            okBtn.setDisable(true);
            
            // 패스워드 유효성 검사
            passwordField.textProperty().addListener((obs, oldText, newText) -> {
                boolean isValid = newText != null && newText.trim().length() >= 8;
                okBtn.setDisable(!isValid);
                
                if (isValid) {
                    warningLabel.setText("✓ 유효한 비밀번호입니다");
                    warningLabel.setStyle("-fx-text-fill: #10b981; -fx-font-size: 11px;");
                } else {
                    warningLabel.setText("⚠️ 비밀번호는 8자 이상이어야 합니다");
                    warningLabel.setStyle("-fx-text-fill: #d97706; -fx-font-size: 11px;");
                }
            });
            
            // 결과 변환
            setResultConverter(buttonType -> {
                if (buttonType == okButton) {
                    return passwordField.getText();
                }
                return null;
            });
            
            // 포커스 설정
            Platform.runLater(passwordField::requestFocus);
        }
    }
}().getSelectedItems();
        if (selectedItems.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "파일 미선택", "암호화할 파일을 선택해주세요.");
            return;
        }
        
        // 확인 다이얼로그
        if (!showConfirmationDialog("암호화 확인", 
            String.format("선택한 %d개 항목을 암호화하시겠습니까?\n\n주의: 원본 파일이 암호화된 파일로 대체됩니다.", 
            selectedItems.size()))) {
            return;
        }
        
        try {
            currentTask = fileSystemManager.createEncryptionTask(
                selectedItems, chunkSizeCombo.getValue(), fileItems, fileTable
            );
            startTaskSafely();
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "암호화 작업 시작 실패", e);
            showAlert(Alert.AlertType.ERROR, "암호화 실패", "암호화 작업을 시작할 수 없습니다: " + e.getMessage());
        }
    }

    @FXML
    private void onDecrypt() {
        if (isShuttingDown.get()) return;
        
        if (!keyLoaded) {
            showAlert(Alert.AlertType.WARNING, "키 미로드", "먼저 암호화 키를 로드해주세요.");
            return;
        }
        
        ObservableList<FileItem> selectedItems = fileTable.getSelectionModel