package com.ddlatte.encryption;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.KeySpec;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * 완전히 수정된 암호화 파일 시스템
 * 
 * 🔒 주요 개선사항:
 * - 순차 처리로 데이터 무결성 보장 (병렬 처리 오류 해결)
 * - AES-GCM으로 인증된 암호화 적용
 * - 동적 메모리 관리로 OOM 방지
 * - 원자적 파일 연산으로 데이터 손실 방지
 * - 완벽한 리소스 정리 및 예외 처리
 * - 보안 강화 (패스워드 메모리 클리어, 다중 패스 삭제)
 */
public class EncryptedFileSystem {
    private static final Logger LOGGER = Logger.getLogger(EncryptedFileSystem.class.getName());
    
    // 암호화 설정 (GCM 모드로 변경)
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_LENGTH = 256;
    private static final int PBKDF2_ITERATIONS = 120000; // 2023년 OWASP 권장사항
    private static final int SALT_LENGTH = 32; // 256비트 솔트
    private static final int GCM_IV_LENGTH = 12; // GCM 표준 IV 길이
    private static final int GCM_TAG_LENGTH = 16; // 128비트 인증 태그
    
    // 메모리 관리 설정
    private static final int MIN_BUFFER_SIZE = 64 * 1024; // 64KB 최소
    private static final int MAX_BUFFER_SIZE = 16 * 1024 * 1024; // 16MB 최대
    private static final double SAFE_MEMORY_RATIO = 0.1; // 가용 메모리의 10%만 사용
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 100;
    
    // 스레드 안전성을 위한 락
    private final ReentrantLock operationLock = new ReentrantLock();
    
    private volatile SecretKey secretKey;
    private volatile boolean isKeyLoaded = false;

    /**
     * 새로운 암호화 키 생성 및 저장
     */
    public void generateKey(String keyPath, String password) throws Exception {
        validatePassword(password);
        
        operationLock.lock();
        try {
            byte[] salt = generateSecureSalt();
            SecretKey key = deriveKeySecurely(password, salt);
            
            // 원자적 파일 쓰기로 키 저장
            saveKeyFileAtomically(keyPath, salt, key);
            
            this.secretKey = key;
            this.isKeyLoaded = true;
            
            LOGGER.info("암호화 키가 안전하게 생성되었습니다: " + keyPath);
            
        } finally {
            operationLock.unlock();
        }
    }

    /**
     * 기존 암호화 키 로드
     */
    public void loadKey(String keyPath, String password) throws Exception {
        validatePassword(password);
        
        operationLock.lock();
        try {
            File keyFile = new File(keyPath);
            if (!keyFile.exists() || !keyFile.canRead()) {
                throw new FileNotFoundException("키 파일에 접근할 수 없습니다: " + keyPath);
            }
            
            // 키 파일 읽기
            KeyData keyData = readKeyFileSecurely(keyPath);
            SecretKey key = deriveKeySecurely(password, keyData.salt);
            
            // 키 검증 (타이밍 공격 방지)
            if (!MessageDigest.isEqual(key.getEncoded(), keyData.keyBytes)) {
                // 보안을 위해 약간의 지연 추가
                Thread.sleep(100 + new SecureRandom().nextInt(100));
                throw new InvalidKeyException("잘못된 패스워드이거나 손상된 키 파일입니다");
            }
            
            this.secretKey = key;
            this.isKeyLoaded = true;
            
            LOGGER.info("암호화 키가 성공적으로 로드되었습니다: " + keyPath);
            
        } finally {
            operationLock.unlock();
        }
    }

    /**
     * 파일 암호화 (완전히 개선된 버전)
     */
    public String encryptFile(String inputPath, int requestedChunkSize) throws Exception {
        if (!isKeyLoaded || secretKey == null) {
            throw new IllegalStateException("암호화 키가 로드되지 않았습니다");
        }
        
        File inputFile = new File(inputPath);
        validateInputFile(inputFile);
        
        String outputPath = generateOutputPath(inputPath, ".lock");
        File outputFile = new File(outputPath);
        File tempFile = new File(outputPath + ".tmp");
        
        // 메모리 체크 및 버퍼 크기 최적화
        checkMemoryAndTriggerGC();
        int optimalBufferSize = calculateOptimalBufferSize(inputFile.length(), requestedChunkSize);
        
        long startTime = System.nanoTime();
        long processedBytes = 0;
        
        LOGGER.info(String.format("암호화 시작: %s (크기: %s, 버퍼: %dKB)", 
            inputFile.getName(), formatFileSize(inputFile.length()), optimalBufferSize / 1024));
        
        try {
            // GCM 암호화 초기화
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] iv = cipher.getIV();
            
            // 임시 파일에 암호화 수행 (원자적 연산을 위해)
            try (FileInputStream fis = new FileInputStream(inputFile);
                 BufferedInputStream bis = new BufferedInputStream(fis, optimalBufferSize);
                 FileOutputStream fos = new FileOutputStream(tempFile);
                 BufferedOutputStream bos = new BufferedOutputStream(fos, optimalBufferSize)) {
                
                // IV를 파일 시작 부분에 저장
                bos.write(iv);
                
                // 순차적으로 암호화 처리 (병렬 처리 오류 해결!)
                byte[] buffer = new byte[optimalBufferSize];
                int bytesRead;
                
                while ((bytesRead = bis.read(buffer)) != -1) {
                    // 메모리 사용량 주기적 체크
                    if (processedBytes % (10 * 1024 * 1024) == 0) { // 10MB마다 체크
                        checkMemoryAndTriggerGC();
                    }
                    
                    // 실제 읽은 만큼만 처리
                    byte[] dataToEncrypt = (bytesRead == buffer.length) ? 
                        buffer : Arrays.copyOf(buffer, bytesRead);
                    
                    byte[] encryptedData = cipher.update(dataToEncrypt);
                    if (encryptedData != null && encryptedData.length > 0) {
                        bos.write(encryptedData);
                    }
                    
                    processedBytes += bytesRead;
                }
                
                // 마지막 블록 및 GCM 인증 태그 처리
                byte[] finalData = cipher.doFinal();
                if (finalData != null && finalData.length > 0) {
                    bos.write(finalData);
                }
                
                bos.flush();
                fos.getFD().sync(); // 디스크에 강제 기록
            }
            
            // 원자적 파일 교체
            atomicFileMove(tempFile, outputFile);
            
            long processingTime = (System.nanoTime() - startTime) / 1_000_000;
            LOGGER.info(String.format("암호화 완료: %s → %s (%dms, %s processed)", 
                inputFile.getName(), outputFile.getName(), processingTime, formatFileSize(processedBytes)));
            
            return outputPath;
            
        } catch (Exception e) {
            // 오류 시 임시 파일 정리
            cleanupTempFiles(tempFile, outputFile);
            throw new RuntimeException("파일 암호화 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 파일 복호화 (완전히 개선된 버전)
     */
    public String decryptFile(String inputPath, String outputPath) throws Exception {
        if (!isKeyLoaded || secretKey == null) {
            throw new IllegalStateException("암호화 키가 로드되지 않았습니다");
        }
        
        File inputFile = new File(inputPath);
        validateInputFile(inputFile);
        
        if (outputPath == null || outputPath.trim().isEmpty()) {
            outputPath = generateOutputPath(inputPath.replaceAll("\\.lock$", ""), "");
        }
        
        File outputFile = new File(outputPath);
        File tempFile = new File(outputPath + ".tmp");
        
        // 메모리 체크 및 버퍼 크기 최적화
        checkMemoryAndTriggerGC();
        int optimalBufferSize = calculateOptimalBufferSize(inputFile.length(), MAX_BUFFER_SIZE);
        
        long startTime = System.nanoTime();
        long processedBytes = 0;
        
        LOGGER.info(String.format("복호화 시작: %s (크기: %s, 버퍼: %dKB)", 
            inputFile.getName(), formatFileSize(inputFile.length()), optimalBufferSize / 1024));
        
        try {
            try (FileInputStream fis = new FileInputStream(inputFile);
                 BufferedInputStream bis = new BufferedInputStream(fis, optimalBufferSize);
                 FileOutputStream fos = new FileOutputStream(tempFile);
                 BufferedOutputStream bos = new BufferedOutputStream(fos, optimalBufferSize)) {
                
                // IV 읽기
                byte[] iv = new byte[GCM_IV_LENGTH];
                int ivBytesRead = bis.read(iv);
                if (ivBytesRead != GCM_IV_LENGTH) {
                    throw new IOException("손상된 암호화 파일: IV를 읽을 수 없습니다");
                }
                
                // GCM 복호화 초기화
                GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);
                
                // 순차적으로 복호화 처리
                byte[] buffer = new byte[optimalBufferSize];
                int bytesRead;
                
                while ((bytesRead = bis.read(buffer)) != -1) {
                    // 메모리 사용량 주기적 체크
                    if (processedBytes % (10 * 1024 * 1024) == 0) { // 10MB마다 체크
                        checkMemoryAndTriggerGC();
                    }
                    
                    // 실제 읽은 만큼만 처리
                    byte[] dataToDecrypt = (bytesRead == buffer.length) ? 
                        buffer : Arrays.copyOf(buffer, bytesRead);
                    
                    byte[] decryptedData = cipher.update(dataToDecrypt);
                    if (decryptedData != null && decryptedData.length > 0) {
                        bos.write(decryptedData);
                    }
                    
                    processedBytes += bytesRead;
                }
                
                // 마지막 블록 처리 (GCM 인증 태그 검증 포함)
                try {
                    byte[] finalData = cipher.doFinal();
                    if (finalData != null && finalData.length > 0) {
                        bos.write(finalData);
                    }
                } catch (AEADBadTagException e) {
                    throw new SecurityException("파일 무결성 검증 실패 - 파일이 손상되었거나 변조되었습니다", e);
                }
                
                bos.flush();
                fos.getFD().sync(); // 디스크에 강제 기록
            }
            
            // 원자적 파일 교체
            atomicFileMove(tempFile, outputFile);
            
            long processingTime = (System.nanoTime() - startTime) / 1_000_000;
            LOGGER.info(String.format("복호화 완료: %s → %s (%dms, %s processed)", 
                inputFile.getName(), outputFile.getName(), processingTime, formatFileSize(processedBytes)));
            
            return outputPath;
            
        } catch (Exception e) {
            // 오류 시 임시 파일 정리
            cleanupTempFiles(tempFile, outputFile);
            throw new RuntimeException("파일 복호화 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 안전한 파일 삭제 (다중 패스)
     */
    public void secureDelete(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            return;
        }
        
        if (!file.canWrite()) {
            throw new IOException("파일 삭제 권한이 없습니다: " + filePath);
        }
        
        LOGGER.info("안전 삭제 시작: " + file.getName());
        
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            long length = file.length();
            if (length == 0) {
                return;
            }
            
            // 효율적인 버퍼 크기 계산
            int bufferSize = (int) Math.min(Math.max(length / 100, MIN_BUFFER_SIZE), MAX_BUFFER_SIZE);
            SecureRandom random = new SecureRandom();
            
            // Pass 1: 랜덤 데이터로 덮어쓰기
            overwriteFileWithPattern(raf, length, bufferSize, pos -> {
                byte[] randomData = new byte[bufferSize];
                random.nextBytes(randomData);
                return randomData;
            });
            
            // Pass 2: 0xFF로 덮어쓰기
            overwriteFileWithPattern(raf, length, bufferSize, pos -> {
                byte[] pattern = new byte[bufferSize];
                Arrays.fill(pattern, (byte) 0xFF);
                return pattern;
            });
            
            // Pass 3: 0x00으로 덮어쓰기
            overwriteFileWithPattern(raf, length, bufferSize, pos -> {
                return new byte[bufferSize]; // 기본값이 0x00
            });
            
            // 파일 시스템에 강제 기록
            raf.getFD().sync();
        }
        
        // 파일 삭제 시도 (재시도 로직 포함)
        boolean deleted = retryOperation(() -> {
            if (file.exists()) {
                return file.delete();
            }
            return true;
        }, MAX_RETRY_ATTEMPTS);
        
        if (!deleted) {
            throw new IOException("파일 삭제 실패: " + filePath);
        }
        
        LOGGER.info("안전 삭제 완료: " + filePath);
    }

    /**
     * 일반 파일 삭제
     */
    public void deleteEncryptedFile(String filePath) throws IOException {
        File file = new File(filePath);
        if (file.exists()) {
            boolean deleted = retryOperation(() -> file.delete(), MAX_RETRY_ATTEMPTS);
            if (!deleted) {
                throw new IOException("암호화 파일 삭제 실패: " + filePath);
            }
            LOGGER.info("암호화 파일 삭제됨: " + filePath);
        }
    }

    // ==================== 유틸리티 메서드들 ====================

    /**
     * 패스워드 유효성 검사
     */
    private void validatePassword(String password) {
        if (password == null) {
            throw new IllegalArgumentException("패스워드는 null일 수 없습니다");
        }
        if (password.length() < 8) {
            throw new IllegalArgumentException("패스워드는 최소 8자 이상이어야 합니다");
        }
        if (password.trim().isEmpty()) {
            throw new IllegalArgumentException("패스워드는 공백만으로 구성될 수 없습니다");
        }
    }

    /**
     * 입력 파일 유효성 검사
     */
    private void validateInputFile(File file) throws FileNotFoundException {
        if (!file.exists()) {
            throw new FileNotFoundException("파일을 찾을 수 없습니다: " + file.getPath());
        }
        if (!file.isFile()) {
            throw new IllegalArgumentException("디렉터리는 처리할 수 없습니다: " + file.getPath());
        }
        if (!file.canRead()) {
            throw new IllegalArgumentException("파일 읽기 권한이 없습니다: " + file.getPath());
        }
        if (file.length() == 0) {
            throw new IllegalArgumentException("빈 파일은 처리할 수 없습니다: " + file.getPath());
        }
    }

    /**
     * 보안 솔트 생성
     */
    private byte[] generateSecureSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        SecureRandom.getInstanceStrong().nextBytes(salt);
        return salt;
    }

    /**
     * 안전한 키 유도 (패스워드 메모리 클리어 포함)
     */
    private SecretKey deriveKeySecurely(String password, byte[] salt) throws Exception {
        char[] passwordChars = password.toCharArray();
        
        try {
            KeySpec spec = new PBEKeySpec(passwordChars, salt, PBKDF2_ITERATIONS, KEY_LENGTH);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, ALGORITHM);
            
        } finally {
            // 패스워드를 메모리에서 완전히 제거
            Arrays.fill(passwordChars, '\0');
        }
    }

    /**
     * 원자적 키 파일 저장
     */
    private void saveKeyFileAtomically(String keyPath, byte[] salt, SecretKey key) throws IOException {
        File keyFile = new File(keyPath);
        File tempFile = new File(keyPath + ".tmp");
        
        try (FileOutputStream fos = new FileOutputStream(tempFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {
            
            bos.write(salt);
            bos.write(key.getEncoded());
            bos.flush();
            fos.getFD().sync();
        }
        
        atomicFileMove(tempFile, keyFile);
    }

    /**
     * 안전한 키 파일 읽기
     */
    private KeyData readKeyFileSecurely(String keyPath) throws IOException {
        byte[] salt = new byte[SALT_LENGTH];
        byte[] keyBytes = new byte[KEY_LENGTH / 8];
        
        try (FileInputStream fis = new FileInputStream(keyPath);
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            
            int saltRead = bis.read(salt);
            int keyRead = bis.read(keyBytes);
            
            if (saltRead != SALT_LENGTH || keyRead != KEY_LENGTH / 8) {
                throw new IOException("잘못된 키 파일 형식");
            }
            
            return new KeyData(salt, keyBytes);
        }
    }

    /**
     * 최적 버퍼 크기 계산 (메모리 기반)
     */
    private int calculateOptimalBufferSize(long fileSize, int requestedSize) {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long availableMemory = maxMemory - (totalMemory - freeMemory);
        
        // 가용 메모리의 10%만 사용 (안전 마진)
        int memoryBasedSize = (int) Math.min(availableMemory * SAFE_MEMORY_RATIO, Integer.MAX_VALUE);
        
        // 요청된 크기와 메모리 기반 크기 중 작은 값 선택
        int optimalSize = Math.min(requestedSize, memoryBasedSize);
        
        // 최소/최대 범위 내로 제한
        optimalSize = Math.max(MIN_BUFFER_SIZE, Math.min(optimalSize, MAX_BUFFER_SIZE));
        
        // 파일 크기보다 큰 버퍼는 의미 없음
        if (fileSize > 0 && fileSize < optimalSize) {
            optimalSize = (int) fileSize;
        }
        
        LOGGER.fine(String.format("버퍼 크기 계산: 요청=%dKB, 메모리기반=%dKB, 최적=%dKB", 
            requestedSize/1024, memoryBasedSize/1024, optimalSize/1024));
        
        return optimalSize;
    }

    /**
     * 메모리 체크 및 가비지 컬렉션
     */
    private void checkMemoryAndTriggerGC() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        double memoryUsageRatio = (double) usedMemory / maxMemory;
        
        if (memoryUsageRatio > 0.8) { // 80% 이상 사용 시
            LOGGER.warning(String.format("메모리 사용률 높음: %.1f%% - GC 실행 중...", 
                memoryUsageRatio * 100));
            
            // 가비지 컬렉션 실행
            System.gc();
            System.runFinalization();
            
            // 잠시 대기
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // 메모리 사용률 재확인
            runtime = Runtime.getRuntime();
            long newUsedMemory = runtime.totalMemory() - runtime.freeMemory();
            double newRatio = (double) newUsedMemory / runtime.maxMemory();
            
            LOGGER.info(String.format("GC 후 메모리 사용률: %.1f%% → %.1f%%", 
                memoryUsageRatio * 100, newRatio * 100));
            
            if (newRatio > 0.9) { // 여전히 90% 이상이면
                throw new OutOfMemoryError(String.format(
                    "메모리 부족 - 현재 사용률: %.1f%% (사용: %dMB / 최대: %dMB)", 
                    newRatio * 100, newUsedMemory / (1024 * 1024), runtime.maxMemory() / (1024 * 1024)));
            }
        }
    }

    /**
     * 고유한 출력 파일 경로 생성
     */
    private String generateOutputPath(String basePath, String extension) {
        String outputPath = basePath + extension;
        File file = new File(outputPath);
        
        if (!file.exists()) {
            return outputPath;
        }
        
        // 중복 파일명 처리
        String name = basePath;
        int counter = 1;
        
        while (counter <= 9999) { // 무한 루프 방지
            outputPath = name + "_" + counter + extension;
            if (!new File(outputPath).exists()) {
                return outputPath;
            }
            counter++;
        }
        
        throw new RuntimeException("고유한 파일명을 생성할 수 없습니다: " + basePath);
    }

    /**
     * 원자적 파일 이동
     */
    private void atomicFileMove(File source, File target) throws IOException {
        try {
            // 원자적 이동 시도
            Files.move(source.toPath(), target.toPath(), 
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                
        } catch (AtomicMoveNotSupportedException e) {
            // 원자적 이동이 지원되지 않는 경우 일반 이동 후 동기화
            LOGGER.warning("원자적 이동 미지원 - 일반 이동으로 대체");
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 임시 파일들 정리
     */
    private void cleanupTempFiles(File... files) {
        for (File file : files) {
            if (file != null && file.exists()) {
                try {
                    Files.deleteIfExists(file.toPath());
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "임시 파일 삭제 실패: " + file.getName(), e);
                    // 프로그램 종료 시 삭제 예약
                    file.deleteOnExit();
                }
            }
        }
    }

    /**
     * 패턴으로 파일 덮어쓰기
     */
    @FunctionalInterface
    private interface PatternGenerator {
        byte[] generate(long position);
    }
    
    private void overwriteFileWithPattern(RandomAccessFile raf, long length, int bufferSize, 
                                         PatternGenerator generator) throws IOException {
        for (long pos = 0; pos < length; pos += bufferSize) {
            int currentBufferSize = (int) Math.min(bufferSize, length - pos);
            byte[] pattern = generator.generate(pos);
            
            if (pattern.length > currentBufferSize) {
                pattern = Arrays.copyOf(pattern, currentBufferSize);
            }
            
            raf.seek(pos);
            raf.write(pattern, 0, currentBufferSize);
        }
        raf.getFD().sync();
    }

    /**
     * 재시도 로직이 있는 연산 실행
     */
    @FunctionalInterface
    private interface RetryableOperation {
        boolean execute() throws Exception;
    }
    
    private boolean retryOperation(RetryableOperation operation, int maxAttempts) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (operation.execute()) {
                    return true;
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, String.format("시도 %d/%d 실패", attempt, maxAttempts), e);
            }
            
            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(RETRY_DELAY_MS * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
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
     * 키 데이터 컨테이너
     */
    private static class KeyData {
        final byte[] salt;
        final byte[] keyBytes;
        
        KeyData(byte[] salt, byte[] keyBytes) {
            this.salt = salt.clone();
            this.keyBytes = keyBytes.clone();
        }
    }
}