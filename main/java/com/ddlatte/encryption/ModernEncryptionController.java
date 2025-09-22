/**
     * 창 설정 저장
     */
    private void saveWindowSettings() {
        try {
            Stage stage = getStage();
            if (stage != null) {
                settingsManager.saveWindowSettings(
                    stage.getX(), stage.getY(),
                    stage.getWidth(), stage.getHeight(),
                    stage.isMaximized()
                );
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "창 설정 저장 실패", e);
        }
    }

    /**
     * UI 상태 주기적 업데이트
     */
    private void updateUIStatus() {
        try {
            // 현재 작업 상태 확인
            Task<Void> task = currentTask.get();
            if (task != null && task.isRunning()) {
                // 작업 진행 중일 때 추가 정보 표시
                String progress = String.format("%.1f%%", task.getProgress() * 100);
                if (task.getMessage() != null && !task.getMessage().isEmpty()) {
                    // 상태 표시 업데이트는 이미 바인딩으로 처리됨
                }
            }
            
            // 오류 상태 체크
            if (lastErrorMessage != null && !lastErrorMessage.equals("")) {
                // 오류 메시지가 있으면 5초 후 클리어
                Timeline clearError = new Timeline(new KeyFrame(Duration.seconds(5), e -> {
                    lastErrorMessage = null;
                }));
                clearError.play();
            }
            
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "UI 상태 업데이트 오류", e);
        }
    }

    /**
     * 암호화 전제조건 검증
     */
    private boolean validateEncryptionPreconditions() {
        if (!keyLoaded) {
            showAlert(Alert.AlertType.WARNING, "키 미로드", 
                "먼저 암호화 키를 생성하거나 로드해주세요.\n\n" +
                "💡 처음 사용하시는 경우:\n" +
                "1. '새 키 생성'을 클릭하여 키를 만드세요\n" +
                "2. 안전한 곳에 키 파일을 보관하세요");
            return false;
        }
        
        if (fileSystemManager.getCurrentDirectory() == null) {
            showAlert(Alert.AlertType.WARNING, "폴더 미선택", 
                "먼저 작업 폴더를 선택해주세요.\n\n" +
                "💡 '폴더 열기' 버튼을 클릭하여 암호화할 파일이 있는 폴더를 선택하세요.");
            return false;
        }
        
        Task<Void> task = currentTask.get();
        if (task != null && task.isRunning()) {
            showAlert(Alert.AlertType.WARNING, "작업 진행 중", 
                "현재 다른 작업이 진행 중입니다.\n\n" +
                "완료를 기다리거나 '취소' 버튼을 클릭하여 중단하세요.");
            return false;
        }
        
        return true;
    }

    /**
     * 키보드 단축키 처리
     */
    private void handleKeyboardShortcuts(javafx.scene.input.KeyEvent event) {
        switch (event.getCode()) {
            case F5:
                if (!event.isControlDown()) {
                    refreshFileListAsync();
                }
                break;
            case DELETE:
                if (event.isShiftDown()) {
                    onSecureDelete();
                }
                break;
            case ESCAPE:
                Task<Void> task = currentTask.get();
                if (task != null && task.isRunning()) {
                    cancelTask();
                }
                break;
            case E:
                if (event.isControlDown()) {
                    onEncrypt();
                }
                break;
            case D:
                if (event.isControlDown()) {
                    onDecrypt();
                }
                break;
            case O:
                if (event.isControlDown()) {
                    onOpenFolder();
                }
                break;
            case N:
                if (event.isControlDown()) {
                    onCreateKey();
                }
                break;
            case L:
                if (event.isControlDown()) {
                    onLoadKey();
                }
                break;
            case A:
                if (event.isControlDown()) {
                    fileTable.getSelectionModel().selectAll();
                }
                break;
        }
    }

    /**
     * 창 닫기 처리
     */
    private void handleWindowClosing() {
        try {
            Task<Void> task = currentTask.get();
            if (task != null && task.isRunning()) {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("⚠️ 작업 진행 중");
                alert.setHeaderText("현재 작업이 진행 중입니다");
                alert.setContentText(
                    "진행 중인 작업이 있습니다.\n\n" +
                    "• 작업을 취소하고 종료하시겠습니까?\n" +
                    "• 아니면 작업 완료를 기다리시겠습니까?\n\n" +
                    "참고: 작업 취소 시 부분 처리된 파일들이 안전하게 정리됩니다."
                );
                
                ButtonType cancelAndExit = new ButtonType("취소 후 종료", ButtonBar.ButtonData.OK_DONE);
                ButtonType waitForCompletion = new ButtonType("완료 대기", ButtonBar.ButtonData.NO);
                ButtonType stayOpen = new ButtonType("계속 사용", ButtonBar.ButtonData.CANCEL_CLOSE);
                alert.getButtonTypes().setAll(cancelAndExit, waitForCompletion, stayOpen);
                
                Optional<ButtonType> result = alert.showAndWait();
                if (result.isPresent()) {
                    if (result.get() == cancelAndExit) {
                        task.cancel(true);
                        performShutdown();
                    } else if (result.get() == waitForCompletion) {
                        // 작업 완료 후 종료
                        task.setOnSucceeded(e -> performShutdown());
                        task.setOnFailed(e -> performShutdown());
                        task.setOnCancelled(e -> performShutdown());
                        
                        showInfo("완료 대기 중", "작업 완료 후 자동으로 종료됩니다.");
                    }
                    // stayOpen인 경우 아무것도 하지 않음
                }
            } else {
                performShutdown();
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "종료 처리 오류", e);
            performShutdown(); // 강제 종료
        }
    }

    /**
     * 실제 종료 수행
     */
    private void performShutdown() {
        if (isShuttingDown.compareAndSet(false, true)) {
            LOGGER.info("프로그램 종료 시작");
            
            try {
                // 타이머들 중지
                stopAllTimers();
                
                // 설정 저장
                saveSettingsSafely();
                saveWindowSettings();
                
                // 파일 시스템 매니저 종료
                fileSystemManager.shutdown();
                
                LOGGER.info("프로그램 정상 종료 완료");
                
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "종료 중 오류 발생", e);
            } finally {
                Platform.exit();
            }
        }
    }

    /**
     * 모든 타이머 중지
     */
    private void stopAllTimers() {
        try {
            if (diskSpaceTimer != null) diskSpaceTimer.stop();
            if (fileListRefreshTimer != null) fileListRefreshTimer.stop();
            if (uiUpdateTimer != null) uiUpdateTimer.stop();
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "타이머 중지 중 오류", e);
        }
    }

    // ==================== 검증 및 확인 메서드들 ====================

    /**
     * 선택된 파일들의 암호화 유효성 검사
     */
    private String validateSelectedFilesForEncryption(ObservableList<FileItem> selectedItems) {
        long totalSize = 0;
        int lockedFiles = 0;
        List<String> largeFiles = new ArrayList<>();
        
        for (FileItem item : selectedItems) {
            File file = new File(fileSystemManager.getCurrentDirectory(), item.getName());
            
            if (item.getName().toLowerCase().endsWith(".lock")) {
                lockedFiles++;
                continue;
            }
            
            if (file.exists()) {
                long fileSize = file.length();
                totalSize += fileSize;
                
                // 10GB 이상 파일 체크
                if (fileSize > 10L * 1024 * 1024 * 1024) {
                    largeFiles.add(String.format("%s (%s)", item.getName(), formatFileSize(fileSize)));
                }
            }
        }
        
        if (lockedFiles > 0) {
            return String.format("선택한 항목 중 %d개가 이미 암호화된 파일(.lock)입니다.\n\n" +
                "암호화된 파일은 다시 암호화할 수 없습니다.\n" +
                "대신 복호화를 수행하세요.", lockedFiles);
        }
        
        if (!largeFiles.isEmpty()) {
            return String.format("매우 큰 파일이 포함되어 있습니다:\n\n%s\n\n" +
                "처리 시간이 매우 오래 걸릴 수 있습니다.\n" +
                "계속하시겠습니까?", String.join("\n", largeFiles));
        }
        
        // 디스크 공간 체크
        try {
            checkDiskSpaceForOperation(totalSize * 2); // 2배 여유 공간 필요
        } catch (RuntimeException e) {
            return "디스크 공간이 부족합니다.\n\n" + e.getMessage() + 
                "\n\n해결 방법:\n• 불필요한 파일을 삭제하세요\n• 다른 드라이브에 저장하세요";
        }
        
        return null; // 검증 통과
    }

    /**
     * 키 파일 유효성 검사
     */
    private boolean validateKeyFile(File keyFile) {
        try {
            long fileSize = keyFile.length();
            
            // 키 파일 크기 체크 (대략 48바이트 정도 예상)
            if (fileSize < 30 || fileSize > 1000) {
                Alert warning = new Alert(Alert.AlertType.WARNING);
                warning.setTitle("⚠️ 키 파일 검증");
                warning.setHeaderText("선택한 파일이 올바른 키 파일이 아닐 수 있습니다");
                warning.setContentText(String.format(
                    "파일 크기: %d 바이트\n\n" +
                    "올바른 키 파일은 보통 48바이트 정도입니다.\n" +
                    "계속하시겠습니까?", fileSize
                ));
                
                Optional<ButtonType> result = warning.showAndWait();
                return result.isPresent() && result.get() == ButtonType.OK;
            }
            
            // 파일 읽기 권한 체크
            if (!keyFile.canRead()) {
                showAlert(Alert.AlertType.ERROR, "키 파일 오류", 
                    "키 파일 읽기 권한이 없습니다.\n\n" +
                    "해결 방법:\n• 파일 권한을 확인하세요\n• 관리자 권한으로 실행해 보세요");
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "키 파일 검증 오류", e);
            showAlert(Alert.AlertType.ERROR, "키 파일 오류", 
                "키 파일을 검증할 수 없습니다: " + e.getMessage());
            return false;
        }
    }

    /**
     * 디스크 공간 체크
     */
    private void checkDiskSpaceForOperation(long requiredBytes) {
        try {
            File currentDir = fileSystemManager.getCurrentDirectory();
            if (currentDir == null) return;
            
            Path path = currentDir.toPath();
            java.nio.file.FileStore store = Files.getFileStore(path);
            
            long usableSpace = store.getUsableSpace();
            long totalSpace = store.getTotalSpace();
            
            // 최소 여유 공간 계산 (10% 또는 1GB 중 큰 값)
            long minFreeSpace = Math.max(totalSpace / 10, 1024L * 1024 * 1024);
            long totalRequired = requiredBytes + minFreeSpace;
            
            if (usableSpace < totalRequired) {
                throw new RuntimeException(String.format(
                    "디스크 공간이 부족합니다.\n\n" +
                    "필요한 공간: %s\n" +
                    "여유 공간: %s\n" +
                    "사용 가능: %s\n" +
                    "부족한 공간: %s",
                    formatFileSize(requiredBytes),
                    formatFileSize(minFreeSpace),
                    formatFileSize(usableSpace),
                    formatFileSize(totalRequired - usableSpace)
                ));
            }
        } catch (IOException e) {
            LOGGER.warning("디스크 공간 체크 실패: " + e.getMessage());
        }
    }

    /**
     * 키 작업용 디스크 공간 체크
     */
    private void checkDiskSpaceForKeyOperation(String directory) {
        try {
            Path path = Path.of(directory);
            java.nio.file.FileStore store = Files.getFileStore(path);
            
            long usableSpace = store.getUsableSpace();
            if (usableSpace < 1024 * 1024) { // 1MB 미만
                throw new RuntimeException("키 파일 저장을 위한 디스크 공간이 부족합니다.");
            }
        } catch (IOException e) {
            LOGGER.fine("키 저장 디스크 공간 체크 실패: " + e.getMessage());
        }
    }

    /**
     * 안전 삭제용 디스크 공간 체크
     */
    private void checkDiskSpaceForSecureDelete(long totalFileSize) {
        try {
            // 안전 삭제는 3배 공간이 필요 (랜덤 + 0xFF + 0x00)
            checkDiskSpaceForOperation(totalFileSize * 3);
        } catch (RuntimeException e) {
            throw new RuntimeException("안전 삭제를 위한 " + e.getMessage());
        }
    }

    // ==================== UI 생성 메서드들 ====================

    /**
     * 기본 정보 탭 내용 생성
     */
    private ScrollPane createBasicInfoContent() {
        VBox content = new VBox(15);
        content.setPadding(new javafx.geometry.Insets(20));
        
        content.getChildren().addAll(
            createInfoSection("🔐 프로그램 정보", 
                "• 이름: PASSCODE\n" +
                "• 버전: " + ModernEncryptionApp.getVersion() + "\n" +
                "• 개발자: DDLATTE\n" +
                "• 목적: 안전한 파일 암호화 및 복호화"
            ),
            
            createInfoSection("🛡️ 보안 사양",
                "• 암호화: AES-256-GCM (인증 포함)\n" +
                "• 키 유도: PBKDF2-HMAC-SHA256 (120,000회 반복)\n" +
                "• 솔트: 256비트 보안 랜덤\n" +
                "• IV: 96비트 GCM 표준\n" +
                "• 인증 태그: 128비트"
            ),
            
            createInfoSection("⚡ 성능 특징",
                "• NIO.2 기반 고속 I/O\n" +
                "• 멀티코어 병렬 처리\n" +
                "• 메모리 맵 파일 지원 (16MB+)\n" +
                "• 동적 버퍼 크기 최적화\n" +
                "• 실시간 메모리 관리"
            )
        );
        
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(400);
        return scrollPane;
    }

    /**
     * 사용법 탭 내용 생성
     */
    private ScrollPane createUsageContent() {
        VBox content = new VBox(15);
        content.setPadding(new javafx.geometry.Insets(20));
        
        content.getChildren().addAll(
            createInfoSection("📝 기본 사용법",
                "1️⃣ 폴더 선택\n" +
                "   • '폴더 열기'로 작업할 폴더 선택\n\n" +
                "2️⃣ 키 준비\n" +
                "   • 신규: '새 키 생성'으로 키 생성\n" +
                "   • 기존: '키 로드'로 기존 키 불러오기\n\n" +
                "3️⃣ 파일 처리\n" +
                "   • 파일 선택 후 '암호화' 또는 '복호화'\n" +
                "   • 진행 상황 실시간 모니터링"
            ),
            
            createInfoSection("🎯 고급 기능",
                "• Shift + Delete: 안전 삭제 (3단계 덮어쓰기)\n" +
                "• F5: 파일 목록 새로고침\n" +
                "• Ctrl + A: 모든 파일 선택\n" +
                "• Ctrl + E: 빠른 암호화\n" +
                "• Ctrl + D: 빠른 복호화\n" +
                "• ESC: 작업 취소"
            ),
            
            createInfoSection("⚠️ 주의사항",
                "🔑 키 관리\n" +
                "   • 키 파일과 비밀번호를 분실하면 복구 불가능\n" +
                "   • 안전한 곳에 백업 보관 필수\n\n" +
                "💾 백업 권장\n" +
                "   • 중요 데이터는 사전 백업\n" +
                "   • 네트워크 드라이브 사용 시 주의\n\n" +
                "🚀 성능 최적화\n" +
                "   • 충분한 디스크 공간 확보\n" +
                "   • 대용량 파일은 시간 여유를 두고 처리"
            )
        );
        
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(400);
        return scrollPane;
    }

    /**
     * 기술 정보 탭 내용 생성
     */
    private ScrollPane createTechnicalContent() {
        VBox content = new VBox(15);
        content.setPadding(new javafx.geometry.Insets(20));
        
        // 시스템 정보 수집
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        
        content.getChildren().addAll(
            createInfoSection("🏗️ 아키텍처",
                "• UI: JavaFX (반응형 디자인)\n" +
                "• I/O: NIO.2 (FileChannel + MappedByteBuffer)\n" +
                "• 암호화: JCA (Java Cryptography Architecture)\n" +
                "• 병렬 처리: ThreadPoolExecutor\n" +
                "• 메모리 관리: 동적 버퍼 + GC 최적화"
            ),
            
            createInfoSection("🔧 현재 시스템",
                String.format(
                    "• Java 버전: %s\n" +
                    "• OS: %s %s\n" +
                    "• CPU 코어: %d개\n" +
                    "• 최대 메모리: %s\n" +
                    "• 사용 중 메모리: %s\n" +
                    "• 여유 메모리: %s",
                    System.getProperty("java.version"),
                    System.getProperty("os.name"),
                    System.getProperty("os.version"),
                    Runtime.getRuntime().availableProcessors(),
                    formatFileSize(maxMemory),
                    formatFileSize(totalMemory - freeMemory),
                    formatFileSize(freeMemory)
                )
            ),
            
            createInfoSection("📊 성능 메트릭",
                "• 취소 응답성: 1초 이내\n" +
                "• UI 업데이트: 0.5초마다\n" +
                "• 메모리 체크: 2초마다\n" +
                "• 디스크 모니터링: 5초마다\n" +
                "• 자동 새로고침: 30초마다\n" +
                "• 최적 버퍼 크기: 동적 계산"
            )
        );
        
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(400);
        return scrollPane;
    }

    /**
     * 시스템 정보 탭 내용 생성
     */
    private ScrollPane createSystemInfoContent() {
        VBox content = new VBox(15);
        content.setPadding(new javafx.geometry.Insets(20));
        
        // 디스크 정보 수집
        String diskInfo = "확인 중...";
        if (fileSystemManager.getCurrentDirectory() != null) {
            try {
                Path path = fileSystemManager.getCurrentDirectory().toPath();
                java.nio.file.FileStore store = Files.getFileStore(path);
                long total = store.getTotalSpace();
                long usable = store.getUsableSpace();
                double usage = ((double)(total - usable) / total) * 100;
                
                diskInfo = String.format(
                    "• 총 용량: %s\n• 사용 가능: %s\n• 사용률: %.1f%%",
                    formatFileSize(total), formatFileSize(usable), usage
                );
            } catch (IOException e) {
                diskInfo = "디스크 정보를 가져올 수 없습니다";
            }
        }
        
        content.getChildren().addAll(
            createInfoSection("💻 하드웨어 정보",
                String.format(
                    "• 프로세서: %s\n" +
                    "• 아키텍처: %s\n" +
                    "• 코어 수: %d개\n" +
                    "• JVM 메모리: %s",
                    System.getProperty("os.arch"),
                    System.getProperty("sun.arch.data.model") + "bit",
                    Runtime.getRuntime().availableProcessors(),
                    formatFileSize(Runtime.getRuntime().maxMemory())
                )
            ),
            
            createInfoSection("💾 디스크 정보", diskInfo),
            
            createInfoSection("🔍 디버그 정보",
                String.format(
                    "• 사용자 디렉터리: %s\n" +
                    "• 임시 디렉터리: %s\n" +
                    "• 현재 작업 디렉터리: %s\n" +
                    "• 파일 구분자: '%s'",
                    System.getProperty("user.home"),
                    System.getProperty("java.io.tmpdir"),
                    System.getProperty("user.dir"),
                    File.separator
                )
            )
        );
        
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(400);
        return scrollPane;
    }

    /**
     * 정보 섹션 생성
     */
    private VBox createInfoSection(String title, String content) {
        VBox section = new VBox(5);
        
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #3b82f6;");
        
        Label contentLabel = new Label(content);
        contentLabel.setWrapText(true);
        contentLabel.setStyle("-fx-font-size: 12px; -fx-padding: 5 0 0 10;");
        
        section.getChildren().addAll(titleLabel, contentLabel);
        return section;
    }

    /**
     * 라이브러리 정보 텍스트 생성
     */
    private String createLibraryInfoText() {
        return "📚 PASSCODE 오픈소스 라이브러리 정보\n" +
               "════════════════════════════════════\n\n" +
               
               "🖥️ JavaFX\n" +
               "├─ 용도: 사용자 인터페이스 프레임워크\n" +
               "├─ 라이선스: Apache License 2.0\n" +
               "├─ 설명: 크로스 플랫폼 GUI 라이브러리\n" +
               "└─ 홈페이지: https://openjfx.io/\n\n" +
               
               "🎨 Ikonli (FontAwesome)\n" +
               "├─ 용도: 벡터 아이콘 표시\n" +
               "├─ 라이선스: Apache License 2.0\n" +
               "├─ 설명: JavaFX용 아이콘 라이브러리\n" +
               "└─ 홈페이지: https://kordamp.org/ikonli/\n\n" +
               
               "🔐 JCA (Java Cryptography Architecture)\n" +
               "├─ 용도: 암호화 및 복호화 엔진\n" +
               "├─ 라이선스: Oracle Binary Code License\n" +
               "├─ 설명: Java 표준 암호화 API\n" +
               "└─ 문서: https://docs.oracle.com/javase/8/docs/technotes/guides/security/crypto/CryptoSpec.html\n\n" +
               
               "🔤 Noto Sans KR\n" +
               "├─ 용도: 한글 폰트\n" +
               "├─ 라이선스: SIL Open Font License 1.1\n" +
               "├─ 설명: Google의 무료 한글 웹폰트\n" +
               "└─ 홈페이지: https://fonts.google.com/noto\n\n" +
               
               "⚖️ 라이선스 준수 사항\n" +
               "═══════════════════\n" +
               "• 모든 라이브러리는 해당 라이선스 조건에 따라 사용됨\n" +
               "• Apache License 2.0: 상업적 사용, 수정, 배포 허용\n" +
               "• SIL OFL 1.1: 폰트 사용 및 수정 허용 (판매는 불가)\n" +
               "• Oracle BCL: Java 런타임 환경에서 사용 허용\n\n" +
               
               "📄 전체 라이선스 텍스트는 각 라이브러리의\n" +
               "공식 웹사이트에서 확인할 수 있습니다.\n\n" +
               
               "🙏 감사 인사\n" +
               "═════════\n" +
               "PASSCODE 개발을 가능하게 해준 모든 오픈소스\n" +
               "개발자들과 커뮤니티에 깊은 감사를 드립니다.";
    }

    // ==================== 확인 다이얼로그 메서드들 ====================

    /**
     * 고급 확인 다이얼로그
     */
    private boolean showAdvancedConfirmationDialog(String title, String message) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(title);
        confirm.setHeaderText(null);
        confirm.setContentText(message);
        
        // 커스텀 버튼
        ButtonType proceedButton = new ButtonType("✅ 계속 진행", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("❌ 취소", ButtonBar.ButtonData.CANCEL_CLOSE);
        confirm.getButtonTypes().setAll(proceedButton, cancelButton);
        
        // 진행 버튼 스타일링
        confirm.getDialogPane().lookupButton(proceedButton).setStyle(
            "-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-weight: bold;");
        
        Optional<ButtonType> result = confirm.showAndWait();
        return result.isPresent() && result.get() == proceedButton;
    }

    /**
     * 암호화 확인 메시지 생성
     */
    private String createEncryptionConfirmationMessage(ObservableList<FileItem> selectedItems) {
        long totalSize = calculateTotalSize(selectedItems);
        int fileCount = selectedItems.size();
        
        StringBuilder message = new StringBuilder();
        message.append(String.format("선택한 %d개 항목을 암호화합니다.\n\n", fileCount));
        
        message.append("📊 작업 정보:\n");
        message.append(String.format("• 총 크기: %s\n", formatFileSize(totalSize)));
        message.append(String.format("• 청크 크기: %s\n", chunkSizeCombo.getValue()));
        message.append(String.format("• 예상 시간: %s\n", estimateProcessingTime(totalSize)));
        
        message.append("\n🔐 암호화 사양:\n");
        message.append("• 알고리즘: AES-256-GCM\n");
        message.append("• 키 유도: PBKDF2 (120,000회)\n");
        message.append("• 인증: 128비트 태그\n");
        
        message.append("\n⚠️ 중요 안내:\n");
        message.append("• 원본 파일이 .lock 파일로 대체됩니다\n");
        message.append("• 키 파일과 비밀번호 분실 시 복구 불가능\n");
        message.append("• 처리 중 컴퓨터를 끄지 마세요\n");
        
        if (totalSize > 1024 * 1024 * 1024) { // 1GB 이상
            message.append("\n🕒 대용량 파일 주의:\n");
            message.append("• 처리 시간이 오래 걸릴 수 있습니다\n");
            message.append("• 충분한 디스크 공간을 확보하세요\n");
        }
        
        return message.toString();
    }

    /**
     * 복호화 확인 메시지 생성
     */
    private String createDecryptionConfirmationMessage(ObservableList<FileItem> encryptedFiles) {
        long totalSize = calculateTotalSize(encryptedFiles);
        int fileCount = encryptedFiles.size();
        
        StringBuilder message = new StringBuilder();
        message.append(String.format("선택한 %d개 암호화 파일을 복호화합니다.\n\n", fileCount));
        
        message.append("📊 작업 정보:\n");
        message.append(String.format("• 총 크기: %s\n", formatFileSize(totalSize)));
        message.append(String.format("• 예상 시간: %s\n", estimateProcessingTime(totalSize)));
        
        message.append("\n🔓 복호화 과정:\n");
        message.append("• 키와 비밀번호 검증\n");
        message.append("• GCM 인증 태그 확인\n");
        message.append("• 데이터 무결성 검증\n");
        message.append("• 원본 파일 복원\n");
        
        message.append("\n✅ 복호화 후:\n");
        message.append("• .lock 파일이 원본 파일로 복원됩니다\n");
        message.append("• 암호화 파일은 자동 삭제됩니다\n");
        message.append("• 무결성이 검증된 안전한 파일을 얻습니다\n");
        
        return message.toString();
    }

    /**
     * 처리 시간 추정
     */
    private String estimateProcessingTime(long totalSize) {
        // 대략적인 처리 속도 추정 (MB/s)
        double speedMBps;
        if (totalSize > 1024 * 1024 * 1024) { // 1GB 이상
            speedMBps = 50; // 50 MB/s (대용량 파일 최적화)
        } else if (totalSize > 100 * 1024 * 1024) { // 100MB 이상
            speedMBps = 30; // 30 MB/s
        } else {
            speedMBps = 20; // 20 MB/s (작은 파일들)
        }
        
        double totalMB = totalSize / (1024.0 * 1024.0);
        int estimatedSeconds = (int) (totalMB / speedMBps);
        
        if (estimatedSeconds < 10) {
            return "10초 미만";
        } else if (estimatedSeconds < 60) {
            return String.format("약 %d초", estimatedSeconds);
        } else if (estimatedSeconds < 3600) {
            int minutes = estimatedSeconds / 60;
            return String.format("약 %d분", minutes);
        } else {
            int hours = estimatedSeconds / 3600;
            int minutes = (estimatedSeconds % 3600) / 60;
            return String.format("약 %d시간 %d분", hours, minutes);
        }
    }

    // ==================== 유틸리티 메서드들 ====================

    /**
     * 사용자 친화적 오류 메시지 생성
     */
    private String makeErrorMessageUserFriendly(String originalMessage) {
        if (originalMessage == null) {
            return "알 수 없는 오류가 발생했습니다.";
        }
        
        String lowerMsg = originalMessage.toLowerCase();
        
        if (lowerMsg.contains("password") || lowerMsg.contains("패스워드")) {
            return "🔑 비밀번호 오류\n\n" +
                   "잘못된 비밀번호이거나 손상된 키 파일입니다.\n\n" +
                   "해결 방법:\n" +
                   "• 비밀번호를 정확히 입력했는지 확인\n" +
                   "• 키 파일이 손상되지 않았는지 확인\n" +
                   "• Caps Lock이 켜져 있지 않은지 확인";
        }
        
        if (lowerMsg.contains("space") || lowerMsg.contains("공간")) {
            return "💾 디스크 공간 부족\n\n" +
                   "작업을 완료하기에 디스크 공간이 부족합니다.\n\n" +
                   "해결 방법:\n" +
                   "• 불필요한 파일을 삭제하세요\n" +
                   "• 임시 파일을 정리하세요\n" +
                   "• 다른 드라이브를 사용하세요";
        }
        
        if (lowerMsg.contains("memory") || lowerMsg.contains("메모리")) {
            return "🧠 메모리 부족\n\n" +
                   "작업을 완료하기에 메모리가 부족합니다.\n\n" +
                   "해결 방법:\n" +
                   "• 다른 프로그램을 종료하세요\n" +
                   "• 더 작은 청크 크기를 사용하세요\n" +
                   "• 컴퓨터를 재시작하세요";
        }
        
        if (lowerMsg.contains("permission") || lowerMsg.contains("권한")) {
            return "🔒 권한 오류\n\n" +
                   "파일이나 폴더에 대한 접근 권한이 없습니다.\n\n" +
                   "해결 방법:\n" +
                   "• 관리자 권한으로 프로그램을 실행하세요\n" +
                   "• 파일이 다른 프로그램에서 사용 중인지 확인\n" +
                   "• 파일 속성에서 권한을 확인하세요";
        }
        
        if (lowerMsg.contains("interrupted") || lowerMsg.contains("취소")) {
            return "⏸️ 작업 취소됨\n\n" +
                   "사용자 요청에 의해 작업이 안전하게 취소되었습니다.\n\n" +
                   "• 부분 처리된 파일들이 정리되었습니다\n" +
                   "• 원본 파일은 안전하게 보호되었습니다";
        }
        
        // 기본적인 정리
        String friendlyMessage = originalMessage;
        if (friendlyMessage.length() > 200) {
            friendlyMessage = friendlyMessage.substring(0, 200) + "...";
        }
        
        return "❌ 오류가 발생했습니다\n\n" + friendlyMessage + 
               "\n\n💡 문제가 지속되면 프로그램을 재시작해 보세요.";
    }

    /**
     * 총 파일 크기 계산
     */
    private long calculateTotalSize(ObservableList<FileItem> items) {
        return items.stream()
            .mapToLong(item -> {
                File file = new File(fileSystemManager.getCurrentDirectory(), item.getName());
                return file.exists() ? file.length() : 0;
            })
            .sum();
    }

    /**
     * 스마트 초기 디렉터리 가져오기
     */
    private File getSmartInitialDirectory() {
        // 1. 현재 설정된 디렉터리
        File currentDir = fileSystemManager.getCurrentDirectory();
        if (currentDir != null && currentDir.exists()) {
            return currentDir;
        }
        
        // 2. 마지막 키 경로
        String lastKeyPath = settingsManager.getLastKeyPath();
        if (lastKeyPath != null) {
            File keyDir = new File(lastKeyPath);
            if (keyDir.exists()) {
                return keyDir;
            }
        }
        
        // 3. 사용자 홈 디렉터리의 Documents
        File documentsDir = new File(System.getProperty("user.home"), "Documents");
        if (documentsDir.exists()) {
            return documentsDir;
        }
        
        // 4. 사용자 홈 디렉터리
        File homeDir = new File(System.getProperty("user.home"));
        if (homeDir.exists()) {
            return homeDir;
        }
        
        return null;
    }

    /**
     * 고유한 키 파일명 생성
     */
    private String generateKeyFileName() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.format.DateTimeFormatter formatter = 
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
        return "passcode_key_" + now.format(formatter) + ".key";
    }

    /**
     * 키 상태 업데이트
     */
    private void updateKeyStatus(File keyFile, String status) {
        String statusText = String.format("키 상태: %s (%s) ✅", keyFile.getName(), status);
        statusLabel.setText(statusText);
        statusLabel.setStyle("-fx-text-fill: #10b981; -fx-font-weight: bold;");
    }

    /**
     * 고급 파일 정보 표시
     */
    private void showAdvancedFileInfo(FileItem item) {
        try {
            File file = new File(fileSystemManager.getCurrentDirectory(), item.getName());
            if (!file.exists()) return;
            
            Dialog<Void> infoDialog = new Dialog<>();
            infoDialog.setTitle("📄 파일 상세 정보");
            infoDialog.setHeaderText(item.getName());
            
            VBox content = new VBox(10);
            content.setPadding(new javafx.geometry.Insets(20));
            
            // 기본 정보
            content.getChildren().add(createInfoSection("📋 기본 정보",
                String.format(
                    "📁 경로: %s\n" +
                    "📏 크기: %s (%,d 바이트)\n" +
                    "📅 수정일: %s\n" +
                    "🏷️ 유형: %s",
                    file.getAbsolutePath(),
                    formatFileSize(file.length()),
                    file.length(),
                    new java.util.Date(file.lastModified()),
                    file.isDirectory() ? "폴더" : "파일"
                )
            ));
            
            // 권한 정보
            content.getChildren().add(createInfoSection("🔐 권한 정보",
                String.format(
                    "🔓 읽기: %s\n" +
                    "✏️ 쓰기: %s\n" +
                    "⚡ 실행: %s\n" +
                    "📨 숨김: %s",
                    file.canRead() ? "가능" : "불가",
                    file.canWrite() ? "가능" : "불가",
                    file.canExecute() ? "가능" : "불가",
                    file.isHidden() ? "예" : "아니오"
                )
            ));
            
            // 암호화 파일 특별 정보
            if (item.getName().toLowerCase().endsWith(".lock")) {
                content.getChildren().add(createInfoSection("🔒 암호화 정보",
                    "• AES-256-GCM 암호화된 파일\n" +
                    "• 올바른 키와 비밀번호로 복호화 가능\n" +
                    "• 무결성 검증 포함\n" +
                    "• 복호화 시 원본 파일명으로 복원됨"
                ));
            }
            
            // 처리 추천사항
            if (file.length() > 100 * 1024 * 1024) { // 100MB 이상
                content.getChildren().add(createInfoSection("💡 처리 권장사항",
                    "• 대용량 파일로 처리 시간이 오래 걸릴 수 있습니다\n" +
                    "• 충분한 디스크 공간을 확보하세요\n" +
                    "• 더 큰 청크 크기를 사용하면 빨라집니다\n" +
                    "• 처리 중 다른 무거운 프로그램 실행을 피하세요"
                ));
            }
            
            ScrollPane scrollPane = new ScrollPane(content);
            scrollPane.setFitToWidth(true);
            scrollPane.setPrefSize(500, 400);
            
            infoDialog.getDialogPane().setContent(scrollPane);
            infoDialog.getDialogPane().getButtonTypes().add(ButtonType.OK);
            infoDialog.showAndWait();
            
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
    private void safeSetButtonIcon(Button button, String iconLiteral, String text) {
        try {
            if (button != null) {
                HBox container = new HBox(5);
                container.setAlignment(javafx.geometry.Pos.CENTER);
                
                FontIcon icon = new FontIcon(iconLiteral);
                icon.setIconSize(14);
                
                Label label = new Label(text);
                label.setStyle("-fx-font-size: 12px;");
                
                container.getChildren().addAll(icon, label);
                button.setGraphic(container);
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "버튼 아이콘 설정 실패: " + iconLiteral, e);
        }
    }

    /**
     * 고급 툴팁 설정
     */
    private void setAdvancedTooltip(Control control, String title, String description) {
        try {
            if (control != null) {
                Tooltip tooltip = new Tooltip(title + "\n\n" + description);
                tooltip.setShowDelay(javafx.util.Duration.millis(300));
                tooltip.setHideDelay(javafx.util.Duration.seconds(10));
                tooltip.setMaxWidth(400);
                tooltip.setWrapText(true);
                tooltip.setStyle("-fx-font-size: 12px; -fx-padding: 8px;");
                control.setTooltip(tooltip);
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "툴팁 설정 실패", e);
        }
    }

    /**
     * 성공 알림 표시
     */
    private void showSuccess(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("✅ " + title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    /**
     * 정보 알림 표시
     */
    private void showInfo(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("ℹ️ " + title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
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
                alert.getDialogPane().setPrefWidth(500);
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
            alert.setTitle("💥 " + title);
            alert.setHeaderText("치명적 오류 발생");
            alert.setContentText(content);
            alert.getDialogPane().setPrefWidth(600);
            alert.showAndWait();
            
            performShutdown();
        });
    }

    /**
     * 파일 크기 포맷팅
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    /**
     * 완전한 리소스 정리
     */
    public void shutdown() {
        performShutdown();
    }

    // ==================== 내부 클래스들 ====================

    /**
     * 고급 패스워드 입력 다이얼로그
     */
    private static class AdvancedPasswordDialog extends Dialog<String> {
        public AdvancedPasswordDialog(String title, String message, boolean isNewPassword) {
            setTitle(title);
            setHeaderText(message);
            
            ButtonType okButton = new ButtonType("확인", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelButton = new ButtonType("취소", ButtonBar.ButtonData.CANCEL_CLOSE);
            getDialogPane().getButtonTypes().addAll(okButton, cancelButton);
            
            VBox content = new VBox(15);
            content.setPadding(new javafx.geometry.Insets(20));
            
            // 패스워드 필드
            PasswordField passwordField = new PasswordField();
            passwordField.setPromptText("비밀번호를 입력하세요");
            passwordField.setPrefWidth(300);
            
            // 확인 패스워드 필드 (새 패스워드인 경우)
            PasswordField confirmField = isNewPassword ? new PasswordField() : null;
            if (confirmField != null) {
                confirmField.setPromptText("비밀번호를 다시 입력하세요");
                confirmField.setPrefWidth(300);
            }
            
            // 강도 표시 라벨
            Label strengthLabel = new Label();
            strengthLabel.setStyle("-fx-font-size: 11px;");
            
            // 경고 라벨
            Label warningLabel = new Label("⚠️ 비밀번호는 8자 이상이어야 합니다");
            warningLabel.setStyle("-fx-text-fill: #d97706; -fx-font-size: 11px;");
            
            content.getChildren().addAll(passwordField, strengthLabel, warningLabel);
            if (confirmField != null) {
                Label confirmLabel = new Label("확인:");
                confirmLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #374151;");
                content.getChildren().addAll(confirmLabel, confirmField);
            }
            
            // 보안 팁
            Label tipLabel = new Label(
                "💡 보안 팁:\n" +
                "• 대소문자, 숫자, 특수문자를 조합하세요\n" +
                "• 개인정보와 관련 없는 문구를 사용하세요\n" +
                "• 다른 사이트와 다른 비밀번호를 사용하세요"
            );
            tipLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #6b7280; -fx-padding: 10 0 0 0;");
            content.getChildren().add(tipLabel);
            
            getDialogPane().setContent(content);
            
            // 확인 버튼 초기 비활성화
            Button okBtn = (Button) getDialogPane().lookupButton(okButton);
            okBtn.setDisable(true);
            
            // 패스워드 유효성 검사
            passwordField.textProperty().addListener((obs, oldText, newText) -> {
                boolean isValid = validatePassword(newText, confirmField, strengthLabel, warningLabel);
                okBtn.setDisable(!isValid);
            });
            
            if (confirmField != null) {
                confirmField.textProperty().addListener((obs, oldText, newText) -> {
                    boolean isValid = validatePassword(passwordField.getText(), confirmField, strengthLabel, warningLabel);
                    okBtn.setDisable(!isValid);
                });
            }
            
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
        
        private boolean validatePassword(String password, PasswordField confirmField, 
                                       Label strengthLabel, Label warningLabel) {
            if (password == null || password.isEmpty()) {
                strengthLabel.setText("");
                warningLabel.setText("⚠️ 비밀번호를 입력해주세요");
                warningLabel.setStyle("-fx-text-fill: #d97706; -fx-font-size: 11px;");
                return false;
            }
            
            if (password.length() < 8) {
                strengthLabel.setText("보안 강도: 너무 짧음");
                strengthLabel.setStyle("-fx-text-fill: #dc2626; -fx-font-size: 11px;");
                warningLabel.setText("⚠️ 비밀번호는 8자 이상이어야 합니다");
                warningLabel.setStyle("-fx-text-fill: #d97706; -fx-font-size: 11px;");
                return false;
            }
            
            // 강도 계산
            int strength = calculatePasswordStrength(password);
            String strengthText;
            String strengthColor;
            
            if (strength >= 4) {
                strengthText = "보안 강도: 매우 강함 🛡️";
                strengthColor = "#10b981";
            } else if (strength >= 3) {
                strengthText = "보안 강도: 강함 🔒";
                strengthColor = "#059669";
            } else if (strength >= 2) {
                strengthText = "보안 강도: 보통 🔐";
                strengthColor = "#d97706";
            } else {
                strengthText = "보안 강도: 약함 ⚠️";
                strengthColor = "#dc2626";
            }
            
            strengthLabel.setText(strengthText);
            strengthLabel.setStyle(String.format("-fx-text-fill: %s; -fx-font-size: 11px;", strengthColor));
            
            // 확인 패스워드 체크
            if (confirmField != null) {
                String confirmPassword = confirmField.getText();
                if (!password.equals(confirmPassword)) {
                    warningLabel.setText("⚠️ 비밀번호가 일치하지 않습니다");
                    warningLabel.setStyle("-fx-text-fill: #dc2626; -fx-font-size: 11px;");
                    return false;
                }
            }
            
            warningLabel.setText("✓ 사용 가능한 비밀번호입니다");
            warningLabel.setStyle("-fx-text-fill: #10b981; -fx-font-size: 11px;");
            return true;
        }
        
        private int calculatePasswordStrength(String password) {
            int strength = 0;
            
            if (password.length() >= 12) strength++;
            if (password.matches(".*[a-z].*")) strength++;
            if (password.matches(".*[A-Z].*")) strength++;
            if (password.matches(".*\\d.*")) strength++;
            if (password.matches(".*[!@#$%^&*(),.?\":{}|<>].*")) strength++;
            
            return strength;
        }
    }
}                // 고급 패스워드 입력 다이얼로그
                AdvancedPasswordDialog dialog = new AdvancedPasswordDialog("키 로드", 
                    "키 파일의 비밀번호를 입력하세요\n파일: " + keyFile.getName(), false);
                Optional<String> password = dialog.showAndWait();
                
                if (password.isPresent()) {
                    executeKeyOperationAsync("키 로드", () -> {
                        fileSystemManager.loadKey(keyFile, password.get());
                        
                        Platform.runLater(() -> {
                            keyLoaded = true;
                            updateKeyStatus(keyFile, "로드됨");
                            settingsManager.setLastKeyPath(keyFile.getParent());
                            saveSettingsSafely();
                            
                            // 파일 목록 자동 새로고침 시작
                            fileListRefreshTimer.play();
                            
                            showSuccess("키 로드 완료", 
                                String.format("암호화 키가 성공적으로 로드되었습니다.\n\n" +
                                "📁 파일: %s\n" +
                                "📅 수정일: %s\n" +
                                "✅ 상태: 사용 준비 완료\n\n" +
                                "이제 파일을 암호화하거나 복호화할 수 있습니다.",
                                keyFile.getName(),
                                new java.util.Date(keyFile.lastModified())));
                        });
                    });
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "키 로드 오류", e);
            showAlert(Alert.AlertType.ERROR, "키 로드 실패", 
                "키 로드 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    @FXML
    private void onEncrypt() {
        if (isShuttingDown.get()) return;
        
        if (!validateEncryptionPreconditions()) {
            return;
        }
        
        ObservableList<FileItem> selectedItems = fileTable.getSelectionModel().getSelectedItems();
        if (selectedItems.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "파일 미선택", 
                "암호화할 파일을 선택해주세요.\n\n💡 팁: Ctrl+A로 모든 파일을 선택할 수 있습니다.");
            return;
        }
        
        // 사전 검증
        String validationResult = validateSelectedFilesForEncryption(selectedItems);
        if (validationResult != null) {
            showAlert(Alert.AlertType.WARNING, "암호화 불가", validationResult);
            return;
        }
        
        // 고급 확인 다이얼로그
        if (!showAdvancedConfirmationDialog("🔐 암호화 확인", createEncryptionConfirmationMessage(selectedItems))) {
            return;
        }
        
        try {
            Task<Void> encryptionTask = fileSystemManager.createEncryptionTask(
                selectedItems, chunkSizeCombo.getValue(), fileItems, fileTable
            );
            startTaskWithFullMonitoring(encryptionTask, "암호화");
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "암호화 작업 시작 실패", e);
            showAlert(Alert.AlertType.ERROR, "암호화 실패", 
                "암호화 작업을 시작할 수 없습니다.\n\n오류: " + e.getMessage() + 
                "\n\n해결 방법:\n• 파일이 다른 프로그램에서 사용 중인지 확인\n• 디스크 공간이 충분한지 확인\n• 관리자 권한으로 실행");
        }
    }

    @FXML
    private void onDecrypt() {
        if (isShuttingDown.get()) return;
        
        if (!keyLoaded) {
            showAlert(Alert.AlertType.WARNING, "키 미로드", 
                "먼저 암호화 키를 로드해주세요.\n\n💡 키가 없다면 '키 생성'을 먼저 수행하세요.");
            return;
        }
        
        ObservableList<FileItem> selectedItems = fileTable.getSelectionModel().getSelectedItems();
        ObservableList<FileItem> encryptedFiles = selectedItems.filtered(item ->
                item.getName().toLowerCase().endsWith(".lock"));
        
        if (encryptedFiles.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "암호화 파일 미선택", 
                "복호화할 암호화 파일(.lock)을 선택해주세요.\n\n" +
                "💡 팁: .lock 확장자가 있는 파일만 복호화할 수 있습니다.");
            return;
        }
        
        // 고급 확인 다이얼로그
        if (!showAdvancedConfirmationDialog("🔓 복호화 확인", createDecryptionConfirmationMessage(encryptedFiles))) {
            return;
        }
        
        try {
            Task<Void> decryptionTask = fileSystemManager.createDecryptionTask(encryptedFiles, fileItems, fileTable);
            startTaskWithFullMonitoring(decryptionTask, "복호화");
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "복호화 작업 시작 실패", e);
            showAlert(Alert.AlertType.ERROR, "복호화 실패", 
                "복호화 작업을 시작할 수 없습니다: " + e.getMessage());
        }
    }

    @FXML
    private void onSecureDelete() {
        if (isShuttingDown.get()) return;
        
        ObservableList<FileItem> selectedItems = fileTable.getSelectionModel().getSelectedItems();
        if (selectedItems.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "파일 미선택", 
                "안전 삭제할 파일을 선택해주세요.");
            return;
        }
        
        // 극강 경고 다이얼로그
        Alert confirmAlert = new Alert(Alert.AlertType.WARNING);
        confirmAlert.setTitle("⚠️ 안전 삭제 - 최종 확인");
        confirmAlert.setHeaderText("🚨 돌이킬 수 없는 작업입니다!");
        
        String warningMessage = String.format(
            "선택한 %d개 파일을 영구적으로 삭제합니다.\n\n" +
            "🔥 안전 삭제 프로세스:\n" +
            "• 1단계: 랜덤 데이터로 덮어쓰기\n" +
            "• 2단계: 0xFF 패턴으로 덮어쓰기\n" +
            "• 3단계: 0x00 패턴으로 덮어쓰기\n" +
            "• 4단계: 파일 시스템에서 제거\n\n" +
            "⚠️ 주의사항:\n" +
            "• 이 작업은 되돌릴 수 없습니다\n" +
            "• 전문 복구 도구로도 복구 불가능합니다\n" +
            "• 처리 시간이 오래 걸릴 수 있습니다\n" +
            "• 디스크 공간을 3배 이상 사용합니다\n\n" +
            "정말로 계속하시겠습니까?", 
            selectedItems.size()
        );
        
        confirmAlert.setContentText(warningMessage);
        
        ButtonType deleteButton = new ButtonType("🗑️ 영구 삭제", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("❌ 취소", ButtonBar.ButtonData.CANCEL_CLOSE);
        confirmAlert.getButtonTypes().setAll(deleteButton, cancelButton);
        
        // 삭제 버튼을 빨간색으로 강조
        confirmAlert.getDialogPane().lookupButton(deleteButton).setStyle(
            "-fx-background-color: #dc2626; -fx-text-fill: white; -fx-font-weight: bold;");
        
        Optional<ButtonType> result = confirmAlert.showAndWait();
        if (result.isPresent() && result.get() == deleteButton) {
            try {
                // 디스크 공간 사전 체크
                long totalSize = calculateTotalSize(selectedItems);
                checkDiskSpaceForSecureDelete(totalSize);
                
                fileSystemManager.secureDeleteFiles(selectedItems, fileItems, fileTable, itemCountLabel);
                LOGGER.info("안전 삭제 작업 시작: " + selectedItems.size() + "개 파일");
                
                showInfo("안전 삭제 시작됨", 
                    String.format("선택한 %d개 파일의 안전 삭제가 시작되었습니다.\n\n" +
                    "진행 상황은 테이블에서 확인할 수 있습니다.", selectedItems.size()));
                    
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "안전 삭제 시작 실패", e);
                showAlert(Alert.AlertType.ERROR, "삭제 실패", 
                    "파일 삭제를 시작할 수 없습니다: " + e.getMessage());
            }
        }
    }

    @FXML
    private void cancelTask() {
        Task<Void> task = currentTask.get();
        if (task != null && task.isRunning()) {
            LOGGER.info("사용자가 작업 취소 요청");
            
            Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
            confirmAlert.setTitle("⏸️ 작업 취소");
            confirmAlert.setHeaderText("현재 작업을 취소하시겠습니까?");
            confirmAlert.setContentText(
                "진행 중인 작업이 안전하게 중단됩니다.\n\n" +
                "• 부분 처리된 파일들이 자동으로 정리됩니다\n" +
                "• 원본 파일은 안전하게 보호됩니다\n" +
                "• 취소 완료까지 몇 초 소요될 수 있습니다"
            );
            
            Optional<ButtonType> result = confirmAlert.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                boolean cancelled = task.cancel(true);
                
                // 취소 완료 알림
                Timeline cancelNotification = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
                    Platform.runLater(() -> {
                        if (cancelled) {
                            showInfo("작업 취소됨", "작업이 안전하게 취소되었습니다.");
                        } else {
                            showInfo("취소 처리 중", "작업이 곧 완료되거나 취소될 예정입니다.");
                        }
                    });
                }));
                cancelNotification.play();
            }
        }
    }

    @FXML
    private void onExit() {
        handleWindowClosing();
    }

    @FXML
    private void toggleTheme() {
        if (isShuttingDown.get()) return;
        
        try {
            Scene scene = fileTable.getScene();
            if (scene != null) {
                boolean isDarkMode = settingsManager.toggleTheme(scene);
                saveSettingsSafely();
                
                // 테마 변경 알림
                showInfo("테마 변경됨", 
                    (isDarkMode ? "🌙 다크 모드" : "☀️ 라이트 모드") + "로 변경되었습니다.");
                
                LOGGER.info("테마 변경: " + (isDarkMode ? "다크" : "라이트") + " 모드");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "테마 변경 오류", e);
            showAlert(Alert.AlertType.WARNING, "테마 변경 실패", 
                "테마 변경 중 오류가 발생했습니다.");
        }
    }

    @FXML
    private void showInfo() {
        if (isShuttingDown.get()) return;
        
        try {
            Dialog<Void> infoDialog = new Dialog<>();
            infoDialog.setTitle("📖 PASSCODE 정보");
            infoDialog.setHeaderText("🔐 PASSCODE v" + ModernEncryptionApp.getVersion());
            
            // 탭 패널로 정보 구성
            TabPane tabPane = new TabPane();
            
            // 기본 정보 탭
            Tab basicTab = new Tab("🏠 기본 정보");
            basicTab.setClosable(false);
            basicTab.setContent(createBasicInfoContent());
            
            // 사용법 탭
            Tab usageTab = new Tab("📚 사용법");
            usageTab.setClosable(false);
            usageTab.setContent(createUsageContent());
            
            // 기술 정보 탭
            Tab techTab = new Tab("🔧 기술 정보");
            techTab.setClosable(false);
            techTab.setContent(createTechnicalContent());
            
            // 시스템 정보 탭
            Tab systemTab = new Tab("💻 시스템 정보");
            systemTab.setClosable(false);
            systemTab.setContent(createSystemInfoContent());
            
            tabPane.getTabs().addAll(basicTab, usageTab, techTab, systemTab);
            
            infoDialog.getDialogPane().setContent(tabPane);
            infoDialog.getDialogPane().setPrefSize(600, 500);
            infoDialog.getDialogPane().getButtonTypes().add(ButtonType.OK);
            
            infoDialog.showAndWait();
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "정보 다이얼로그 표시 오류", e);
        }
    }

    @FXML
    private void showLibrary() {
        if (isShuttingDown.get()) return;
        
        try {
            Dialog<Void> libraryDialog = new Dialog<>();
            libraryDialog.setTitle("📚 사용된 라이브러리");
            libraryDialog.setHeaderText("PASSCODE 오픈소스 라이브러리 정보");
            
            TextArea content = new TextArea();
            content.setEditable(false);
            content.setWrapText(true);
            content.setPrefSize(500, 400);
            content.setText(createLibraryInfoText());
            
            // 스타일링
            content.setStyle("-fx-font-family: 'Consolas', 'Monaco', monospace; -fx-font-size: 12px;");
            
            libraryDialog.getDialogPane().setContent(content);
            libraryDialog.getDialogPane().getButtonTypes().add(ButtonType.OK);
            libraryDialog.showAndWait();
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "라이브러리 다이얼로그 표시 오류", e);
        }
    }

    // ==================== 비동기 작업 메서드들 ====================

    /**
     * 비동기 파일 목록 새로고침
     */
    private void refreshFileListAsync() {
        if (fileSystemManager.getCurrentDirectory() == null) {
            showAlert(Alert.AlertType.WARNING, "폴더 미선택", 
                "먼저 작업 폴더를 선택해주세요.");
            return;
        }
        
        // 로딩 상태 표시
        Platform.runLater(() -> {
            itemCountLabel.setText("항목: 로딩 중...");
            if (loadingIndicator != null) {
                loadingIndicator.setVisible(true);
            }
        });
        
        fileSystemManager.updateFileList(fileItems, itemCountLabel);
        
        // 로딩 완료 후 인디케이터 숨기기
        Timeline hideLoader = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            if (loadingIndicator != null) {
                loadingIndicator.setVisible(false);
            }
        }));
        hideLoader.play();
    }

    /**
     * 조용한 파일 목록 새로고침 (자동 새로고침용)
     */
    private void refreshFileListSilently() {
        if (fileSystemManager.getCurrentDirectory() != null) {
            fileSystemManager.updateFileList(fileItems, itemCountLabel);
        }
    }

    /**
     * 비동기 디렉터리 설정
     */
    private void setCurrentDirectoryAsync(File directory) {
        Task<Void> setDirTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                updateMessage("폴더 분석 중: " + directory.getName());
                
                // 디렉터리 유효성 검사
                if (!directory.exists()) {
                    throw new IOException("선택한 폴더가 존재하지 않습니다.");
                }
                if (!directory.canRead()) {
                    throw new IOException("폴더 읽기 권한이 없습니다.");
                }
                
                // 파일 개수 사전 체크 (너무 많으면 경고)
                File[] files = directory.listFiles();
                if (files != null && files.length > 10000) {
                    Platform.runLater(() -> {
                        Alert warning = new Alert(Alert.AlertType.WARNING);
                        warning.setTitle("대용량 폴더 경고");
                        warning.setHeaderText("선택한 폴더에 많은 파일이 있습니다");
                        warning.setContentText(String.format("파일 개수: %,d개\n\n" +
                            "처리 시간이 오래 걸릴 수 있습니다.\n계속하시겠습니까?", files.length));
                        
                        Optional<ButtonType> result = warning.showAndWait();
                        if (result.isEmpty() || result.get() != ButtonType.OK) {
                            cancel();
                        }
                    });
                }
                
                if (isCancelled()) {
                    return null;
                }
                
                fileSystemManager.setCurrentDirectory(directory);
                return null;
            }
        };
        
        setDirTask.setOnSucceeded(e -> {
            refreshFileListAsync();
            saveSettingsSafely();
            
            // 자동 새로고침 활성화
            fileListRefreshTimer.play();
            
            // 성공 알림
            Platform.runLater(() -> {
                showSuccess("폴더 선택됨", 
                    String.format("작업 폴더가 설정되었습니다.\n\n📁 경로: %s", 
                    directory.getAbsolutePath()));
            });
            
            LOGGER.info("작업 폴더 설정됨: " + directory.getPath());
        });
        
        setDirTask.setOnFailed(e -> {
            Throwable exception = setDirTask.getException();
            LOGGER.log(Level.WARNING, "폴더 설정 실패", exception);
            
            Platform.runLater(() -> {
                showAlert(Alert.AlertType.ERROR, "폴더 설정 실패", 
                    "선택한 폴더를 설정할 수 없습니다.\n\n" +
                    "오류: " + exception.getMessage() + "\n\n" +
                    "해결 방법:\n• 다른 폴더를 선택해 보세요\n• 폴더 권한을 확인하세요");
            });
        });
        
        Thread setDirThread = new Thread(setDirTask, "SetDirectory");
        setDirThread.setDaemon(true);
        setDirThread.start();
    }

    /**
     * 비동기 디스크 공간 업데이트
     */
    private void updateDiskSpaceAsync() {
        if (diskSpaceLabel == null || fileSystemManager.getCurrentDirectory() == null) {
            return;
        }
        
        File currentDir = fileSystemManager.getCurrentDirectory();
        
        Task<String> diskSpaceTask = new Task<String>() {
            @Override
            protected String call() throws Exception {
                try {
                    Path path = currentDir.toPath();
                    java.nio.file.FileStore store = Files.getFileStore(path);
                    
                    long totalSpace = store.getTotalSpace();
                    long usableSpace = store.getUsableSpace();
                    long usedSpace = totalSpace - usableSpace;
                    
                    double usagePercent = (double) usedSpace / totalSpace * 100;
                    
                    return String.format("디스크: %s / %s (%.1f%% 사용)",
                        formatFileSize(usedSpace),
                        formatFileSize(totalSpace),
                        usagePercent);
                        
                } catch (IOException e) {
                    return "디스크: 확인 불가";
                }
            }
        };
        
        diskSpaceTask.setOnSucceeded(e -> {
            Platform.runLater(() -> {
                if (diskSpaceLabel != null) {
                    diskSpaceLabel.setText(diskSpaceTask.getValue());
                    
                    // 디스크 사용률에 따른 색상 변경
                    String text = diskSpaceTask.getValue();
                    if (text.contains("디스크: 확인 불가")) {
                        diskSpaceLabel.setStyle("-fx-text-fill: #6b7280;");
                    } else {
                        String percentStr = text.substring(text.indexOf("(") + 1, text.indexOf("%"));
                        try {
                            double percent = Double.parseDouble(percentStr);
                            if (percent > 90) {
                                diskSpaceLabel.setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");
                            } else if (percent > 80) {
                                diskSpaceLabel.setStyle("-fx-text-fill: #d97706; -fx-font-weight: bold;");
                            } else {
                                diskSpaceLabel.setStyle("-fx-text-fill: -fx-text-fill;");
                            }
                        } catch (NumberFormatException ex) {
                            diskSpaceLabel.setStyle("-fx-text-fill: -fx-text-fill;");
                        }
                    }
                }
            });
        });
        
        Thread diskSpaceThread = new Thread(diskSpaceTask, "DiskSpace");
        diskSpaceThread.setDaemon(true);
        diskSpaceThread.start();
    }

    /**
     * 비동기 키 작업 실행
     */
    private void executeKeyOperationAsync(String operationName, Runnable operation) {
        Task<Void> keyTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                updateMessage(operationName + " 중...");
                operation.run();
                return null;
            }
        };
        
        keyTask.setOnSucceeded(e -> {
            LOGGER.info(operationName + " 성공");
        });
        
        keyTask.setOnFailed(e -> {
            Throwable exception = keyTask.getException();
            LOGGER.log(Level.SEVERE, operationName + " 실패", exception);
            
            Platform.runLater(() -> {
                String errorMsg = exception.getMessage();
                String userFriendlyMsg = makeErrorMessageUserFriendly(errorMsg);
                
                showAlert(Alert.AlertType.ERROR, operationName + " 실패", userFriendlyMsg);
            });
        });
        
        Thread keyThread = new Thread(keyTask, operationName + "-Thread");
        keyThread.setDaemon(true);
        keyThread.start();
    }

    /**
     * 완전 모니터링이 포함된 작업 시작
     */
    private void startTaskWithFullMonitoring(Task<Void> task, String taskName) {
        currentTask.set(task);
        
        // 진행률 바인딩
        progressBar.progressProperty().bind(task.progressProperty());
        progressLabel.textProperty().bind(task.messageProperty());
        
        // UI 상태 변경
        Platform.runLater(this::showProgressControls);
        
        // 작업 시작 알림
        Platform.runLater(() -> showInfo(taskName + " 시작", 
            taskName + " 작업이 시작되었습니다.\n진행 상황을 모니터링하고 있습니다."));
        
        // 이벤트 핸들러 설정
        task.setOnSucceeded(e -> {
            Platform.runLater(() -> {
                hideProgressControls();
                showSuccess(taskName + " 완료", 
                    taskName + " 작업이 성공적으로 완료되었습니다!");
                
                // 자동 파일 목록 새로고침
                refreshFileListSilently();
            });
            
            LOGGER.info(taskName + " 작업 성공 완료");
        });
        
        task.setOnFailed(e -> {
            Throwable exception = task.getException();
            LOGGER.log(Level.SEVERE, taskName + " 작업 실패", exception);
            
            Platform.runLater(() -> {
                hideProgressControls();
                String userFriendlyMsg = makeErrorMessageUserFriendly(exception.getMessage());
                showAlert(Alert.AlertType.ERROR, taskName + " 실패", userFriendlyMsg);
                
                // 실패 후 자동 파일 목록 새로고침
                refreshFileListSilently();
            });
        });
        
        task.setOnCancelled(e -> {
            Platform.runLater(() -> {
                hideProgressControls();
                showInfo(taskName + " 취소됨", taskName + " 작업이 안전하게 취소되었습니다.");
                
                // 취소 후 자동 파일 목록 새로고침
                refreshFileListSilently();
            });
            
            LOGGER.info(taskName + " 작업 취소됨");
        });
        
        // 작업 시작
        Thread taskThread = new Thread(task, taskName + "-Task");
        taskThread.setDaemon(true);
        taskThread.start();
    }

    // ==================== 유틸리티 메서드들 ====================

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
        currentTask.set(null);
    }

    /**
     * 비동기 설정 로드
     */
    private void loadSettingsAsync() {
        Task<Void> loadTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                updateMessage("설정 로드 중...");
                
                Scene scene = fileTable.getScene();
                settingsManager.loadSettings(
                    chunkSizeCombo,
                    fileSystemManager::setCurrentDirectory,
                    scene
                );
                return null;
            }
        };
        
        loadTask.setOnSucceeded(e -> {
            Platform.runLater(() -> {
                refreshFileListAsync();
                LOGGER.info("설정 로드 완료");
            });
        });
        
        loadTask.setOnFailed(e -> {
            LOGGER.log(Level.WARNING, "설정 로드 실패", loadTask.getException());
        });
        
        Thread loadThread = new Thread(loadTask, "LoadSettings");
        loadThread.setDaemon(true);
        loadThread.start();
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
     * 창 설정 저장
     */
    private void saveWindowSettings() {
        try {
            Stage stage = getStage();
            if (stage != null) {
                settingsManager.saveWindowSettings(
                    stage.getX(), stage.getY(),
                    stage.getWidth(), stage.package com.ddlatte.encryption;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.concurrent.Task;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * 극강 응답성 UI 컨트롤러 - High Priority 개선 완전 적용
 * 
 * 🚀 주요 개선사항:
 * 1. 1초 이내 취소 응답성 보장
 * 2. 모든 UI 작업 완전 비동기화
 * 3. 실시간 디스크 공간 및 메모리 모니터링
 * 4. 사용자 경험 극대화 (프로그레시브 로딩, 스마트 알림)
 * 5. 모든 예외 상황 완벽 처리 및 사용자 친화적 오류 메시지
 */
public class ModernEncryptionController {
    private static final Logger LOGGER = Logger.getLogger(ModernEncryptionController.class.getName());
    
    // 응답성 설정
    private static final int UI_UPDATE_INTERVAL_MS = 500; // 0.5초마다 UI 업데이트
    private static final int DISK_SPACE_CHECK_INTERVAL_S = 5; // 5초마다 디스크 공간 체크
    private static final int FILE_LIST_REFRESH_INTERVAL_S = 30; // 30초마다 자동 새로고침
    
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
    @FXML private Label diskSpaceLabel; // 새로 추가될 디스크 공간 표시 라벨
    @FXML private ProgressIndicator loadingIndicator; // 로딩 인디케이터

    // 비즈니스 로직 관리자들
    private final FileSystemManager fileSystemManager;
    private final SettingsManager settingsManager;
    
    // 극강 응답성을 위한 변수들
    private final ObservableList<FileItem> fileItems;
    private final ReentrantLock uiLock = new ReentrantLock();
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private final AtomicReference<Task<Void>> currentTask = new AtomicReference<>();
    
    // UI 자동 업데이트 타이머들
    private Timeline diskSpaceTimer;
    private Timeline fileListRefreshTimer;
    private Timeline uiUpdateTimer;
    
    private volatile boolean keyLoaded = false;
    private volatile long lastDiskSpaceCheck = 0;
    private volatile String lastErrorMessage = null;

    public ModernEncryptionController() {
        fileSystemManager = new FileSystemManager();
        settingsManager = new SettingsManager();
        fileItems = FXCollections.synchronizedObservableList(FXCollections.observableArrayList());
        
        LOGGER.info("극강 응답성 컨트롤러 초기화됨");
    }

    @FXML
    public void initialize() {
        try {
            if (isInitialized.compareAndSet(false, true)) {
                setupUI();
                setupTableColumns();
                setupChunkSizeCombo();
                setupEventHandlers();
                setupAutoUpdateTimers();
                
                // 메모리 모니터링 시작
                fileSystemManager.startMemoryMonitoring(memoryLabel);
                
                // 설정 로드 (비동기)
                Platform.runLater(this::loadSettingsAsync);
                
                LOGGER.info("극강 응답성 UI 초기화 완료");
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "UI 초기화 실패", e);
            showCriticalErrorAndExit("초기화 오류", 
                "프로그램 초기화에 실패했습니다.\n\n오류 내용:\n" + e.getMessage() + 
                "\n\n해결 방법:\n1. 프로그램을 재시작해 보세요\n2. 관리자 권한으로 실행해 보세요\n3. 백신 소프트웨어를 일시 중지해 보세요");
        }
    }

    /**
     * 극강 응답성 UI 설정
     */
    private void setupUI() {
        // 테이블 설정
        fileTable.setItems(fileItems);
        fileTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        fileTable.setRowFactory(tv -> createSmartTableRow());
        
        // 플레이스홀더 설정 (로딩 상태 포함)
        VBox placeholder = new VBox(10);
        placeholder.setAlignment(javafx.geometry.Pos.CENTER);
        placeholder.getChildren().addAll(
            new FontIcon("fas-folder"),
            new Label("폴더를 선택하여 파일을 표시하세요"),
            createLoadingIndicator()
        );
        fileTable.setPlaceholder(placeholder);
        
        // 버튼 설정
        setupButtonsWithIcons();
        
        // 초기 상태 설정
        hideProgressControls();
        
        // 상태 라벨들 초기화
        memoryLabel.setText("메모리: 초기화 중...");
        itemCountLabel.setText("항목: 준비 중...");
        statusLabel.setText("상태: 키를 로드해주세요");
        if (diskSpaceLabel != null) {
            diskSpaceLabel.setText("디스크: 확인 중...");
        }
        
        // 툴팁 설정
        setupAdvancedTooltips();
        
        // 스타일 클래스 추가
        fileTable.getStyleClass().add("responsive-table");
        progressBar.getStyleClass().add("modern-progress");
    }

    /**
     * 스마트 테이블 행 생성
     */
    private TableRow<FileItem> createSmartTableRow() {
        TableRow<FileItem> row = new TableRow<>();
        
        // 더블클릭 이벤트
        row.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && !row.isEmpty()) {
                showAdvancedFileInfo(row.getItem());
            }
        });
        
        // 컨텍스트 메뉴
        ContextMenu contextMenu = createFileContextMenu();
        row.setContextMenu(contextMenu);
        
        // 드래그 앤 드롭 지원 (향후 확장)
        row.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
            }
            event.consume();
        });
        
        return row;
    }

    /**
     * 파일 컨텍스트 메뉴 생성
     */
    private ContextMenu createFileContextMenu() {
        ContextMenu contextMenu = new ContextMenu();
        
        MenuItem infoItem = new MenuItem("파일 정보");
        infoItem.setGraphic(new FontIcon("fas-info-circle"));
        infoItem.setOnAction(e -> {
            FileItem item = fileTable.getSelectionModel().getSelectedItem();
            if (item != null) showAdvancedFileInfo(item);
        });
        
        MenuItem refreshItem = new MenuItem("목록 새로고침");
        refreshItem.setGraphic(new FontIcon("fas-sync"));
        refreshItem.setOnAction(e -> refreshFileListAsync());
        
        contextMenu.getItems().addAll(infoItem, new SeparatorMenuItem(), refreshItem);
        return contextMenu;
    }

    /**
     * 로딩 인디케이터 생성
     */
    private ProgressIndicator createLoadingIndicator() {
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setMaxSize(30, 30);
        indicator.setVisible(false);
        return indicator;
    }

    /**
     * 아이콘이 있는 버튼 설정
     */
    private void setupButtonsWithIcons() {
        safeSetButtonIcon(encryptButton, "fas-lock", "암호화");
        safeSetButtonIcon(decryptButton, "fas-unlock", "복호화");
        safeSetButtonIcon(cancelButton, "fas-times", "취소");
    }

    /**
     * 고급 툴팁 설정
     */
    private void setupAdvancedTooltips() {
        setAdvancedTooltip(encryptButton, "파일 암호화", 
            "선택한 파일을 AES-256-GCM 알고리즘으로 암호화합니다.\n" +
            "• 원본 파일은 안전하게 삭제됩니다\n" +
            "• .lock 확장자가 추가됩니다\n" +
            "• 키 파일이 필요합니다");
            
        setAdvancedTooltip(decryptButton, "파일 복호화",
            "암호화된 파일을 원본 상태로 복호화합니다.\n" +
            "• .lock 파일만 선택 가능합니다\n" +
            "• 올바른 키와 패스워드가 필요합니다\n" +
            "• 무결성 검증이 자동으로 수행됩니다");
            
        setAdvancedTooltip(chunkSizeCombo, "처리 버퍼 크기",
            "암호화/복호화 시 사용할 메모리 버퍼 크기입니다.\n" +
            "• 큰 값: 빠른 처리 속도, 많은 메모리 사용\n" +
            "• 작은 값: 적은 메모리 사용, 다소 느린 속도\n" +
            "• 시스템 메모리에 따라 자동 조정됩니다");
    }

    /**
     * 자동 업데이트 타이머 설정
     */
    private void setupAutoUpdateTimers() {
        // 디스크 공간 모니터링 (5초마다)
        diskSpaceTimer = new Timeline(new KeyFrame(Duration.seconds(DISK_SPACE_CHECK_INTERVAL_S), e -> {
            if (!isShuttingDown.get()) {
                updateDiskSpaceAsync();
            }
        }));
        diskSpaceTimer.setCycleCount(Timeline.INDEFINITE);
        diskSpaceTimer.play();
        
        // 파일 목록 자동 새로고침 (30초마다)
        fileListRefreshTimer = new Timeline(new KeyFrame(Duration.seconds(FILE_LIST_REFRESH_INTERVAL_S), e -> {
            if (!isShuttingDown.get() && fileSystemManager.getCurrentDirectory() != null) {
                refreshFileListSilently();
            }
        }));
        fileListRefreshTimer.setCycleCount(Timeline.INDEFINITE);
        
        // UI 업데이트 타이머 (0.5초마다)
        uiUpdateTimer = new Timeline(new KeyFrame(Duration.millis(UI_UPDATE_INTERVAL_MS), e -> {
            if (!isShuttingDown.get()) {
                updateUIStatus();
            }
        }));
        uiUpdateTimer.setCycleCount(Timeline.INDEFINITE);
        uiUpdateTimer.play();
    }

    /**
     * 테이블 컬럼 설정 (향상된 버전)
     */
    private void setupTableColumns() {
        fileTable.getColumns().clear();
        
        // 이름 컬럼 (아이콘 포함)
        TableColumn<FileItem, String> nameCol = new TableColumn<>("이름");
        nameCol.setCellValueFactory(data -> data.getValue().nameProperty());
        nameCol.setCellFactory(col -> new TableCell<FileItem, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item);
                    // 파일 유형에 따른 아이콘 설정
                    FileItem fileItem = getTableView().getItems().get(getIndex());
                    if (fileItem != null) {
                        FontIcon icon = getFileIcon(fileItem);
                        setGraphic(icon);
                    }
                }
            }
        });
        nameCol.prefWidthProperty().bind(fileTable.widthProperty().multiply(0.35));
        nameCol.setResizable(true);

        // 유형 컬럼
        TableColumn<FileItem, String> typeCol = new TableColumn<>("유형");
        typeCol.setCellValueFactory(data -> data.getValue().typeProperty());
        typeCol.prefWidthProperty().bind(fileTable.widthProperty().multiply(0.12));

        // 크기 컬럼 (색상 코딩)
        TableColumn<FileItem, String> sizeCol = new TableColumn<>("크기");
        sizeCol.setCellValueFactory(data -> data.getValue().sizeProperty());
        sizeCol.setCellFactory(col -> new TableCell<FileItem, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    // 파일 크기에 따른 색상 설정
                    setStyle(getSizeColorStyle(item));
                }
            }
        });
        sizeCol.prefWidthProperty().bind(fileTable.widthProperty().multiply(0.13));

        // 상태 컬럼 (진행률 바 포함)
        TableColumn<FileItem, String> statusCol = new TableColumn<>("상태");
        statusCol.setCellValueFactory(data -> data.getValue().statusProperty());
        statusCol.setCellFactory(col -> new TableCell<FileItem, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    setStyle(getStatusColorStyle(item));
                }
            }
        });
        statusCol.prefWidthProperty().bind(fileTable.widthProperty().multiply(0.15));

        // 진행률 컬럼 (향상된 진행률 바)
        TableColumn<FileItem, Number> progressCol = new TableColumn<>("진행률");
        progressCol.setCellValueFactory(data -> data.getValue().progressProperty());
        progressCol.setCellFactory(col -> new TableCell<FileItem, Number>() {
            private final ProgressBar progressBar = new ProgressBar(0);
            private final Label progressLabel = new Label();
            private final HBox container = new HBox(5);
            
            {
                progressBar.setPrefWidth(80);
                progressBar.setPrefHeight(12);
                progressLabel.setMinWidth(35);
                container.getChildren().addAll(progressBar, progressLabel);
                container.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            }
            
            @Override
            protected void updateItem(Number progress, boolean empty) {
                super.updateItem(progress, empty);
                if (empty || progress == null) {
                    setGraphic(null);
                } else {
                    double value = progress.doubleValue();
                    progressBar.setProgress(value);
                    progressLabel.setText(String.format("%.0f%%", value * 100));
                    
                    // 진행률에 따른 색상 변경
                    if (value >= 1.0) {
                        progressBar.getStyleClass().removeAll("progress-warning", "progress-error");
                        progressBar.getStyleClass().add("progress-success");
                    } else if (value > 0) {
                        progressBar.getStyleClass().removeAll("progress-success", "progress-error");
                        progressBar.getStyleClass().add("progress-warning");
                    }
                    
                    setGraphic(container);
                }
            }
        });
        progressCol.prefWidthProperty().bind(fileTable.widthProperty().multiply(0.25));

        fileTable.getColumns().addAll(nameCol, typeCol, sizeCol, statusCol, progressCol);
        fileTable.getSortOrder().clear();
        fileTable.sortPolicyProperty().set(t -> false);
    }

    /**
     * 파일 아이콘 가져오기
     */
    private FontIcon getFileIcon(FileItem item) {
        String iconLiteral;
        String iconColor;
        
        if (item.getName().endsWith(".lock")) {
            iconLiteral = "fas-lock";
            iconColor = "#dc2626"; // 빨간색
        } else if (item.typeProperty().get().equals("폴더")) {
            iconLiteral = "fas-folder";
            iconColor = "#3b82f6"; // 파란색
        } else {
            String extension = item.typeProperty().get().toLowerCase();
            switch (extension) {
                case "txt", "doc", "docx", "pdf":
                    iconLiteral = "fas-file-alt";
                    iconColor = "#6b7280";
                    break;
                case "jpg", "png", "gif", "bmp":
                    iconLiteral = "fas-file-image";
                    iconColor = "#10b981";
                    break;
                case "mp4", "avi", "mov", "wmv":
                    iconLiteral = "fas-file-video";
                    iconColor = "#8b5cf6";
                    break;
                case "mp3", "wav", "flac", "aac":
                    iconLiteral = "fas-file-audio";
                    iconColor = "#f59e0b";
                    break;
                case "zip", "rar", "7z", "tar":
                    iconLiteral = "fas-file-archive";
                    iconColor = "#ef4444";
                    break;
                default:
                    iconLiteral = "fas-file";
                    iconColor = "#6b7280";
            }
        }
        
        FontIcon icon = new FontIcon(iconLiteral);
        icon.setIconSize(14);
        icon.setIconColor(javafx.scene.paint.Color.web(iconColor));
        return icon;
    }

    /**
     * 크기에 따른 색상 스타일
     */
    private String getSizeColorStyle(String sizeText) {
        if (sizeText.contains("GB")) {
            return "-fx-text-fill: #dc2626; -fx-font-weight: bold;"; // 빨간색 (대용량)
        } else if (sizeText.contains("MB")) {
            return "-fx-text-fill: #d97706; -fx-font-weight: bold;"; // 주황색 (중용량)
        } else {
            return "-fx-text-fill: #6b7280;"; // 회색 (소용량)
        }
    }

    /**
     * 상태에 따른 색상 스타일
     */
    private String getStatusColorStyle(String status) {
        switch (status.toLowerCase()) {
            case "암호화 완료", "복호화 완료":
                return "-fx-text-fill: #10b981; -fx-font-weight: bold;"; // 녹색
            case "암호화 중", "복호화 중", "삭제 중":
                return "-fx-text-fill: #3b82f6; -fx-font-weight: bold;"; // 파란색
            case "암호화 실패", "복호화 실패", "삭제 실패":
                return "-fx-text-fill: #dc2626; -fx-font-weight: bold;"; // 빨간색
            case "암호화 취소됨", "복호화 취소됨":
                return "-fx-text-fill: #d97706; -fx-font-weight: bold;"; // 주황색
            default:
                return "-fx-text-fill: #6b7280;"; // 기본 회색
        }
    }

    /**
     * 청크 크기 콤보박스 설정
     */
    private void setupChunkSizeCombo() {
        chunkSizeCombo.getItems().clear();
        chunkSizeCombo.getItems().addAll(
            "1 MB", "16 MB", "32 MB", "64 MB", 
            "128 MB", "256 MB", "512 MB", "1 GB", "2 GB"
        );
        
        // 시스템에 최적화된 기본값 설정
        String optimalSize = settingsManager.getOptimalChunkSize();
        chunkSizeCombo.setValue(optimalSize);
        
        // 값 변경 리스너 (디바운싱 적용)
        chunkSizeCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null && !newValue.equals(oldValue)) {
                // 0.5초 후 저장 (연속 변경 방지)
                Timeline saveTimer = new Timeline(new KeyFrame(Duration.millis(500), e -> saveSettingsSafely()));
                saveTimer.play();
            }
        });
    }

    /**
     * 이벤트 핸들러 설정
     */
    private void setupEventHandlers() {
        // 키보드 단축키
        Platform.runLater(() -> {
            Scene scene = fileTable.getScene();
            if (scene != null) {
                scene.setOnKeyPressed(event -> {
                    try {
                        handleKeyboardShortcuts(event);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "키보드 이벤트 처리 오류", e);
                    }
                });
            }
        });
        
        // 윈도우 이벤트
        Platform.runLater(() -> {
            Stage stage = getStage();
            if (stage != null) {
                stage.setOnCloseRequest(event -> {
                    event.consume(); // 기본 닫기 동작 방지
                    handleWindowClosing();
                });
                
                // 윈도우 크기 변경 이벤트
                stage.widthProperty().addListener((obs, oldVal, newVal) -> saveWindowSettings());
                stage.heightProperty().addListener((obs, oldVal, newVal) -> saveWindowSettings());
            }
        });
    }

    // ==================== 메뉴/버튼 이벤트 핸들러들 ====================

    @FXML
    private void onOpenFolder() {
        if (isShuttingDown.get()) return;
        
        try {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("📁 작업 폴더 선택");
            
            // 스마트 초기 디렉터리 설정
            File initialDir = getSmartInitialDirectory();
            if (initialDir != null) {
                chooser.setInitialDirectory(initialDir);
            }
            
            File directory = chooser.showDialog(getStage());
            if (directory != null) {
                setCurrentDirectoryAsync(directory);
            }
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "폴더 선택 오류", e);
            showAlert(Alert.AlertType.ERROR, "폴더 선택 오류", 
                "폴더를 선택하는 중 오류가 발생했습니다.\n\n" +
                "가능한 원인:\n• 네트워크 드라이브 연결 끊김\n• 폴더 접근 권한 부족\n• 시스템 리소스 부족\n\n" +
                "해결 방법:\n1. 다른 폴더를 선택해 보세요\n2. 관리자 권한으로 실행해 보세요");
        }
    }

    @FXML
    private void refreshFileList() {
        refreshFileListAsync();
    }

    @FXML
    private void onCreateKey() {
        if (isShuttingDown.get()) return;
        
        try {
            // 고급 패스워드 입력 다이얼로그
            AdvancedPasswordDialog dialog = new AdvancedPasswordDialog("새 키 생성", 
                "새 암호화 키를 생성합니다", true);
            Optional<String> password = dialog.showAndWait();
            
            if (!password.isPresent()) {
                return;
            }
            
            // 키 파일 저장 위치 선택
            FileChooser keyChooser = new FileChooser();
            keyChooser.setTitle("🔑 암호화 키 저장 위치 선택");
            keyChooser.setInitialFileName(generateKeyFileName());
            keyChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("암호화 키 파일 (*.key)", "*.key")
            );
            
            String lastKeyPath = settingsManager.getLastKeyPath();
            if (lastKeyPath != null && Files.exists(Path.of(lastKeyPath))) {
                keyChooser.setInitialDirectory(new File(lastKeyPath));
            }
            
            File keyFile = keyChooser.showSaveDialog(getStage());
            if (keyFile != null) {
                executeKeyOperationAsync("키 생성", () -> {
                    // 디스크 공간 체크 (키 파일은 작지만 확인)
                    checkDiskSpaceForKeyOperation(keyFile.getParent());
                    
                    fileSystemManager.generateKey(keyFile, password.get());
                    
                    Platform.runLater(() -> {
                        keyLoaded = true;
                        updateKeyStatus(keyFile, "생성됨");
                        settingsManager.setLastKeyPath(keyFile.getParent());
                        saveSettingsSafely();
                        
                        showSuccess("키 생성 완료", 
                            String.format("암호화 키가 성공적으로 생성되었습니다.\n\n" +
                            "📁 파일: %s\n" +
                            "📏 크기: %s\n" +
                            "🔐 알고리즘: AES-256-GCM\n" +
                            "🔑 키 유도: PBKDF2-HMAC-SHA256 (120,000회)\n\n" +
                            "⚠️ 중요: 이 키 파일과 비밀번호를 안전한 곳에 보관하세요!",
                            keyFile.getName(),
                            formatFileSize(keyFile.length())));
                    });
                });
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "키 생성 오류", e);
            showAlert(Alert.AlertType.ERROR, "키 생성 실패", 
                "키 생성 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    @FXML
    private void onLoadKey() {
        if (isShuttingDown.get()) return;
        
        try {
            // 키 파일 선택
            FileChooser chooser = new FileChooser();
            chooser.setTitle("🔑 암호화 키 파일 선택");
            chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("암호화 키 파일 (*.key)", "*.key"),
                new FileChooser.ExtensionFilter("모든 파일 (*.*)", "*.*")
            );
            
            String lastKeyPath = settingsManager.getLastKeyPath();
            if (lastKeyPath != null && Files.exists(Path.of(lastKeyPath))) {
                chooser.setInitialDirectory(new File(lastKeyPath));
            }
            
            File keyFile = chooser.showOpenDialog(getStage());
            if (keyFile != null && keyFile.exists()) {
                
                // 키 파일 유효성 사전 체크
                if (!validateKeyFile(keyFile)) {
                    return;
                }
                
                // 고급 패스워드 입력 다이얼로그
                AdvancedPasswordDialog dialog = new AdvancedPasswordDialog("키 로드", 
                    "키 파일의 비밀번호를 입력하세요\n
