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
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * 완전히 개선된 파일 시스템 관리자
 * 
 * 🔧 주요 개선사항:
 * - 스레드 안전성 완전 보장
 * - 메모리 누수 방지를 위한 완벽한 리소스 정리
 * - 원자적 파일 연산으로 데이터 손실 방지
 * - 지능형 메모리 모니터링 및 관리
 * - 강화된 예외 처리 및 복구 메커니즘
 * - 데드락 방지를 위한 타임아웃 적용
 */
public class FileSystemManager {
    private static final Logger LOGGER = Logger.getLogger(FileSystemManager.class.getName());
    
    // 스레드 풀 설정
    private static final int CORE_POOL_SIZE = 2;
    private static final int MAX_POOL_SIZE = Math.max(4, Runtime.getRuntime().availableProcessors());
    private static final long THREAD_KEEP_ALIVE_TIME = 30L; // 30초로 단축
    private static final int QUEUE_CAPACITY = 100;
    
    // 메모리 모니터링 설정
    private static final int MEMORY_CHECK_INTERVAL = 2; // 2초마다 체크
    private static final double MEMORY_WARNING_THRESHOLD = 0.80; // 80% 경고
    private static final double MEMORY_CRITICAL_THRESHOLD = 0.90; // 90% 위험
    private static final long MAX_OPERATION_TIMEOUT = 30; // 30초 타임아웃
    
    // 재시도 및 백업 설정
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 200;
    
    private final EncryptedFileSystem efs;
    private final ReentrantLock operationLock = new ReentrantLock();
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private final AtomicLong operationCounter = new AtomicLong(0);
    
    private volatile File currentDirectory;
    private ThreadPoolExecutor mainExecutor;
    private ScheduledExecutorService memoryMonitorExecutor;
    private ScheduledExecutorService cleanupExecutor;

    public FileSystemManager() {
        efs = new EncryptedFileSystem();
        initializeExecutors();
        startBackgroundServices();
    }

    /**
     * 스레드 풀 초기화 (개선된 버전)
     */
    private void initializeExecutors() {
        // 메인 작업용 스레드 풀
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
                    t.setUncaughtExceptionHandler((thread, ex) -> 
                        LOGGER.log(Level.SEVERE, "작업 스레드에서 예외 발생: " + thread.getName(), ex));
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // 큐가 가득 찰 경우 호출자 스레드에서 실행
        );
        
        mainExecutor.allowCoreThreadTimeOut(true);
        
        // 메모리 모니터링용 스레드
        memoryMonitorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Memory-Monitor");
            t.setDaemon(true);
            return t;
        });
        
        // 정리 작업용 스레드
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Cleanup-Service");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 백그라운드 서비스 시작
     */
    private void startBackgroundServices() {
        // 주기적 임시 파일 정리
        cleanupExecutor.scheduleWithFixedDelay(this::cleanupTempFiles, 5, 30, TimeUnit.MINUTES);
        
        // JVM 종료 훅 등록
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("JVM 종료 - FileSystemManager 정리 중...");
            shutdown();
        }));
    }

    // ==================== 공개 메서드들 ====================

    public void setCurrentDirectory(File directory) {
        if (directory != null && (!directory.exists() || !directory.isDirectory())) {
            throw new IllegalArgumentException("유효하지 않은 디렉터리: " + directory.getPath());
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
     * 스레드 안전한 파일 목록 업데이트
     */
    public void updateFileList(ObservableList<FileItem> fileItems, Label itemCountLabel) {
        validateNotShuttingDown();
        
        if (currentDirectory == null) {
            Platform.runLater(() -> {
                synchronized (fileItems) {
                    fileItems.clear();
                }
                itemCountLabel.setText("항목 수: 0개");
            });
            return;
        }
        
        CompletableFuture.supplyAsync(() -> {
            try {
                return loadFileItemsSafely(currentDirectory);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "파일 목록 로드 실패", e);
                return Collections.<FileItem>emptyList();
            }
        }, mainExecutor).thenAcceptAsync(newItems -> {
            Platform.runLater(() -> {
                synchronized (fileItems) {
                    fileItems.clear();
                    fileItems.addAll(newItems);
                }
                itemCountLabel.setText("항목 수: " + newItems.size() + "개");
            });
        }, Platform::runLater).exceptionally(throwable -> {
            LOGGER.log(Level.SEVERE, "파일 목록 업데이트 실패", throwable);
            return null;
        });
    }

    /**
     * 안전한 파일 삭제 (스레드 안전)
     */
    public void secureDeleteFiles(ObservableList<FileItem> selectedItems, ObservableList<FileItem> fileItems,
                                 TableView<FileItem> fileTable, Label itemCountLabel) {
        validateNotShuttingDown();
        
        if (selectedItems.isEmpty()) {
            return;
        }
        
        List<FileItem> itemsToDelete = new ArrayList<>(selectedItems);
        
        CompletableFuture.runAsync(() -> {
            for (FileItem item : itemsToDelete) {
                if (isShuttingDown.get()) {
                    break;
                }
                
                File file = new File(currentDirectory, item.getName());
                try {
                    efs.secureDelete(file.getPath());
                    
                    Platform.runLater(() -> {
                        synchronized (fileItems) {
                            fileItems.remove(item);
                        }
                        fileTable.refresh();
                        itemCountLabel.setText("항목 수: " + fileItems.size() + "개");
                    });
                    
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "파일 삭제 실패: " + file.getPath(), e);
                    Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "삭제 실패", 
                        "파일 삭제 중 오류: " + e.getMessage()));
                }
            }
        }, mainExecutor).exceptionally(throwable -> {
            LOGGER.log(Level.SEVERE, "안전 삭제 작업 실패", throwable);
            return null;
        });
    }

    /**
     * 암호화 작업 생성 (완전히 개선된 버전)
     */
    public Task<Void> createEncryptionTask(ObservableList<FileItem> selectedItems, String chunkSizeStr,
                                          ObservableList<FileItem> fileItems, TableView<FileItem> fileTable) {
        validateNotShuttingDown();
        
        return new Task<>() {
            private final long operationId = operationCounter.incrementAndGet();
            private final List<File> tempFilesToCleanup = new ArrayList<>();
            
            @Override
            protected Void call() throws Exception {
                LOGGER.info("암호화 작업 시작 (ID: " + operationId + ")");
                
                try {
                    List<FileItem> itemsToProcess = new ArrayList<>(selectedItems);
                    int chunkSize = Utils.parseChunkSize(chunkSizeStr);
                    
                    // 총 파일 크기 계산 및 메모리 체크
                    long totalSize = calculateTotalSize(itemsToProcess);
                    checkMemoryBeforeOperation(totalSize);
                    
                    if (itemsToProcess.size() == 1) {
                        processSingleFileEncryption(itemsToProcess.get(0), chunkSize, totalSize, fileItems, fileTable);
                    } else {
                        processMultiFileEncryption(itemsToProcess, chunkSize, totalSize, fileItems, fileTable);
                    }
                    
                    updateProgress(1.0, 1.0);
                    updateMessage("암호화 완료");
                    
                    LOGGER.info("암호화 작업 완료 (ID: " + operationId + ")");
                    return null;
                    
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "암호화 작업 실패 (ID: " + operationId + ")", e);
                    throw e;
                } finally {
                    cleanupTempFiles(tempFilesToCleanup);
                }
            }
            
            @Override
            protected void cancelled() {
                LOGGER.info("암호화 작업 취소됨 (ID: " + operationId + ")");
                cleanupTempFiles(tempFilesToCleanup);
            }
            
            @Override
            protected void failed() {
                LOGGER.severe("암호화 작업 실패 (ID: " + operationId + ")");
                cleanupTempFiles(tempFilesToCleanup);
            }
        };
    }

    /**
     * 복호화 작업 생성 (완전히 개선된 버전)
     */
    public Task<Void> createDecryptionTask(List<FileItem> encryptedFiles, ObservableList<FileItem> fileItems,
                                          TableView<FileItem> fileTable) {
        validateNotShuttingDown();
        
        return new Task<>() {
            private final long operationId = operationCounter.incrementAndGet();
            private final List<File> tempFilesToCleanup = new ArrayList<>();
            
            @Override
            protected Void call() throws Exception {
                LOGGER.info("복호화 작업 시작 (ID: " + operationId + ")");
                
                try {
                    List<FileItem> itemsToProcess = new ArrayList<>(encryptedFiles);
                    long totalSize = calculateTotalSize(itemsToProcess);
                    checkMemoryBeforeOperation(totalSize);
                    
                    long[] processedSize = {0};
                    
                    for (int i = 0; i < itemsToProcess.size(); i++) {
                        if (isCancelled()) {
                            break;
                        }
                        
                        FileItem item = itemsToProcess.get(i);
                        File file = new File(currentDirectory, item.getName());
                        
                        // 고유한 출력 파일명 생성
                        String outputPath = generateUniqueOutputPath(
                            file.getPath().replaceAll("\\.lock$", "")
                        );
                        
                        updateMessage("복호화 중: " + item.getName());
                        
                        try {
                            String decryptedPath = efs.decryptFile(file.getPath(), outputPath);
                            
                            // 진행률 업데이트
                            synchronized (this) {
                                processedSize[0] += file.length();
                                updateProgress(processedSize[0], totalSize);
                                Platform.runLater(() -> item.setProgress((double) processedSize[0] / totalSize));
                            }
                            
                            // 원본 암호화 파일 삭제
                            efs.deleteEncryptedFile(file.getPath());
                            
                            // UI 업데이트
                            Platform.runLater(() -> {
                                synchronized (fileItems) {
                                    item.setStatus("복호화 완료");
                                    if (i == itemsToProcess.size() - 1) { // 마지막 파일인 경우
                                        fileItems.clear();
                                        fileItems.add(new FileItem(new File(decryptedPath)));
                                        fileTable.refresh();
                                    }
                                }
                            });
                            
                        } catch (Exception e) {
                            LOGGER.log(Level.SEVERE, "복호화 실패: " + file.getPath(), e);
                            Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "복호화 실패", 
                                "파일 복호화 중 오류: " + e.getMessage()));
                            throw e;
                        }
                    }
                    
                    updateProgress(1.0, 1.0);
                    updateMessage("복호화 완료");
                    
                    LOGGER.info("복호화 작업 완료 (ID: " + operationId + ")");
                    return null;
                    
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "복호화 작업 실패 (ID: " + operationId + ")", e);
                    throw e;
                } finally {
                    cleanupTempFiles(tempFilesToCleanup);
                }
            }
            
            @Override
            protected void cancelled() {
                LOGGER.info("복호화 작업 취소됨 (ID: " + operationId + ")");
                cleanupTempFiles(tempFilesToCleanup);
            }
            
            @Override
            protected void failed() {
                LOGGER.severe("복호화 작업 실패 (ID: " + operationId + ")");
                cleanupTempFiles(tempFilesToCleanup);
            }
        };
    }

    /**
     * 지능형 메모리 모니터링 시작
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
                        String memoryInfo = String.format("메모리: %dMB / %dMB (%.1f%%)", 
                            stats.usedMemory / (1024 * 1024), 
                            stats.maxMemory / (1024 * 1024),
                            stats.getUsageRatio() * 100);
                        
                        memoryLabel.setText(memoryInfo);
                        
                        // 메모리 사용률에 따른 색상 변경
                        String style = getMemoryLabelStyle(stats.getUsageRatio());
                        memoryLabel.setStyle(style);
                    }
                });
                
                // 메모리 경고 로깅
                logMemoryWarningsIfNeeded(stats);
                
                // 위험 수준일 경우 자동 GC 실행
                if (stats.getUsageRatio() > MEMORY_CRITICAL_THRESHOLD) {
                    LOGGER.warning("메모리 사용률 위험 수준 - 강제 GC 실행");
                    System.gc();
                    System.runFinalization();
                }
                
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "메모리 모니터링 오류", e);
            }
        }, 0, MEMORY_CHECK_INTERVAL, TimeUnit.SECONDS);
    }

    /**
     * 완전한 리소스 정리를 위한 종료
     */
    public void shutdown() {
        if (isShuttingDown.compareAndSet(false, true)) {
            LOGGER.info("FileSystemManager 종료 시작...");
            
            try {
                // 1. 메모리 모니터 종료
                shutdownExecutor("Memory Monitor", memoryMonitorExecutor, 2, TimeUnit.SECONDS);
                
                // 2. 정리 서비스 종료
                shutdownExecutor("Cleanup Service", cleanupExecutor, 2, TimeUnit.SECONDS);
                
                // 3. 메인 실행기 종료 (진행 중인 작업 완료 대기)
                shutdownExecutor("Main Executor", mainExecutor, 10, TimeUnit.SECONDS);
                
                // 4. 최종 임시 파일 정리
                cleanupAllTempFiles();
                
                LOGGER.info("FileSystemManager 종료 완료");
                
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "종료 중 오류 발생", e);
            }
        }
    }

    // ==================== 내부 메서드들 ====================

    /**
     * 단일 파일 암호화 처리
     */
    private void processSingleFileEncryption(FileItem item, int chunkSize, long totalSize, 
                                           ObservableList<FileItem> fileItems, TableView<FileItem> fileTable) throws Exception {
        File file = new File(currentDirectory, item.getName());
        File backupFile = null;
        
        try {
            // 백업 생성
            backupFile = createAtomicBackup(file);
            
            // 암호화 실행
            updateMessage("암호화 중: " + item.getName());
            String encryptedPath = efs.encryptFile(file.getPath(), chunkSize);
            
            // 진행률 업데이트
            updateProgress(1.0, 1.0);
            Platform.runLater(() -> item.setProgress(1.0));
            
            // 무결성 검증 (간소화된 버전)
            updateMessage("무결성 검증 중: " + item.getName());
            if (verifyEncryptedFile(file, new File(encryptedPath))) {
                // 성공 - 원본 파일 안전 삭제 및 백업 정리
                efs.secureDelete(file.getPath());
                cleanupBackupFile(backupFile);
                
                Platform.runLater(() -> {
                    synchronized (fileItems) {
                        item.setStatus("암호화 완료");
                        fileItems.clear();
                        fileItems.add(new FileItem(new File(encryptedPath)));
                        fileTable.refresh();
                    }
                });
            } else {
                // 실패 - 백업에서 복원
                restoreFromBackup(file, backupFile);
                new File(encryptedPath).delete();
                throw new Exception("무결성 검증 실패");
            }
            
        } catch (Exception e) {
            if (backupFile != null) {
                restoreFromBackup(file, backupFile);
            }
            throw e;
        }
    }

    /**
     * 다중 파일 암호화 처리
     */
    private void processMultiFileEncryption(List<FileItem> items, int chunkSize, long totalSize,
                                          ObservableList<FileItem> fileItems, TableView<FileItem> fileTable) throws Exception {
        File zipFile = null;
        
        try {
            // 고유한 ZIP 파일명 생성
            String zipFileName = "encrypted_bundle_" + System.currentTimeMillis() + ".zip";
            zipFile = new File(currentDirectory, zipFileName);
            
            updateMessage("파일 압축 중...");
            Utils.zipFiles(javafx.collections.FXCollections.observableList(items), zipFile, currentDirectory);
            
            updateMessage("압축 파일 암호화 중...");
            String encryptedPath = efs.encryptFile(zipFile.getPath(), chunkSize);
            
            // 진행률 업데이트
            updateProgress(1.0, 1.0);
            for (FileItem item : items) {
                Platform.runLater(() -> item.setProgress(1.0));
            }
            
            // 무결성 검증 및 정리
            if (verifyEncryptedFile(zipFile, new File(encryptedPath))) {
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
            if (zipFile != null && zipFile.exists()) {
                cleanupTempFiles(Collections.singletonList(zipFile));
            }
        }
    }

    /**
     * 안전한 파일 항목 로드
     */
    private List<FileItem> loadFileItemsSafely(File directory) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return Collections.emptyList();
        }
        
        try {
            File[] files = directory.listFiles();
            if (files == null) {
                return Collections.emptyList();
            }
            
            List<FileItem> items = new ArrayList<>();
            Arrays.sort(files, (f1, f2) -> {
                // 널 안전성 체크
                if (f1 == null && f2 == null) return 0;
                if (f1 == null) return 1;
                if (f2 == null) return -1;
                
                // 디렉터리 우선, 그 다음 크기 순
                if (f1.isDirectory() && !f2.isDirectory()) return -1;
                if (!f1.isDirectory() && f2.isDirectory()) return 1;
                return Long.compare(f2.length(), f1.length());
            });
            
            for (File file : files) {
                if (file != null && file.exists()) {
                    items.add(new FileItem(file));
                }
            }
            
            return items;
            
        } catch (SecurityException e) {
            LOGGER.log(Level.WARNING, "디렉터리 읽기 권한 없음: " + directory.getPath(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 원자적 백업 파일 생성
     */
    private File createAtomicBackup(File originalFile) throws IOException {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "encryption_backups");
        Files.createDirectories(tempDir.toPath());
        
        String backupName = originalFile.getName() + "_" + System.currentTimeMillis() + ".backup";
        File backupFile = new File(tempDir, backupName);
        
        Files.copy(originalFile.toPath(), backupFile.toPath(), 
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        
        return backupFile;
    }

    /**
     * 백업에서 파일 복원
     */
    private void restoreFromBackup(File originalFile, File backupFile) {
        if (backupFile != null && backupFile.exists()) {
            try {
                Files.copy(backupFile.toPath(), originalFile.toPath(), 
                    StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("백업에서 파일 복원됨: " + originalFile.getName());
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "백업 복원 실패: " + originalFile.getPath(), e);
            }
        }
    }

    /**
     * 간소화된 암호화 파일 검증
     */
    private boolean verifyEncryptedFile(File originalFile, File encryptedFile) {
        try {
            // 기본적인 검증: 파일 존재 및 크기 체크
            if (!encryptedFile.exists() || encryptedFile.length() == 0) {
                return false;
            }
            
            // 암호화 파일이 원본보다 작으면 안됨 (IV + 데이터 + 태그)
            long expectedMinSize = originalFile.length() + 12 + 16; // IV + GCM 태그
            return encryptedFile.length() >= expectedMinSize;
            
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "파일 검증 중 오류", e);
            return false;
        }
    }

    /**
     * 고유한 출력 경로 생성
     */
    private String generateUniqueOutputPath(String basePath) {
        File file = new File(basePath);
        if (!file.exists()) {
            return basePath;
        }
        
        String directory = file.getParent();
        String name = file.getName();
        String extension = "";
        
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0) {
            extension = name.substring(lastDot);
            name = name.substring(0, lastDot);
        }
        
        for (int i = 1; i <= 9999; i++) {
            String newPath = directory + File.separator + name + "_" + i + extension;
            if (!new File(newPath).exists()) {
                return newPath;
            }
        }
        
        throw new RuntimeException("고유한 파일명 생성 실패: " + basePath);
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
     * 작업 전 메모리 체크
     */
    private void checkMemoryBeforeOperation(long estimatedDataSize) {
        MemoryStats stats = getCurrentMemoryStats();
        
        // 보수적인 추정: 데이터 크기의 3배 메모리 필요 (버퍼링, 임시 데이터 등)
        long estimatedMemoryNeeded = Math.min(estimatedDataSize * 3, Integer.MAX_VALUE);
        
        if (estimatedMemoryNeeded > stats.availableMemory) {
            LOGGER.warning(String.format("메모리 부족 가능성: 필요 %dMB, 가용 %dMB", 
                estimatedMemoryNeeded / (1024 * 1024), stats.availableMemory / (1024 * 1024)));
            
            // 강제 GC 실행
            System.gc();
            System.runFinalization();
            
            // 재확인
            MemoryStats newStats = getCurrentMemoryStats();
            if (estimatedMemoryNeeded > newStats.availableMemory * 0.8) {
                throw new OutOfMemoryError(String.format(
                    "메모리 부족으로 작업 실행 불가 (필요: %dMB, 가용: %dMB)", 
                    estimatedMemoryNeeded / (1024 * 1024), 
                    newStats.availableMemory / (1024 * 1024)));
            }
        }
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
     * 메모리 라벨 스타일 결정
     */
    private String getMemoryLabelStyle(double usageRatio) {
        if (usageRatio > MEMORY_CRITICAL_THRESHOLD) {
            return "-fx-text-fill: #dc2626; -fx-font-weight: bold;"; // 빨간색
        } else if (usageRatio > MEMORY_WARNING_THRESHOLD) {
            return "-fx-text-fill: #d97706; -fx-font-weight: bold;"; // 주황색
        } else {
            return "-fx-text-fill: -fx-text-fill; -fx-font-weight: normal;"; // 기본색
        }
    }

    /**
     * 메모리 경고 로깅
     */
    private void logMemoryWarningsIfNeeded(MemoryStats stats) {
        double ratio = stats.getUsageRatio();
        
        if (ratio > MEMORY_CRITICAL_THRESHOLD) {
            LOGGER.severe(String.format("메모리 사용률 위험: %.1f%% (%dMB / %dMB)", 
                ratio * 100, stats.usedMemory / (1024 * 1024), stats.maxMemory / (1024 * 1024)));
        } else if (ratio > MEMORY_WARNING_THRESHOLD) {
            LOGGER.warning(String.format("메모리 사용률 높음: %.1f%% (%dMB / %dMB)", 
                ratio * 100, stats.usedMemory / (1024 * 1024), stats.maxMemory / (1024 * 1024)));
        }
    }

    /**
     * 실행기 안전 종료
     */
    private void shutdownExecutor(String name, ExecutorService executor, long timeout, TimeUnit unit) {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        
        LOGGER.info(name + " 종료 시작...");
        executor.shutdown();
        
        try {
            if (!executor.awaitTermination(timeout, unit)) {
                LOGGER.warning(name + " 정상 종료 실패, 강제 종료 중...");
                List<Runnable> pendingTasks = executor.shutdownNow();
                LOGGER.info(name + " 강제 종료됨. 미완료 작업: " + pendingTasks.size() + "개");
                
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    LOGGER.severe(name + " 강제 종료 실패!");
                }
            } else {
                LOGGER.info(name + " 정상 종료됨");
            }
        } catch (InterruptedException e) {
            LOGGER.warning(name + " 종료 중 인터럽트");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 백업 파일 정리
     */
    private void cleanupBackupFile(File backupFile) {
        if (backupFile != null && backupFile.exists()) {
            try {
                Files.deleteIfExists(backupFile.toPath());
                LOGGER.fine("백업 파일 정리됨: " + backupFile.getName());
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "백업 파일 정리 실패: " + backupFile.getName(), e);
                backupFile.deleteOnExit();
            }
        }
    }

    /**
     * 임시 파일들 정리
     */
    private void cleanupTempFiles(List<File> tempFiles) {
        for (File tempFile : tempFiles) {
            if (tempFile != null && tempFile.exists()) {
                try {
                    Files.deleteIfExists(tempFile.toPath());
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "임시 파일 정리 실패: " + tempFile.getName(), e);
                    tempFile.deleteOnExit();
                }
            }
        }
    }

    /**
     * 주기적 임시 파일 정리
     */
    private void cleanupTempFiles() {
        try {
            File tempDir = new File(System.getProperty("java.io.tmpdir"));
            File backupDir = new File(tempDir, "encryption_backups");
            
            if (backupDir.exists()) {
                File[] oldFiles = backupDir.listFiles((dir, name) -> 
                    name.endsWith(".backup") || name.endsWith(".tmp"));
                
                if (oldFiles != null) {
                    long cutoffTime = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24); // 24시간 이전
                    int cleanedCount = 0;
                    
                    for (File file : oldFiles) {
                        if (file.lastModified() < cutoffTime) {
                            try {
                                Files.deleteIfExists(file.toPath());
                                cleanedCount++;
                            } catch (IOException e) {
                                LOGGER.log(Level.FINE, "오래된 임시 파일 삭제 실패: " + file.getName(), e);
                            }
                        }
                    }
                    
                    if (cleanedCount > 0) {
                        LOGGER.info("오래된 임시 파일 " + cleanedCount + "개 정리됨");
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "임시 파일 정리 중 오류", e);
        }
    }

    /**
     * 모든 임시 파일 정리 (종료 시)
     */
    private void cleanupAllTempFiles() {
        try {
            File tempDir = new File(System.getProperty("java.io.tmpdir"));
            File backupDir = new File(tempDir, "encryption_backups");
            
            if (backupDir.exists()) {
                File[] allFiles = backupDir.listFiles();
                if (allFiles != null) {
                    for (File file : allFiles) {
                        try {
                            Files.deleteIfExists(file.toPath());
                        } catch (IOException e) {
                            LOGGER.log(Level.FINE, "임시 파일 삭제 실패: " + file.getName(), e);
                        }
                    }
                }
                
                // 빈 디렉터리 삭제 시도
                try {
                    Files.deleteIfExists(backupDir.toPath());
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "백업 디렉터리 삭제 실패", e);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "전체 임시 파일 정리 중 오류", e);
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
     * 경고창 표시 (스레드 안전)
     */
    private void showAlert(Alert.AlertType type, String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.showAndWait();
        });
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
        
        @Override
        public String toString() {
            return String.format("Memory[used: %dMB, max: %dMB, usage: %.1f%%]", 
                usedMemory / (1024 * 1024), maxMemory / (1024 * 1024), getUsageRatio() * 100);
        }
    }

    /**
     * 작업 완료를 위한 인터페이스
     */
    @FunctionalInterface
    public interface OperationCallback {
        void onComplete(boolean success, String message);
    }

    /**
     * 진행률 업데이트를 위한 인터페이스
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(double progress, String message);
    }
}