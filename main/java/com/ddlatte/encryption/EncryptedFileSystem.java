```java
package com.ddlatte.encryption;

import javax.crypto.*;
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
 * Handles file encryption and decryption with parallel processing.
 */
public class EncryptedFileSystem {
    private static final Logger LOGGER = Logger.getLogger(EncryptedFileSystem.class.getName());
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final int KEY_LENGTH = 256;
    private static final int PBKDF2_ITERATIONS = 65536;
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 16;
    private static final int MAX_BUFFER_SIZE = 1024 * 1024 * 128; // 128MB

    private SecretKey secretKey;

    public void generateKey(String keyPath, String password) throws Exception {
        byte[] salt = generateSalt();
        SecretKey key = deriveKey(password, salt);
        try (FileOutputStream fos = new FileOutputStream(keyPath)) {
            fos.write(salt);
            fos.write(key.getEncoded());
        }
        secretKey = key;
        LOGGER.info("Key generated and saved to: " + keyPath);
    }

    public void loadKey(String keyPath, String password) throws Exception {
        byte[] salt = new byte[SALT_LENGTH];
        byte[] keyBytes = new byte[KEY_LENGTH / 8];
        try (FileInputStream fis = new FileInputStream(keyPath)) {
            int saltRead = fis.read(salt);
            int keyRead = fis.read(keyBytes);
            if (saltRead != SALT_LENGTH || keyRead != KEY_LENGTH / 8) {
                throw new IOException("Invalid key file format");
            }
        }
        SecretKey key = deriveKey(password, salt);
        if (!Arrays.equals(key.getEncoded(), keyBytes)) {
            throw new InvalidKeyException("Incorrect password or corrupted key file");
        }
        secretKey = key;
        LOGGER.info("Key loaded from: " + keyPath);
    }

    public String encryptFile(String inputPath, int chunkSize) throws Exception {
        if (secretKey == null) throw new IllegalStateException("Key not loaded");
        File inputFile = new File(inputPath);
        String outputPath = inputPath + ".lock";
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);

        int bufferSize = Math.min(chunkSize, MAX_BUFFER_SIZE);
        long startTime = System.nanoTime();
        long usedMemoryBefore = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);

        ExecutorService executor = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        List<Future<byte[]>> futures = new ArrayList<>();

        try (FileChannel inChannel = new FileInputStream(inputFile).getChannel();
             FileChannel outChannel = new FileOutputStream(outputPath).getChannel()) {
            byte[] iv = cipher.getIV();
            outChannel.write(ByteBuffer.wrap(iv));

            long fileSize = inputFile.length();
            long offset = 0;
            while (offset < fileSize) {
                long currentOffset = offset;
                int currentBufferSize = (int) Math.min(bufferSize, fileSize - offset);
                Future<byte[]> future = executor.submit(() -> {
                    ByteBuffer buffer = ByteBuffer.allocate(currentBufferSize);
                    synchronized (inChannel) {
                        inChannel.position(currentOffset);
                        inChannel.read(buffer);
                    }
                    buffer.flip();
                    byte[] input = new byte[buffer.remaining()];
                    buffer.get(input);
                    return cipher.update(input);
                });
                futures.add(future);
                offset += currentBufferSize;
            }

            for (Future<byte[]> future : futures) {
                outChannel.write(ByteBuffer.wrap(future.get()));
            }

            byte[] finalOutput = cipher.doFinal();
            outChannel.write(ByteBuffer.wrap(finalOutput));
        } catch (Exception e) {
            new File(outputPath).delete();
            throw e;
        } finally {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        long usedMemoryAfter = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        LOGGER.info(String.format("Encrypted %s in %d ms, memory used: %d MB -> %d MB",
                inputPath, (System.nanoTime() - startTime) / 1_000_000, usedMemoryBefore, usedMemoryAfter));
        return outputPath;
    }

    public String decryptFile(String inputPath, String outputPath) throws Exception {
        if (secretKey == null) throw new IllegalStateException("Key not loaded");
        File inputFile = new File(inputPath);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);

        int bufferSize = Math.min(1024 * 1024 * 1024, MAX_BUFFER_SIZE); // 1GB default
        long startTime = System.nanoTime();
        long usedMemoryBefore = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);

        ExecutorService executor = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        List<Future<byte[]>> futures = new ArrayList<>();

        try (FileChannel inChannel = new FileInputStream(inputFile).getChannel();
             FileChannel outChannel = new FileOutputStream(outputPath).getChannel()) {
            ByteBuffer ivBuffer = ByteBuffer.allocate(IV_LENGTH);
            inChannel.read(ivBuffer);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new javax.crypto.spec.IvParameterSpec(ivBuffer.array()));

            long fileSize = inputFile.length() - IV_LENGTH;
            long offset = IV_LENGTH;
            while (offset < fileSize) {
                long currentOffset = offset;
                int currentBufferSize = (int) Math.min(bufferSize, fileSize - offset);
                Future<byte[]> future = executor.submit(() -> {
                    ByteBuffer buffer = ByteBuffer.allocate(currentBufferSize);
                    synchronized (inChannel) {
                        inChannel.position(currentOffset);
                        inChannel.read(buffer);
                    }
                    buffer.flip();
                    byte[] input = new byte[buffer.remaining()];
                    buffer.get(input);
                    return cipher.update(input);
                });
                futures.add(future);
                offset += currentBufferSize;
            }

            for (Future<byte[]> future : futures) {
                outChannel.write(ByteBuffer.wrap(future.get()));
            }

            byte[] finalOutput = cipher.doFinal();
            outChannel.write(ByteBuffer.wrap(finalOutput));
        } catch (Exception e) {
            new File(outputPath).delete();
            throw e;
        } finally {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        long usedMemoryAfter = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        LOGGER.info(String.format("Decrypted %s in %d ms, memory used: %d MB -> %d MB",
                inputPath, (System.nanoTime() - startTime) / 1_000_000, usedMemoryBefore, usedMemoryAfter));
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

        try (FileChannel channel = new FileOutputStream(file, true).getChannel()) {
            long length = file.length();
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            byte[] zeros = new byte[8192];
            long position = 0;
            while (position < length) {
                int toWrite = (int) Math.min(8192, length - position);
                buffer.clear();
                buffer.put(zeros, 0, toWrite);
                buffer.flip();
                channel.write(buffer);
                position += toWrite;
            }
        }
        if (!file.delete()) {
            throw new IOException("Failed to securely delete file: " + filePath);
        }
        LOGGER.info("Securely deleted file: " + filePath);
    }

    private byte[] generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    private SecretKey deriveKey(String password, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, ALGORITHM);
    }
}
```