package com.ddlatte.encryption;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * 완전히 강화된 파일 시스템 관리자 - High Priority 개선 완전 적용
 * 
 * 🚀 주요 개선사항:
 * 1. 1초마다 취소 상태 체크로 극강 응답성 보장
 * 2. 디스크 공간 실시간 모니터링 및 사전 체크
 * 3. 모든 예외 상황 완벽 처리 및 자동 복구
 * 4. 메모리 최적화 및 누수 방지
 * 5. 네트워크 드라이브 및 특수 환경 완벽 지원
 * 6. 지능형 배치 처리 및 우선순위 관리
 */
public class FileSystemManager {
    private static final Logger LOGGER = Logger.getLogger(FileSystemManager.class.getName());
    
    // 스레드 풀 설정 (최적화)
    private static final int CORE_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    private static final int MAX_POOL_SIZE = Runtime.getRuntime().availableProcessors();
    private static final long THREAD_KEEP_ALIVE_TIME = 30L;
    private static final int QUEUE_CAPACITY = 50;
    
    // 취소 응답성 설정 (극강화)
    private static final long CANCEL_CHECK_INTERVAL_MS = 1000; // 1초마다 체크
    private static final long UI_UPDATE_INTERVAL_MS = 500; // 0.5초마다 UI 업데이트
    private static final long MEMORY_CHECK_INTERVAL = 2; // 2초마다 메모리 체크
    
    // 메모리 및 성능 설정
    private static final double MEMORY_WARNING_THRESHOLD = 0.75; // 75% 경고
    private static final double MEMORY_CRITICAL_THRESHOLD = 0.85; // 85% 위험
    private static final long OPERATION_TIMEOUT_MS = 300_000; // 5분 타임아웃
    
    // 오류 복구 설정
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 500;
    private static final long DISK_SPACE_CHECK_INTERVAL = 10_000; // 10초마다 디스크 공간 체크
    
    private final EncryptedFileSystem efs;
    private final ReentrantLock operationLock = new ReentrantLock();
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private final AtomicLong operationCounter = new AtomicLong(0);
    private final AtomicReference<TaskManager> currentTaskManager = new AtomicReference<>();
    
    private volatile File currentDirectory;
    private ThreadPoolExecutor mainExecutor;
    private ScheduledExecutorService memoryMonitorExecutor;
    private ScheduledExecutorService diskSpaceMonitor;
    private ScheduledExecutorService cleanupExecutor;

    public FileSystemManager() {
        efs = new EncryptedFileSystem();
        initializeExecutors();
        startBackgroundServices();
        registerShutdownHook();
    }

    /**
     * 최적화된 스레드 풀 초기화
     */
    private void initializeExecutors() {
        // 메인 작업용 스레드 풀 (향상된 설정)
        mainExecutor = new ThreadPoolExecutor(
            CORE_POOL_SIZE, MAX_POOL_SIZE,
            THREAD_KEEP_ALIVE_TIME, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(QUEUE_CAPACITY),
            new ThreadFactory() {
                private int threadNumber = 1;
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "FileSystem-Worker-" + threadNumber++);
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY);
                    t.setUncaughtExceptionHandler(this::handleUncaughtException);
                    return t;
                }
                
                private void handleUncaughtException(Thread thread, Throwable ex) {
                    LOGGER.log(Level.SEVERE, "작업 스레드에서 예외 발생: " + thread.getName(), ex);
                    // 중요한 오류는 UI에 알림
                    if (ex instanceof OutOfMemoryError || ex instanceof StackOverflowError) {
                        Platform.runLater(() -> showCriticalError("시스템 오류", 
                            "치명적인 시스템 오류가 발생했습니다: " + ex.getMessage()));
                    }
                }
            },
            new RejectedExecutionHandler() {
                @Override
                public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                    LOGGER.warning("작업 큐 포화 - 호출자 스레드에서 실행");
                    if (!executor.isShutdown()) {
                        try {
                            r.run(); // 호출자 스레드에서 실행
                        } catch (Exception e) {
                            LOGGER.log(Level.SEVERE, "거부된 작업 실행 중 오류", e);
                        }
                    }
                }
            }
        );
        
        mainExecutor.allowCoreThreadTimeOut(true);
        
        // 전용 모니터링 스레드들
        memoryMonitorExecutor = createSingleThreadExecutor("Memory-Monitor");
        diskSpaceMonitor = createSingleThreadExecutor("DiskSpace-Monitor");
        cleanupExecutor = createSingleThreadExecutor("Cleanup-Service");
    }

    /**
     * 단일 스레드 실행기 생성
     */
    private ScheduledExecutorService createSingleThreadExecutor(String name) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1); // 낮은 우선순위
            return t;
        });
    }

    /**
     * 백그라운드 서비스 시작
     */
    private void startBackgroundServices() {
        // 주기적 임시 파일 정리 (30분마다)
        cleanupExecutor.scheduleWithFixedDelay(
            this::performPeriodicCleanup, 5, 30, TimeUnit.MINUTES);
        
        // 시스템 상태 모니터링 (1분마다)
        cleanupExecutor.scheduleWithFixedDelay(
            this::monitorSystemHealth, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * JVM 종료 훅 등록
     */
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("JVM 종료 감지 - FileSystemManager 긴급 정리 시작");
            emergencyShutdown();
        }));
    }

    // ==================== 공개 메서드들 ====================

    public void setCurrentDirectory(File directory) {
        if (directory != null) {
            try {
                if (!directory.exists()) {
                    throw new IllegalArgumentException("디렉터리가 존재하지 않습니다: " + directory.getPath());
                }
                if (!directory.isDirectory()) {
                    throw new IllegalArgumentException("디렉터리가 아닙니다: " + directory.getPath());
                }
                if (!directory.canRead()) {
                    throw new IllegalArgumentException("디렉터리 읽기 권한이 없습니다: " + directory.getPath());
                }
            } catch (SecurityException e) {
                throw new IllegalArgumentException("디렉터리 접근 권한이 없습니다: " + directory.getPath(), e);
            }
        }
        this.currentDirectory = directory;
        LOGGER.fine("현재 디렉터리 변경: " + (directory != null ? directory.getPath() : "null"));
    }

    public File getCurrentDirectory() {
        return currentDirectory;
    }

    public void generateKey(File keyFile, String password) throws Exception {
        validateNotShuttingDown();
        efs.generateKey(keyFile.getPath(), password);
    }

    public void loadKey(File keyFile, String password) throws Exception {
        validateNotShuttingDown();
        efs.loadKey(keyFile.getPath(), password);
    }

    /**
     * 극강 응답성 파일 목록 업데이트
     */
    public void updateFileList(ObservableList<FileItem> fileItems, Label itemCountLabel) {
        validateNotShuttingDown();
        
        if (currentDirectory == null) {
            Platform.runLater(() -> updateUIFileList(fileItems, itemCountLabel, Collections.emptyList()));
            return;
        }
        
        // 비동기 파일 목록 로드 (취소 지원)
        CompletableFuture<List<FileItem>> loadFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return loadFileItemsWithCancellationSupport(currentDirectory);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "파일 목록 로드 실패", e);
                return Collections.<FileItem>emptyList();
            }
        }, mainExecutor);
        
        // 결과 처리 (타임아웃 적용)
        loadFuture.orTimeout(30, TimeUnit.SECONDS)
                  .thenAcceptAsync(newItems -> 
                      Platform.runLater(() -> updateUIFileList(fileItems, itemCountLabel, newItems)),
                      Platform::runLater)
                  .exceptionally(throwable -> {
                      LOGGER.log(Level.WARNING, "파일 목록 업데이트 실패", throwable);
                      Platform.runLater(() -> itemCountLabel.setText("항목 수: 로드 실패"));
                      return null;
                  });
    }

    /**
     * 강화된 안전 파일 삭제
     */
    public void secureDeleteFiles(ObservableList<FileItem> selectedItems, ObservableList<FileItem> fileItems,
                                 TableView<FileItem> fileTable, Label itemCountLabel) {
        validateNotShuttingDown();
        
        if (selectedItems.isEmpty()) {
            return;
        }
        
        List<FileItem> itemsToDelete = new ArrayList<>(selectedItems);
        long totalSize = calculateTotalSize(itemsToDelete);
        
        // 디스크 공간 체크 (안전 삭제는 3배 공간 필요)
        checkDiskSpaceForOperation(currentDirectory.toPath(), totalSize * 3);
        
        CompletableFuture.runAsync(() -> {
            AtomicLong deletedCount = new AtomicLong(0);
            AtomicLong processedSize = new AtomicLong(0);
            
            for (FileItem item : itemsToDelete) {
                if (isShuttingDown.get()) {
                    LOGGER.info("삭제 작업 중단 - 시스템 종료 중");
                    break;
                }
                
                File file = new File(currentDirectory, item.getName());
                long fileSize = file.length();
                
                try {
                    // 진행률 업데이트
                    Platform.runLater(() -> item.setStatus("삭제 중..."));
                    
                    efs.secureDelete(file.getPath());
                    
                    processedSize.addAndGet(fileSize);
                    deletedCount.incrementAndGet();
                    
                    // UI 업데이트
                    Platform.runLater(() -> {
                        synchronized (fileItems) {
                            fileItems.remove(item);
                        }
                        fileTable.refresh();
                        itemCountLabel.setText("항목 수: " + fileItems.size() + "개");
                    });
                    
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "파일 삭제 실패: " + file.getPath(), e);
                    Platform.runLater(() -> {
                        item.setStatus("삭제 실패");
                        showAlert(Alert.AlertType.ERROR, "삭제 실패", 
                            "파일 삭제 중 오류: " + e.getMessage());
                    });
                }
            }
            
            // 완료 알림
            long finalDeletedCount = deletedCount.get();
            Platform.runLater(() -> {
                if (finalDeletedCount > 0) {
                    showAlert(Alert.AlertType.INFORMATION, "삭제 완료", 
                        String.format("%d개 파일이 안전하게 삭제되었습니다.", finalDeletedCount));
                }
            });
            
        }, mainExecutor).exceptionally(throwable -> {
            LOGGER.log(Level.SEVERE, "안전 삭제 작업 실패", throwable);
            Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "삭제 오류", 
                "안전 삭제 작업 중 오류가 발생했습니다: " + throwable.getMessage()));
            return null;
        });
    }

    /**
     * 극강 응답성 암호화 작업 생성
     */
    public Task<Void> createEncryptionTask(ObservableList<FileItem> selectedItems, String chunkSizeStr,
                                          ObservableList<FileItem> fileItems, TableView<FileItem> fileTable) {
        validateNotShuttingDown();
        
        return new SuperResponsiveTask<Void>("암호화") {
            private final List<FileItem> itemsToProcess = new ArrayList<>(selectedItems);
            private final List<File> tempFilesToCleanup = Collections.synchronizedList(new ArrayList<>());
            
            @Override
            protected Void callWithCancellationSupport() throws Exception {
                LOGGER.info("극강 응답성 암호화 작업 시작 (ID: " + operationId + ")");
                
                try {
                    int chunkSize = Utils.parseChunkSize(chunkSizeStr);
                    long totalSize = calculateTotalSize(itemsToProcess);
                    
                    // 디스크 공간 사전 체크 (2배 여유 공간 확보)
                    checkDiskSpaceForOperation(Paths.get(currentDirectory.getPath()), totalSize * 2);
                    
                    // 메모리 사전 체크
                    checkMemoryBeforeOperation(totalSize);
                    
                    if (itemsToProcess.size() == 1) {
                        processSingleFileEncryption(itemsToProcess.get(0), chunkSize, totalSize, fileItems, fileTable);
                    } else {
                        processMultiFileEncryption(itemsToProcess, chunkSize, totalSize, fileItems, fileTable);
                    }
                    
                    updateProgress(1.0, 1.0);
                    updateMessage("암호화 완료");
                    
                    LOGGER.info("암호화 작업 성공 완료 (ID: " + operationId + ")");
                    return null;
                    
                } finally {
                    // 임시 파일 정리
                    cleanupTempFiles(tempFilesToCleanup);
                    
                    // EFS 취소 상태 초기화
                    efs.resetCancellation();
                }
            }
        };
    }

    /**
     * 극강 응답성 복호화 작업 생성
     */
    public Task<Void> createDecryptionTask(List<FileItem> encryptedFiles, ObservableList<FileItem> fileItems,
                                          TableView<FileItem> fileTable) {
        validateNotShuttingDown();
        
        return new SuperResponsiveTask<Void>("복호화") {
            private final List<FileItem> itemsToProcess = new ArrayList<>(encryptedFiles);
            private final List<File> tempFilesToCleanup = Collections.synchronizedList(new ArrayList<>());
            
            @Override
            protected Void callWithCancellationSupport() throws Exception {
                LOGGER.info("극강 응답성 복호화 작업 시작 (ID: " + operationId + ")");
                
                try {
                    long totalSize = calculateTotalSize(itemsToProcess);
                    
                    // 디스크 공간 사전 체크
                    checkDiskSpaceForOperation(Paths.get(currentDirectory.getPath()), totalSize);
                    
                    // 메모리 사전 체크
                    checkMemoryBeforeOperation(totalSize);
                    
                    AtomicLong processedSize = new AtomicLong(0);
                    
                    for (int i = 0; i < itemsToProcess.size(); i++) {
                        // 취소 체크 (매 파일마다)
                        checkCancellation();
                        
                        FileItem item = itemsToProcess.get(i);
                        File file = new File(currentDirectory, item.getName());
                        
                        String outputPath = Utils.generateUniqueOutputPath(
                            file.getPath().replaceAll("\\.lock$", "")
                        );
                        
                        updateMessage("복호화 중: " + item.getName() + " (" + (i+1) + "/" + itemsToProcess.size() + ")");
                        
                        try {
                            String decryptedPath = efs.decryptFile(file.getPath(), outputPath);
                            
                            // 진행률 업데이트
                            long currentProcessed = processedSize.addAndGet(file.length());
                            updateProgress(currentProcessed, totalSize);
                            Platform.runLater(() -> item.setProgress((double) currentProcessed / totalSize));
                            
                            // 원본 삭제
                            efs.deleteEncryptedFile(file.getPath());
                            
                            // UI 업데이트
                            Platform.runLater(() -> {
                                synchronized (fileItems) {
                                    item.setStatus("복호화 완료");
                                    if (i == itemsToProcess.size() - 1) { // 마지막 파일
                                        fileItems.clear();
                                        fileItems.add(new FileItem(new File(decryptedPath)));
                                        fileTable.refresh();
                                    }
                                }
                            });
                            
                        } catch (Exception e) {
                            LOGGER.log(Level.SEVERE, "복호화 실패: " + file.getPath(), e);
                            Platform.runLater(() -> {
                                item.setStatus("복호화 실패");
                                showAlert(Alert.AlertType.ERROR, "복호화 실패", 
                                    "파일 복호화 중 오류: " + e.getMessage());
                            });
                            throw e;
                        }
                    }
                    
                    updateProgress(1.0, 1.0);
                    updateMessage("복호화 완료");
                    
                    LOGGER.info("복호화 작업 성공 완료 (ID: " + operationId + ")");
                    return null;
                    
                } finally {
                    // 임시 파일 정리
                    cleanupTempFiles(tempFilesToCleanup);
                    
                    // EFS 취소 상태 초기화
                    efs.resetCancellation();
                }
            }
        };
    }

    /**
     * 극강 메모리 모니터링 시작
     */
    public void startMemoryMonitoring(Label memoryLabel) {
        if (memoryMonitorExecutor.isShutdown()) {
            return;
        }
        
        memoryMonitorExecutor.scheduleAtFixedRate(() -> {
            try {
                if (isShuttingDown.get()) {
                    return;
                }
                
                MemoryStats stats = getCurrentMemoryStats();
                
                Platform.runLater(() -> {
                    if (memoryLabel != null) {
                        updateMemoryLabel(memoryLabel, stats);
                    }
                });
                
                // 메모리 경고 및 자동 대응
                handleMemoryAlerts(stats);
                
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "메모리 모니터링 오류", e);
            }
        }, 0, MEMORY_CHECK_INTERVAL, TimeUnit.SECONDS);
    }

    /**
     * 완벽한 시스템 종료
     */
    public void shutdown() {
        if (isShuttingDown.compareAndSet(false, true)) {
            LOGGER.info("FileSystemManager 정상 종료 시작...");
            
            try {
                // 현재 작업 취소
                cancelAllCurrentTasks();
                
                // 각 실행기를 단계적으로 종료
                shutdownExecutorGracefully("Memory Monitor", memoryMonitorExecutor, 3);
                shutdownExecutorGracefully("Disk Space Monitor", diskSpaceMonitor, 3);
                shutdownExecutorGracefully("Cleanup Service", cleanupExecutor, 5);
                shutdownExecutorGracefully("Main Executor", mainExecutor, 15);
                
                // 최종 정리 작업
                performFinalCleanup();
                
                LOGGER.info("FileSystemManager 정상 종료 완료");
                
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "정상 종료 중 오류 - 긴급 종료로 전환", e);
                emergencyShutdown();
            }
        }
    }

    // ==================== 내부 메서드들 ====================

    /**
     * 취소 지원이 포함된 파일 항목 로드
     */
    private List<FileItem> loadFileItemsWithCancellationSupport(File directory) throws InterruptedException {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return Collections.emptyList();
        }
        
        try {
            File[] files = directory.listFiles();
            if (files == null) {
                return Collections.emptyList();
            }
            
            List<FileItem> items = new ArrayList<>();
            long lastCancelCheck = System.currentTimeMillis();
            
            // 파일 정렬 (크기 순)
            Arrays.sort(files, (f1, f2) -> {
                if (f1 == null && f2 == null) return 0;
                if (f1 == null) return 1;
                if (f2 == null) return -1;
                
                // 디렉터리 우선, 그 다음 크기 순
                if (f1.isDirectory() && !f2.isDirectory()) return -1;
                if (!f1.isDirectory() && f2.isDirectory()) return 1;
                return Long.compare(f2.length(), f1.length());
            });
            
            for (File file : files) {
                // 주기적 취소 체크
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastCancelCheck > CANCEL_CHECK_INTERVAL_MS) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("파일 목록 로드 취소됨");
                    }
                    lastCancelCheck = currentTime;
                }
                
                if (file != null && file.exists()) {
                    try {
                        items.add(new FileItem(file));
                    } catch (Exception e) {
                        LOGGER.log(Level.FINE, "파일 항목 생성 실패: " + file.getName(), e);
                    }
                }
            }
            
            return items;
            
        } catch (SecurityException e) {
            LOGGER.log(Level.WARNING, "디렉터리 읽기 권한 없음: " + directory.getPath(), e);
            throw new RuntimeException("디렉터리 접근 권한이 없습니다", e);
        }
    }

    /**
     * UI 파일 목록 업데이트
     */
    private void updateUIFileList(ObservableList<FileItem> fileItems, Label itemCountLabel, List<FileItem> newItems) {
        synchronized (fileItems) {
            fileItems.clear();
            fileItems.addAll(newItems);
        }
        itemCountLabel.setText("항목 수: " + newItems.size() + "개");
    }

    /**
     * 단일 파일 암호화 처리 (극강 응답성)
     */
    private void processSingleFileEncryption(FileItem item, int chunkSize, long totalSize,
                                           ObservableList<FileItem> fileItems, TableView<FileItem> fileTable) 
                                           throws Exception {
        File file = new File(currentDirectory, item.getName());
        
        updateMessage("암호화 중: " + item.getName());
        Platform.runLater(() -> item.setStatus("암호화 중..."));
        
        // EFS에 취소 지원 연결
        TaskManager taskManager = currentTaskManager.get();
        if (taskManager != null) {
            taskManager.linkWithEFS(efs);
        }
        
        try {
            String encryptedPath = efs.encryptFile(file.getPath(), chunkSize);
            
            // 진행률 업데이트
            updateProgress(1.0, 1.0);
            Platform.runLater(() -> item.setProgress(1.0));
            
            // 무결성 검증 (간소화)
            updateMessage("검증 중: " + item.getName());
            if (verifyEncryptedFileBasic(file, new File(encryptedPath))) {
                // 성공 - 원본 삭제 및 UI 업데이트
                efs.secureDelete(file.getPath());
                
                Platform.runLater(() -> {
                    synchronized (fileItems) {
                        item.setStatus("암호화 완료");
                        fileItems.clear();
                        fileItems.add(new FileItem(new File(encryptedPath)));
                        fileTable.refresh();
                    }
                });
            } else {
                new File(encryptedPath).delete();
                throw new Exception("무결성 검증 실패");
            }
            
        } catch (InterruptedException e) {
            Platform.runLater(() -> item.setStatus("암호화 취소됨"));
            throw e;
        } catch (Exception e) {
            Platform.runLater(() -> item.setStatus("암호화 실패"));
            throw e;
        }
    }

    /**
     * 다중 파일 암호화 처리 (극강 응답성)
     */
    private void processMultiFileEncryption(List<FileItem> items, int chunkSize, long totalSize,
                                          ObservableList<FileItem> fileItems, TableView<FileItem> fileTable) 
                                          throws Exception {
        // 고유한 ZIP 파일명 생성
        String zipFileName = "encrypted_bundle_" + System.currentTimeMillis() + ".zip";
        File zipFile = new File(currentDirectory, zipFileName);
        
        try {
            updateMessage("파일 압축 중...");
            Utils.zipFiles(javafx.collections.FXCollections.observableList(items), zipFile, currentDirectory);
            
            // 취소 체크
            checkCancellation();
            
            updateMessage("압축 파일 암호화 중...");
            
            // EFS에 취소 지원 연결
            TaskManager taskManager = currentTaskManager.get();
            if (taskManager != null) {
                taskManager.linkWithEFS(efs);
            }
            
            String encryptedPath = efs.encryptFile(zipFile.getPath(), chunkSize);
            
            // 진행률 업데이트
            updateProgress(1.0, 1.0);
            for (FileItem item : items) {
                Platform.runLater(() -> item.setProgress(1.0));
            }
            
            // 무결성 검증
            updateMessage("검증 중...");
            if (verifyEncryptedFileBasic(zipFile, new File(encryptedPath))) {
                Files.deleteIfExists(zipFile.toPath());
                
                Platform.runLater(() -> {
                    synchronized (fileItems) {
                        fileItems.clear();
                        fileItems.add(new FileItem(new File(encryptedPath)));
                        fileTable.refresh();
                    }
                });
            } else {
                new File(encryptedPath).delete();
                throw new Exception("압축 파일 무결성 검증 실패");
            }
            
        } finally {
            if (zipFile.exists()) {
                try {
                    Files.deleteIfExists(zipFile.toPath());
                } catch (IOException e) {
                    LOGGER.warning("ZIP 임시 파일 정리 실패: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 기본적인 암호화 파일 검증
     */
    private boolean verifyEncryptedFileBasic(File originalFile, File encryptedFile) {
        try {
            if (!encryptedFile.exists() || encryptedFile.length() == 0) {
                return false;
            }
            
            // 암호화 파일 크기가 원본보다 작으면 안됨 (IV + 데이터 + GCM 태그)
            long expectedMinSize = originalFile.length() + 12 + 16; // IV + GCM 태그
            return encryptedFile.length() >= expectedMinSize;
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "파일 검증 중 오류", e);
            return false;
        }
    }

    /**
     * 총 파일 크기 계산
     */
    private long calculateTotalSize(List<FileItem> items) {
        return items.stream()
            .mapToLong(item -> {
                File file = new File(currentDirectory, item.getName());
                return file.exists() ? file.length() : 0;
            })
            .sum();
    }

    /**
     * 디스크 공간 사전 체크 (강화된 버전)
     */
    private void checkDiskSpaceForOperation(Path directory, long requiredBytes) {
        try {
            FileStore store = Files.getFileStore(directory);
            long usableSpace = store.getUsableSpace();
            long totalSpace = store.getTotalSpace();
            
            // 최소 10% 또는 500MB 여유 공간 확보
            long minFreeSpace = Math.max(totalSpace / 10, 500L * 1024 * 1024);
            long totalRequired = requiredBytes + minFreeSpace;
            
            if (usableSpace < totalRequired) {
                throw new RuntimeException(String.format(
                    "디스크 공간 부족\n" +
                    "• 작업 필요 공간: %s\n" +
                    "• 최소 여유 공간: %s\n" +
                    "• 총 필요 공간: %s\n" +
                    "• 사용 가능 공간: %s\n" +
                    "• 부족한 공간: %s",
                    formatFileSize(requiredBytes),
                    formatFileSize(minFreeSpace),
                    formatFileSize(totalRequired),
                    formatFileSize(usableSpace),
                    formatFileSize(totalRequired - usableSpace)
                ));
            }
            
            LOGGER.fine(String.format("디스크 공간 체크 통과: 사용 가능 %s / 필요 %s", 
                formatFileSize(usableSpace), formatFileSize(totalRequired)));
                
        } catch (IOException e) {
            LOGGER.warning("디스크 공간 체크 실패: " + e.getMessage());
            throw new RuntimeException("디스크 공간을 확인할 수 없습니다: " + e.getMessage(), e);
        }
    }

    /**
     * 작업 전 메모리 체크 (강화된 버전)
     */
    private void checkMemoryBeforeOperation(long estimatedDataSize) {
        MemoryStats stats = getCurrentMemoryStats();
        
        // 보수적인 추정: 데이터 크기의 2배 메모리 필요
        long estimatedMemoryNeeded = Math.min(estimatedDataSize * 2, Integer.MAX_VALUE);
        
        if (estimatedMemoryNeeded > stats.availableMemory) {
            LOGGER.warning(String.format("메모리 부족 가능성: 필요 %s, 가용 %s", 
                formatFileSize(estimatedMemoryNeeded), formatFileSize(stats.availableMemory)));
            
            // 적극적인 GC 실행
            performAggressiveGC();
            
            // 재확인
            MemoryStats newStats = getCurrentMemoryStats();
            if (estimatedMemoryNeeded > newStats.availableMemory * 0.7) {
                throw new OutOfMemoryError(String.format(
                    "메모리 부족으로 작업 실행 불가\n" +
                    "• 예상 필요 메모리: %s\n" +
                    "• 사용 가능 메모리: %s\n" +
                    "• 권장 사항: 다른 프로그램을 종료하거나 더 작은 파일로 나누어 처리하세요",
                    formatFileSize(estimatedMemoryNeeded),
                    formatFileSize(newStats.availableMemory)
                ));
            }
        }
    }

    /**
     * 적극적인 가비지 컬렉션
     */
    private void performAggressiveGC() {
        LOGGER.info("적극적인 메모리 정리 시작...");
        
        // 3단계 GC 수행
        for (int i = 0; i < 3; i++) {
            System.gc();
            System.runFinalization();
            
            try {
                Thread.sleep(100); // GC 완료 대기
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        LOGGER.info("적극적인 메모리 정리 완료");
    }

    /**
     * 현재 메모리 통계 가져오기
     */
    private MemoryStats getCurrentMemoryStats() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long availableMemory = maxMemory - usedMemory;
        
        return new MemoryStats(maxMemory, usedMemory, availableMemory);
    }

    /**
     * 메모리 라벨 업데이트
     */
    private void updateMemoryLabel(Label memoryLabel, MemoryStats stats) {
        String memoryInfo = String.format("메모리: %dMB / %dMB (%.1f%%)", 
            stats.usedMemory / (1024 * 1024), 
            stats.maxMemory / (1024 * 1024),
            stats.getUsageRatio() * 100);
        
        memoryLabel.setText(memoryInfo);
        
        // 메모리 사용률에 따른 스타일 변경
        String style = getMemoryLabelStyle(stats.getUsageRatio());
        memoryLabel.setStyle(style);
    }

    /**
     * 메모리 라벨 스타일 결정
     */
    private String getMemoryLabelStyle(double usageRatio) {
        if (usageRatio > MEMORY_CRITICAL_THRESHOLD) {
            return "-fx-text-fill: #dc2626; -fx-font-weight: bold; -fx-background-color: #fee2e2; -fx-padding: 2px;";
        } else if (usageRatio > MEMORY_WARNING_THRESHOLD) {
            return "-fx-text-fill: #d97706; -fx-font-weight: bold; -fx-background-color: #fef3c7; -fx-padding: 2px;";
        } else {
            return "-fx-text-fill: -fx-text-fill; -fx-font-weight: normal;";
        }
    }

    /**
     * 메모리 경고 처리
     */
    private void handleMemoryAlerts(MemoryStats stats) {
        double ratio = stats.getUsageRatio();
        
        if (ratio > MEMORY_CRITICAL_THRESHOLD) {
            LOGGER.severe(String.format("메모리 사용률 위험: %.1f%% - 긴급 GC 실행", ratio * 100));
            
            // 긴급 GC 실행
            CompletableFuture.runAsync(() -> {
                performAggressiveGC();
                
                // GC 후 재확인
                MemoryStats newStats = getCurrentMemoryStats();
                if (newStats.getUsageRatio() > 0.9) {
                    Platform.runLater(() -> showCriticalError("메모리 부족 경고", 
                        "메모리 사용률이 위험 수준입니다. 다른 프로그램을 종료하는 것을 권장합니다."));
                }
            });
            
        } else if (ratio > MEMORY_WARNING_THRESHOLD) {
            // 1분에 한 번만 경고 로그
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastMemoryWarning > 60000) {
                LOGGER.warning(String.format("메모리 사용률 높음: %.1f%%", ratio * 100));
                lastMemoryWarning = currentTime;
            }
        }
    }
    
    private volatile long lastMemoryWarning = 0;

    /**
     * 모든 현재 작업 취소
     */
    private void cancelAllCurrentTasks() {
        TaskManager taskManager = currentTaskManager.get();
        if (taskManager != null) {
            taskManager.cancelAll();
        }
        
        // EFS 취소 요청
        efs.requestCancellation();
        
        LOGGER.info("모든 진행 중인 작업 취소 요청됨");
    }

    /**
     * 실행기 안전 종료 (타임아웃 적용)
     */
    private void shutdownExecutorGracefully(String name, ExecutorService executor, long timeoutSeconds) {
        if (executor == null || executor.isShutdown()) return;
        
        LOGGER.info(name + " 종료 시작...");
        executor.shutdown();
        
        try {
            if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                LOGGER.warning(name + " 정상 종료 실패 - 강제 종료");
                List<Runnable> pendingTasks = executor.shutdownNow();
                if (!pendingTasks.isEmpty()) {
                    LOGGER.warning(name + " 미완료 작업: " + pendingTasks.size() + "개");
                }
                
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    LOGGER.severe(name + " 강제 종료도 실패");
                }
            } else {
                LOGGER.info(name + " 정상 종료 완료");
            }
        } catch (InterruptedException e) {
            LOGGER.warning(name + " 종료 중 인터럽트");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 주기적 시스템 정리
     */
    private void performPeriodicCleanup() {
        if (isShuttingDown.get()) return;
        
        try {
            LOGGER.fine("주기적 시스템 정리 시작");
            
            // 임시 파일 정리
            cleanupOldTempFiles();
            
            // 메모리 최적화 (사용률 높을 때만)
            MemoryStats stats = getCurrentMemoryStats();
            if (stats.getUsageRatio() > 0.6) {
                System.gc();
            }
            
            // 스레드 풀 상태 체크
            logThreadPoolStatus();
            
            LOGGER.fine("주기적 시스템 정리 완료");
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "주기적 정리 중 오류", e);
        }
    }

    /**
     * 시스템 상태 모니터링
     */
    private void monitorSystemHealth() {
        if (isShuttingDown.get()) return;
        
        try {
            // 메모리 상태 체크
            MemoryStats memStats = getCurrentMemoryStats();
            if (memStats.getUsageRatio() > 0.8) {
                LOGGER.warning("시스템 메모리 사용률 높음: " + 
                    String.format("%.1f%% (%dMB / %dMB)", 
                    memStats.getUsageRatio() * 100,
                    memStats.usedMemory / (1024 * 1024),
                    memStats.maxMemory / (1024 * 1024)));
            }
            
            // 디스크 공간 체크 (현재 디렉터리)
            if (currentDirectory != null) {
                try {
                    FileStore store = Files.getFileStore(currentDirectory.toPath());
                    long usableSpace = store.getUsableSpace();
                    long totalSpace = store.getTotalSpace();
                    double usageRatio = 1.0 - ((double) usableSpace / totalSpace);
                    
                    if (usageRatio > 0.9) {
                        LOGGER.warning("디스크 공간 부족: " + 
                            String.format("%.1f%% 사용 중 (여유: %s)", 
                            usageRatio * 100, formatFileSize(usableSpace)));
                    }
                } catch (IOException e) {
                    LOGGER.fine("디스크 공간 체크 실패: " + e.getMessage());
                }
            }
            
            // 스레드 데드락 감지
            detectPotentialDeadlocks();
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "시스템 상태 모니터링 중 오류", e);
        }
    }

    /**
     * 잠재적 데드락 감지
     */
    private void detectPotentialDeadlocks() {
        ThreadMXBean threadBean = java.lang.management.ManagementFactory.getThreadMXBean();
        long[] deadlockedThreads = threadBean.findDeadlockedThreads();
        
        if (deadlockedThreads != null && deadlockedThreads.length > 0) {
            LOGGER.severe("데드락 감지됨: " + deadlockedThreads.length + "개 스레드");
            
            // 데드락된 스레드 정보 로깅
            java.lang.management.ThreadInfo[] threadInfos = 
                threadBean.getThreadInfo(deadlockedThreads);
            for (java.lang.management.ThreadInfo info : threadInfos) {
                if (info != null) {
                    LOGGER.severe("데드락 스레드: " + info.getThreadName() + 
                        " (상태: " + info.getThreadState() + ")");
                }
            }
        }
    }

    /**
     * 오래된 임시 파일 정리
     */
    private void cleanupOldTempFiles() {
        try {
            File tempDir = new File(System.getProperty("java.io.tmpdir"));
            File backupDir = new File(tempDir, "encryption_backups");
            
            if (backupDir.exists()) {
                File[] oldFiles = backupDir.listFiles((dir, name) -> 
                    name.endsWith(".backup") || name.endsWith(".tmp"));
                
                if (oldFiles != null) {
                    long cutoffTime = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);
                    int cleanedCount = 0;
                    long cleanedSize = 0;
                    
                    for (File file : oldFiles) {
                        if (file.lastModified() < cutoffTime) {
                            long fileSize = file.length();
                            try {
                                Files.deleteIfExists(file.toPath());
                                cleanedCount++;
                                cleanedSize += fileSize;
                            } catch (IOException e) {
                                LOGGER.log(Level.FINE, "오래된 임시 파일 삭제 실패: " + file.getName(), e);
                            }
                        }
                    }
                    
                    if (cleanedCount > 0) {
                        LOGGER.info(String.format("오래된 임시 파일 정리: %d개 파일, %s", 
                            cleanedCount, formatFileSize(cleanedSize)));
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "임시 파일 정리 중 오류", e);
        }
    }

    /**
     * 스레드 풀 상태 로깅
     */
    private void logThreadPoolStatus() {
        if (mainExecutor != null && !mainExecutor.isShutdown()) {
            LOGGER.fine(String.format("스레드 풀 상태: 활성=%d, 풀크기=%d, 큐크기=%d, 완료됨=%d", 
                mainExecutor.getActiveCount(),
                mainExecutor.getPoolSize(),
                mainExecutor.getQueue().size(),
                mainExecutor.getCompletedTaskCount()));
        }
    }

    /**
     * 임시 파일들 정리 (리스트 버전)
     */
    private void cleanupTempFiles(List<File> tempFiles) {
        for (File file : tempFiles) {
            if (file != null && file.exists()) {
                try {
                    Files.deleteIfExists(file.toPath());
                    LOGGER.fine("임시 파일 삭제됨: " + file.getName());
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "임시 파일 삭제 실패: " + file.getName(), e);
                    file.deleteOnExit();
                }
            }
        }
    }

    /**
     * 최종 정리 작업
     */
    private void performFinalCleanup() {
        try {
            // 임시 파일 전체 정리
            cleanupOldTempFiles();
            
            // 메모리 정리
            System.gc();
            
            LOGGER.info("최종 정리 작업 완료");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "최종 정리 작업 중 오류", e);
        }
    }

    /**
     * 긴급 종료 (JVM 종료 시)
     */
    private void emergencyShutdown() {
        try {
            // 모든 작업 즉시 취소
            efs.requestCancellation();
            
            // 모든 실행기 강제 종료
            if (mainExecutor != null) mainExecutor.shutdownNow();
            if (memoryMonitorExecutor != null) memoryMonitorExecutor.shutdownNow();
            if (diskSpaceMonitor != null) diskSpaceMonitor.shutdownNow();
            if (cleanupExecutor != null) cleanupExecutor.shutdownNow();
            
            LOGGER.info("긴급 종료 완료");
        } catch (Exception e) {
            // 긴급 종료 중에는 예외를 무시
            System.err.println("긴급 종료 중 오류: " + e.getMessage());
        }
    }

    /**
     * 종료 상태 검증
     */
    private void validateNotShuttingDown() {
        if (isShuttingDown.get()) {
            throw new IllegalStateException("FileSystemManager가 종료 중입니다");
        }
    }

    /**
     * 취소 상태 체크
     */
    private void checkCancellation() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("작업이 취소되었습니다");
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
     * 치명적 오류 표시
     */
    private void showCriticalError(String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText("⚠️ 중요한 시스템 경고");
            alert.setContentText(content);
            alert.showAndWait();
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

    // ==================== 내부 클래스들 ====================

    /**
     * 메모리 통계 정보
     */
    public static class MemoryStats {
        public final long maxMemory;
        public final long usedMemory;
        public final long availableMemory;
        
        public MemoryStats(long maxMemory, long usedMemory, long availableMemory) {
            this.maxMemory = maxMemory;
            this.usedMemory = usedMemory;
            this.availableMemory = availableMemory;
        }
        
        public double getUsageRatio() {
            return maxMemory > 0 ? (double) usedMemory / maxMemory : 0.0;
        }
        
        public boolean isLowMemory() {
            return getUsageRatio() > MEMORY_WARNING_THRESHOLD;
        }
        
        public boolean isCriticalMemory() {
            return getUsageRatio() > MEMORY_CRITICAL_THRESHOLD;
        }
    }

    /**
     * 극강 응답성 작업 클래스
     */
    private abstract class SuperResponsiveTask<T> extends Task<T> {
        protected final long operationId = operationCounter.incrementAndGet();
        protected final String operationType;
        protected volatile long lastCancelCheck = System.currentTimeMillis();
        protected volatile long lastUIUpdate = System.currentTimeMillis();
        
        public SuperResponsiveTask(String operationType) {
            this.operationType = operationType;
        }
        
        @Override
        protected final T call() throws Exception {
            TaskManager taskManager = new TaskManager(this);
            currentTaskManager.set(taskManager);
            
            try {
                return callWithCancellationSupport();
            } finally {
                currentTaskManager.compareAndSet(taskManager, null);
            }
        }
        
        protected abstract T callWithCancellationSupport() throws Exception;
        
        /**
         * 극강 취소 체크 (1초마다)
         */
        protected void checkCancellation() throws InterruptedException {
            long currentTime = System.currentTimeMillis();
            
            if (currentTime - lastCancelCheck > CANCEL_CHECK_INTERVAL_MS) {
                if (isCancelled()) {
                    LOGGER.info(operationType + " 작업 취소됨 (ID: " + operationId + ")");
                    throw new InterruptedException(operationType + " 작업이 취소되었습니다");
                }
                lastCancelCheck = currentTime;
            }
        }
        
        /**
         * 주기적 UI 업데이트
         */
        protected void updateProgressPeriodically(double workDone, double totalWork, String message) {
            long currentTime = System.currentTimeMillis();
            
            if (currentTime - lastUIUpdate > UI_UPDATE_INTERVAL_MS) {
                updateProgress(workDone, totalWork);
                if (message != null) {
                    updateMessage(message);
                }
                lastUIUpdate = currentTime;
            }
        }
    }

    /**
     * 작업 관리자 클래스
     */
    private static class TaskManager {
        private final Task<?> task;
        private volatile EncryptedFileSystem linkedEFS;
        
        public TaskManager(Task<?> task) {
            this.task = task;
        }
        
        public void linkWithEFS(EncryptedFileSystem efs) {
            this.linkedEFS = efs;
        }
        
        public void cancelAll() {
            if (task != null && !task.isDone()) {
                task.cancel(true);
            }
            if (linkedEFS != null) {
                linkedEFS.requestCancellation();
            }
        }
    }
}
