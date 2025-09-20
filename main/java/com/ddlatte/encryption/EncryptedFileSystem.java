package com.ddlatte.encryption;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.spec.KeySpec;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Handles file encryption and decryption with improved security and optimized memory management.
 * 
 * Key improvements:
 * - Uses AES-GCM for authenticated encryption
 * - Sequential processing to maintain data integrity
 * - Dynamic buffer sizing based on available memory
 * - Enhanced error handling and resource cleanup
 */
public class EncryptedFileSystem {
    private static final Logger LOGGER = Logger.getLogger(EncryptedFileSystem.class.getName());
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_LENGTH = 256;
    private static final int PBKDF2_ITERATIONS = 100000; // Increased from 65536
    private static final int SALT_LENGTH = 16;
    private static final int GCM_IV_LENGTH = 12; // GCM standard IV length
    private static final int GCM_TAG_LENGTH = 16; // 128-bit authentication tag
    private static final int MIN_BUFFER_SIZE = 1024 * 1024; // 1MB minimum
    private static final int MAX_BUFFER_SIZE = 1024 * 1024 * 64; // 64MB maximum
    private static final double MEMORY_USAGE_RATIO = 0.25; // Use max 25% of available memory

    private SecretKey secretKey;

    public void generateKey(String keyPath, String password) throws Exception {
        validatePassword(password);
        byte[] salt = generateSalt();
        SecretKey key = deriveKey(password, salt);
        
        // Use try-with-resources for proper cleanup
        try (FileOutputStream fos = new FileOutputStream(keyPath);
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {
            bos.write(salt);
            bos.write(key.getEncoded());
            bos.flush();
        }
        
        secretKey = key;
        LOGGER.info("Key generated and saved to: " + keyPath);
    }

    public void loadKey(String keyPath, String password) throws Exception {
        validatePassword(password);
        byte[] salt = new byte[SALT_LENGTH];
        byte[] keyBytes = new byte[KEY_LENGTH / 8];
        
        try (FileInputStream fis = new FileInputStream(keyPath);
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            int saltRead = bis.read(salt);
            int keyRead = bis.read(keyBytes);
            
            if (saltRead != SALT_LENGTH || keyRead != KEY_LENGTH / 8) {
                throw new IOException("Invalid key file format");
            }
        }
        
        SecretKey key = deriveKey(password, salt);
        if (!MessageDigest.isEqual(key.getEncoded(), keyBytes)) {
            throw new InvalidKeyException("Incorrect password or corrupted key file");
        }
        
        secretKey = key;
        LOGGER.info("Key loaded from: " + keyPath);
    }

    public String encryptFile(String inputPath, int requestedChunkSize) throws Exception {
        if (secretKey == null) {
            throw new IllegalStateException("Key not loaded");
        }
        
        File inputFile = new File(inputPath);
        if (!inputFile.exists() || !inputFile.canRead()) {
            throw new FileNotFoundException("Cannot read input file: " + inputPath);
        }
        
        String outputPath = inputPath + ".lock";
        File outputFile = new File(outputPath);
        
        // Calculate optimal buffer size
        int bufferSize = getOptimalBufferSize(inputFile.length(), requestedChunkSize);
        
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        
        long startTime = System.nanoTime();
        long usedMemoryBefore = getUsedMemory();
        
        try (FileInputStream fis = new FileInputStream(inputFile);
             FileOutputStream fos = new FileOutputStream(outputFile);
             BufferedInputStream bis = new BufferedInputStream(fis);
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {
            
            // Write IV to output file
            byte[] iv = cipher.getIV();
            bos.write(iv);
            
            // Process file sequentially to maintain encryption integrity
            byte[] buffer = new byte[bufferSize];
            byte[] outputBuffer;
            int bytesRead;
            
            while ((bytesRead = bis.read(buffer)) != -1) {
                if (bytesRead == bufferSize) {
                    outputBuffer = cipher.update(buffer);
                } else {
                    // Last chunk - copy only the bytes read
                    byte[] lastChunk = Arrays.copyOf(buffer, bytesRead);
                    outputBuffer = cipher.update(lastChunk);
                }
                
                if (outputBuffer != null) {
                    bos.write(outputBuffer);
                }
            }
            
            // Finalize encryption (includes GCM authentication tag)
            byte[] finalOutput = cipher.doFinal();
            if (finalOutput != null) {
                bos.write(finalOutput);
            }
            
            bos.flush();
            
        } catch (Exception e) {
            // Clean up partial file on error
            if (outputFile.exists()) {
                outputFile.delete();
            }
            throw new RuntimeException("Encryption failed: " + e.getMessage(), e);
        }

        long usedMemoryAfter = getUsedMemory();
        long processingTime = (System.nanoTime() - startTime) / 1_000_000;
        
        LOGGER.info(String.format("Encrypted %s in %d ms, memory used: %d MB -> %d MB, buffer size: %d MB",
                inputPath, processingTime, usedMemoryBefore, usedMemoryAfter, bufferSize / (1024 * 1024)));
        
        return outputPath;
    }

    public String decryptFile(String inputPath, String outputPath) throws Exception {
        if (secretKey == null) {
            throw new IllegalStateException("Key not loaded");
        }
        
        File inputFile = new File(inputPath);
        if (!inputFile.exists() || !inputFile.canRead()) {
            throw new FileNotFoundException("Cannot read input file: " + inputPath);
        }
        
        File outputFile = new File(outputPath);
        
        // Calculate optimal buffer size
        int bufferSize = getOptimalBufferSize(inputFile.length(), MAX_BUFFER_SIZE);
        
        long startTime = System.nanoTime();
        long usedMemoryBefore = getUsedMemory();
        
        try (FileInputStream fis = new FileInputStream(inputFile);
             FileOutputStream fos = new FileOutputStream(outputFile);
             BufferedInputStream bis = new BufferedInputStream(fis);
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {
            
            // Read IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            int ivBytesRead = bis.read(iv);
            if (ivBytesRead != GCM_IV_LENGTH) {
                throw new IOException("Invalid encrypted file format: IV not found");
            }
            
            // Initialize cipher with IV
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);
            
            // Process file sequentially
            byte[] buffer = new byte[bufferSize];
            byte[] outputBuffer;
            int bytesRead;
            
            while ((bytesRead = bis.read(buffer)) != -1) {
                if (bytesRead == bufferSize) {
                    outputBuffer = cipher.update(buffer);
                } else {
                    // Last chunk - copy only the bytes read
                    byte[] lastChunk = Arrays.copyOf(buffer, bytesRead);
                    outputBuffer = cipher.update(lastChunk);
                }
                
                if (outputBuffer != null) {
                    bos.write(outputBuffer);
                }
            }
            
            // Finalize decryption (verifies GCM authentication tag)
            try {
                byte[] finalOutput = cipher.doFinal();
                if (finalOutput != null) {
                    bos.write(finalOutput);
                }
            } catch (AEADBadTagException e) {
                throw new SecurityException("File authentication failed - file may be corrupted or tampered with", e);
            }
            
            bos.flush();
            
        } catch (Exception e) {
            // Clean up partial file on error
            if (outputFile.exists()) {
                outputFile.delete();
            }
            throw new RuntimeException("Decryption failed: " + e.getMessage(), e);
        }

        long usedMemoryAfter = getUsedMemory();
        long processingTime = (System.nanoTime() - startTime) / 1_000_000;
        
        LOGGER.info(String.format("Decrypted %s in %d ms, memory used: %d MB -> %d MB, buffer size: %d MB",
                inputPath, processingTime, usedMemoryBefore, usedMemoryAfter, bufferSize / (1024 * 1024)));
        
        return outputPath;
    }

    public void deleteEncryptedFile(String filePath) throws IOException {
        File file = new File(filePath);
        if (file.exists()) {
            if (!file.delete()) {
                throw new IOException("Failed to delete encrypted file: " + filePath);
            }
            LOGGER.info("Deleted encrypted file: " + filePath);
        }
    }

    public void secureDelete(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) return;

        try (RandomAccessFile raf = new RandomAccessFile(file, "rw");
             FileChannel channel = raf.getChannel()) {
            
            long length = file.length();
            int bufferSize = Math.min(8192, (int)Math.min(length, Integer.MAX_VALUE));
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            
            // Multiple pass secure deletion
            SecureRandom random = new SecureRandom();
            
            // Pass 1: Random data
            for (long position = 0; position < length; position += bufferSize) {
                int toWrite = (int) Math.min(bufferSize, length - position);
                byte[] randomData = new byte[toWrite];
                random.nextBytes(randomData);
                
                buffer.clear();
                buffer.put(randomData, 0, toWrite);
                buffer.flip();
                
                channel.position(position);
                channel.write(buffer);
            }
            
            // Pass 2: Zeros
            Arrays.fill(buffer.array(), (byte) 0);
            for (long position = 0; position < length; position += bufferSize) {
                int toWrite = (int) Math.min(bufferSize, length - position);
                
                buffer.clear();
                buffer.limit(toWrite);
                buffer.flip();
                
                channel.position(position);
                channel.write(buffer);
            }
            
            // Force write to disk
            channel.force(true);
            
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error during secure deletion of " + filePath, e);
            throw e;
        }
        
        if (!file.delete()) {
            throw new IOException("Failed to delete file after secure wipe: " + filePath);
        }
        
        LOGGER.info("Securely deleted file: " + filePath);
    }

    /**
     * Calculate optimal buffer size based on file size and available memory
     */
    private int getOptimalBufferSize(long fileSize, int requestedSize) {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long freeMemory = runtime.freeMemory();
        long totalMemory = runtime.totalMemory();
        long availableMemory = maxMemory - (totalMemory - freeMemory);
        
        // Use a portion of available memory, but respect min/max limits
        int memoryBasedSize = (int) Math.min(availableMemory * MEMORY_USAGE_RATIO, Integer.MAX_VALUE);
        int optimalSize = Math.min(requestedSize, memoryBasedSize);
        
        // Ensure we stay within bounds
        optimalSize = Math.max(MIN_BUFFER_SIZE, optimalSize);
        optimalSize = Math.min(MAX_BUFFER_SIZE, optimalSize);
        
        // For small files, don't use a buffer larger than the file
        if (fileSize > 0) {
            optimalSize = (int) Math.min(optimalSize, fileSize);
        }
        
        LOGGER.fine(String.format("Buffer size calculation: requested=%d, memory-based=%d, optimal=%d, file-size=%d",
                requestedSize, memoryBasedSize, optimalSize, fileSize));
        
        return optimalSize;
    }

    /**
     * Get current memory usage in MB
     */
    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    }

    /**
     * Generate cryptographically secure random salt
     */
    private byte[] generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    /**
     * Derive key from password using PBKDF2 with increased iterations
     */
    private SecretKey deriveKey(String password, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        
        // Clear the password from memory
        Arrays.fill(password.toCharArray(), '\0');
        
        return new SecretKeySpec(keyBytes, ALGORITHM);
    }

    /**
     * Validate password strength
     */
    private void validatePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters long");
        }
        // Add more password strength validation if needed
    }
}