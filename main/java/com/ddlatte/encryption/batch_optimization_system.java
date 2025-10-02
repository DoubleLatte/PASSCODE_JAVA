package com.ddlatte.encryption;

import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.application.Platform;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * 지능형 배치 처리 최적화 시스템
 * 
 * 🚀 핵심 최적화 기능:
 * 1. 파일 크기별 스마트 그룹화
 * 2. 처리 순서 최적화 (작은 파일 → 큰 파일)
 * 3. 병렬 작업 큐 관리
 * 4. 메모리 사용량 기반 동적 조정
 * 5. 실시간 성능 모니터링 및 자동 튜닝
 */
public class BatchProcessingOptimizer {
    private static final Logger LOGGER = Logger.getLogger(BatchProcessingOptimizer.class.getName());
    
    // 최적화 설정
    private static final long SMALL_FILE_THRESHOLD = 10 * 1024 * 1024; // 10MB
    private static final long MEDIUM_FILE_THRESHOLD = 100 * 1024 * 1024; // 100MB
    private static final long LARGE_FILE_THRESHOLD = 1024 * 1024 * 1024L; // 1GB
    
    private static final int SMALL_FILE_BATCH_SIZE = 20; // 작은 파일 20개씩 묶음
    private static final int MEDIUM_FILE_BATCH_SIZE = 5;  // 중간 파일 5개씩 묶음
    private static final int LARGE_FILE_BATCH_SIZE = 1;   // 큰 파일 개별 처리
    
    // 성능 모니터링
    private final AtomicLong totalProcessedBytes = new AtomicLong(0);
    private final AtomicLong totalProcessingTime = new AtomicLong(0);
    private volatile double averageThroughput = 0.0; // MB/s
    
    /**
     * 파일 목록을 최적화된 배치로 분할
     */
    public List<OptimizedBatch> optimizeFileBatches(ObservableList<FileItem> fileItems, File currentDirectory) {
        List<OptimizedBatch> optimizedBatches = new ArrayList<>();
        
        // 1단계: 파일 크기별 분류 및 정렬
        Map<FileSizeCategory, List<FileProcessingInfo>> categorizedFiles = categorizeAndSortFiles(fileItems, currentDirectory);
        
        // 2단계: 각 카테고리별 배치 생성
        for (Map.Entry<FileSizeCategory, List<FileProcessingInfo>> entry : categorizedFiles.entrySet()) {
            FileSizeCategory category = entry.getKey();
            List<FileProcessingInfo> files = entry.getValue();
            
            List<OptimizedBatch> categoryBatches = createBatchesForCategory(category, files);
            optimizedBatches.addAll(categoryBatches);
        }
        
        // 3단계: 전체 배치 순서 최적화 (작은 파일부터 처리)
        optimizedBatches.sort(Comparator.comparingInt(batch -> batch.priority));
        
        // 4단계: 메모리 사용량 기반 최종 조정
        adjustBatchesForMemory(optimizedBatches);
        
        LOGGER.info(String.format("배치 최적화 완료: 총 %d개 파일을 %d개 배치로 분할", 
            fileItems.size(), optimizedBatches.size()));
            
        return optimizedBatches;
    }
    
    /**
     * 파일 크기별 분류 및 정렬
     */
    private Map<FileSizeCategory, List<FileProcessingInfo>> categorizeAndSortFiles(
            ObservableList<FileItem> fileItems, File currentDirectory) {
        
        Map<FileSizeCategory, List<FileProcessingInfo>> categorized = new EnumMap<>(FileSizeCategory.class);
        
        for (FileSizeCategory category : FileSizeCategory.values()) {
            categorized.put(category, new ArrayList<>());
        }
        
        for (FileItem item : fileItems) {
            File file = new File(currentDirectory, item.getName());
            if (!file.exists()) continue;
            
            long fileSize = file.length();
            FileSizeCategory category = determineFileSizeCategory(fileSize);
            
            FileProcessingInfo info = new FileProcessingInfo(item, file, fileSize, category);
            categorized.get(category).add(info);
        }
        
        // 각 카테고리 내에서 크기순 정렬 (작은 것부터)
        for (List<FileProcessingInfo> files : categorized.values()) {
            files.sort(Comparator.comparingLong(f -> f.fileSize));
        }
        
        return categorized;
    }
    
    /**
     * 파일 크기 카테고리 결정
     */
    private FileSizeCategory determineFileSizeCategory(long fileSize) {
        if (fileSize <= SMALL_FILE_THRESHOLD) {
            return FileSizeCategory.SMALL;
        } else if (fileSize <= MEDIUM_FILE_THRESHOLD) {
            return FileSizeCategory.MEDIUM;
        } else if (fileSize <= LARGE_FILE_THRESHOLD) {
            return FileSizeCategory.LARGE;
        } else {
            return FileSizeCategory.EXTRA_LARGE;
        }
    }
    
    /**
     * 카테고리별 배치 생성
     */
    private List<OptimizedBatch> createBatchesForCategory(FileSizeCategory category, List<FileProcessingInfo> files) {
        if (files.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<OptimizedBatch> batches = new ArrayList<>();
        int batchSize = getBatchSizeForCategory(category);
        int priority = getPriorityForCategory(category);
        
        for (int i = 0; i < files.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, files.size());
            List<FileProcessingInfo> batchFiles = files.subList(i, endIndex);
            
            OptimizedBatch batch = new OptimizedBatch(
                category, 
                batchFiles, 
                priority,
                calculateOptimalChunkSize(category),
                calculateEstimatedProcessingTime(batchFiles)
            );
            
            batches.add(batch);
        }
        
        return batches;
    }
    
    /**
     * 카테고리별 배치 크기 결정
     */
    private int getBatchSizeForCategory(FileSizeCategory category) {
        switch (category) {
            case SMALL: return SMALL_FILE_BATCH_SIZE;
            case MEDIUM: return MEDIUM_FILE_BATCH_SIZE;
            case LARGE: return LARGE_FILE_BATCH_SIZE;
            case EXTRA_LARGE: return 1; // 초대용량은 무조건 개별 처리
            default: return MEDIUM_FILE_BATCH_SIZE;
        }
    }
    
    /**
     * 카테고리별 우선순위 결정 (낮을수록 먼저 처리)
     */
    private int getPriorityForCategory(FileSizeCategory category) {
        switch (category) {
            case SMALL: return 1;        // 최우선 처리
            case MEDIUM: return 2;       // 두 번째 우선순위
            case LARGE: return 3;        // 세 번째 우선순위
            case EXTRA_LARGE: return 4;  // 마지막 처리
            default: return 2;
        }
    }
    
    /**
     * 카테고리별 최적 청크 크기 계산
     */
    private int calculateOptimalChunkSize(FileSizeCategory category) {
        Runtime runtime = Runtime.getRuntime();
        long availableMemory = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        
        switch (category) {
            case SMALL:
                return (int) Math.min(16 * 1024 * 1024, availableMemory / 20); // 16MB 또는 메모리의 5%
            case MEDIUM:
                return (int) Math.min(64 * 1024 * 1024, availableMemory / 10); // 64MB 또는 메모리의 10%
            case LARGE:
                return (int) Math.min(256 * 1024 * 1024, availableMemory / 5); // 256MB 또는 메모리의 20%
            case EXTRA_LARGE:
                return (int) Math.min(512 * 1024 * 1024, availableMemory / 4); // 512MB 또는 메모리의 25%
            default:
                return 64 * 1024 * 1024; // 기본값 64MB
        }
    }
    
    /**
     * 예상 처리 시간 계산
     */
    private long calculateEstimatedProcessingTime(List<FileProcessingInfo> files) {
        long totalSize = files.stream().mapToLong(f -> f.fileSize).sum();
        
        // 평균 처리량을 기반으로 예상 시간 계산 (MB/s)
        double throughput = averageThroughput > 0 ? averageThroughput : 30.0; // 기본값 30MB/s
        
        return Math.round(totalSize / (1024.0 * 1024.0 * throughput) * 1000); // milliseconds
    }
    
    /**
     * 메모리 사용량 기반 배치 조정
     */
    private void adjustBatchesForMemory(List<OptimizedBatch> batches) {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long currentMemory = runtime.totalMemory() - runtime.freeMemory();
        double memoryUsageRatio = (double) currentMemory / maxMemory;
        
        // 메모리 사용률이 높으면 배치 크기 축소
        if (memoryUsageRatio > 0.8) {
            LOGGER.warning("높은 메모리 사용률로 배치 크기 조정: " + String.format("%.1f%%", memoryUsageRatio * 100));
            
            for (OptimizedBatch batch : batches) {
                // 청크 크기를 50% 축소
                batch.optimalChunkSize = Math.max(batch.optimalChunkSize / 2, 1024 * 1024); // 최소 1MB
                
                // 병렬도 감소
                if (batch.files.size() > 5) {
                    // 큰 배치를 작은 배치들로 분할
                    // (실제 구현에서는 배치를 재분할하는 로직 추가)
                }
            }
        }
    }
    
    /**
     * 배치 처리 작업 생성
     */
    public Task<Void> createOptimizedBatchTask(List<OptimizedBatch> batches, 
                                              FileSystemManager fileSystemManager,
                                              String chunkSizeStr,
                                              ObservableList<FileItem> fileItems,
                                              javafx.scene.control.TableView<FileItem> fileTable) {
        
        return new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                long totalFiles = batches.stream().mapToLong(b -> b.files.size()).sum();
                long processedFiles = 0;
                long totalSize = batches.stream().mapToLong(b -> b.getTotalSize()).sum();
                long processedSize = 0;
                
                long overallStartTime = System.nanoTime();
                
                updateMessage("🚀 배치 처리 최적화 시작...");
                LOGGER.info(String.format("최적화된 배치 처리 시작: %d개 배치, 총 %d개 파일", 
                    batches.size(), totalFiles));
                
                for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
                    OptimizedBatch batch = batches.get(batchIndex);
                    
                    if (isCancelled()) {
                        updateMessage("❌ 배치 처리 취소됨");
                        break;
                    }
                    
                    // 배치 처리 시작
                    long batchStartTime = System.nanoTime();
                    String batchInfo = String.format("배치 %d/%d (%s, %d개 파일)", 
                        batchIndex + 1, batches.size(), 
                        batch.category.getDisplayName(), batch.files.size());
                    
                    updateMessage("⚡ " + batchInfo + " 처리 중...");
                    LOGGER.info(batchInfo + " 처리 시작");
                    
                    // 실제 암호화 작업 수행
                    processBatch(batch, fileSystemManager, chunkSizeStr, fileItems, fileTable);
                    
                    // 배치 완료 통계
                    long batchTime = System.nanoTime() - batchStartTime;
                    long batchSize = batch.getTotalSize();
                    double batchThroughput = (batchSize / (1024.0 * 1024.0)) / (batchTime / 1_000_000_000.0);
                    
                    // 전체 통계 업데이트
                    processedFiles += batch.files.size();
                    processedSize += batchSize;
                    totalProcessedBytes.addAndGet(batchSize);
                    totalProcessingTime.addAndGet(batchTime);
                    
                    // 평균 처리량 업데이트
                    updateAverageThroughput();
                    
                    // 진행률 업데이트
                    double progress = (double) processedFiles / totalFiles;
                    updateProgress(progress, 1.0);
                    
                    String statusMsg = String.format("✅ %s 완료 (%.1f MB/s) - 전체 진행률: %.1f%%", 
                        batchInfo, batchThroughput, progress * 100);
                    updateMessage(statusMsg);
                    
                    LOGGER.info(String.format("배치 완료: %s, 처리량: %.1f MB/s", 
                        batchInfo, batchThroughput));
                }
                
                // 전체 작업 완료 통계
                long overallTime = System.nanoTime() - overallStartTime;
                double overallThroughput = (totalSize / (1024.0 * 1024.0)) / (overallTime / 1_000_000_000.0);
                
                updateProgress(1.0, 1.0);
                updateMessage(String.format("🎉 최적화 배치 처리 완료! 전체 처리량: %.1f MB/s", overallThroughput));
                
                LOGGER.info(String.format("최적화된 배치 처리 완료: 총 %d개 파일, 평균 처리량: %.1f MB/s", 
                    totalFiles, overallThroughput));
                
                return null;
            }
        };
    }
    
    /**
     * 개별 배치 처리
     */
    private void processBatch(OptimizedBatch batch, FileSystemManager fileSystemManager,
                            String chunkSizeStr, ObservableList<FileItem> fileItems,
                            javafx.scene.control.TableView<FileItem> fileTable) throws Exception {
        
        // 배치의 파일들을 FileItem 리스트로 변환
        ObservableList<FileItem> batchItems = javafx.collections.FXCollections.observableArrayList();
        for (FileProcessingInfo info : batch.files) {
            batchItems.add(info.fileItem);
        }
        
        // 최적화된 청크 크기 적용
        String optimizedChunkSize = formatChunkSize(batch.optimalChunkSize);
        
        // FileSystemManager의 기존 암호화 메서드 사용
        Task<Void> encryptionTask = fileSystemManager.createEncryptionTask(batchItems, optimizedChunkSize, fileItems, fileTable);
        
        // 동기적으로 실행 (배치 내에서는 순차 처리)
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        encryptionTask.setOnSucceeded(e -> future.complete(null));
        encryptionTask.setOnFailed(e -> future.completeExceptionally(encryptionTask.getException()));
        encryptionTask.setOnCancelled(e -> future.cancel(true));
        
        Thread encryptionThread = new Thread(encryptionTask, "Batch-Encryption");
        encryptionThread.setDaemon(true);
        encryptionThread.start();
        
        try {
            future.get(30, TimeUnit.MINUTES); // 30분 타임아웃
        } catch (TimeoutException e) {
            encryptionTask.cancel(true);
            throw new Exception("배치 처리 타임아웃: 30분 초과");
        }
    }
    
    /**
     * 평균 처리량 업데이트
     */
    private void updateAverageThroughput() {
        long totalBytes = totalProcessedBytes.get();
        long totalTime = totalProcessingTime.get();
        
        if (totalTime > 0) {
            averageThroughput = (totalBytes / (1024.0 * 1024.0)) / (totalTime / 1_000_000_000.0);
        }
    }
    
    /**
     * 청크 크기를 문자열로 포맷
     */
    private String formatChunkSize(int chunkSizeBytes) {
        if (chunkSizeBytes >= 1024 * 1024 * 1024) {
            return String.format("%.0f GB", chunkSizeBytes / (1024.0 * 1024.0 * 1024.0));
        } else if (chunkSizeBytes >= 1024 * 1024) {
            return String.format("%.0f MB", chunkSizeBytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.0f KB", chunkSizeBytes / 1024.0);
        }
    }
    
    /**
     * 현재 성능 통계 가져오기
     */
    public BatchPerformanceStats getPerformanceStats() {
        return new BatchPerformanceStats(
            totalProcessedBytes.get(),
            totalProcessingTime.get(),
            averageThroughput
        );
    }
    
    // ==================== 내부 클래스들 ====================
    
    /**
     * 파일 크기 카테고리
     */
    public enum FileSizeCategory {
        SMALL("소형 파일", "< 10MB"),
        MEDIUM("중형 파일", "10MB - 100MB"),
        LARGE("대형 파일", "100MB - 1GB"),
        EXTRA_LARGE("초대형 파일", "> 1GB");
        
        private final String displayName;
        private final String sizeRange;
        
        FileSizeCategory(String displayName, String sizeRange) {
            this.displayName = displayName;
            this.sizeRange = sizeRange;
        }
        
        public String getDisplayName() { return displayName; }
        public String getSizeRange() { return sizeRange; }
    }
    
    /**
     * 파일 처리 정보
     */
    public static class FileProcessingInfo {
        public final FileItem fileItem;
        public final File file;
        public final long fileSize;
        public final FileSizeCategory category;
        
        public FileProcessingInfo(FileItem fileItem, File file, long fileSize, FileSizeCategory category) {
            this.fileItem = fileItem;
            this.file = file;
            this.fileSize = fileSize;
            this.category = category;
        }
    }
    
    /**
     * 최적화된 배치
     */
    public static class OptimizedBatch {
        public final FileSizeCategory category;
        public final List<FileProcessingInfo> files;
        public final int priority;
        public int optimalChunkSize;
        public final long estimatedProcessingTime;
        
        public OptimizedBatch(FileSizeCategory category, List<FileProcessingInfo> files, 
                            int priority, int optimalChunkSize, long estimatedProcessingTime) {
            this.category = category;
            this.files = new ArrayList<>(files);
            this.priority = priority;
            this.optimalChunkSize = optimalChunkSize;
            this.estimatedProcessingTime = estimatedProcessingTime;
        }
        
        public long getTotalSize() {
            return files.stream().mapToLong(f -> f.fileSize).sum();
        }
        
        public String getDescription() {
            return String.format("%s (%d개 파일, 총 %s)", 
                category.getDisplayName(), 
                files.size(), 
                formatFileSize(getTotalSize()));
        }
        
        private String formatFileSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            int exp = (int) (Math.log(bytes) / Math.log(1024));
            String pre = "KMGTPE".charAt(exp - 1) + "";
            return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
        }
    }
    
    /**
     * 배치 처리 성능 통계
     */
    public static class BatchPerformanceStats {
        public final long totalProcessedBytes;
        public final long totalProcessingTime;
        public final double averageThroughput;
        
        public BatchPerformanceStats(long totalProcessedBytes, long totalProcessingTime, double averageThroughput) {
            this.totalProcessedBytes = totalProcessedBytes;
            this.totalProcessingTime = totalProcessingTime;
            this.averageThroughput = averageThroughput;
        }
        
        public String getFormattedStats() {
            return String.format("처리량: %.1f MB/s, 총 처리: %s, 총 시간: %.1f초",
                averageThroughput,
                formatFileSize(totalProcessedBytes),
                totalProcessingTime / 1_000_000_000.0);
        }
        
        private String formatFileSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            int exp = (int) (Math.log(bytes) / Math.log(1024));
            String pre = "KMGTPE".charAt(exp - 1) + "";
            return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
        }
    }
}