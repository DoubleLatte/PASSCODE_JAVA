package com.ddlatte.encryption;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.logging.Logger;

/**
 * Handles encryption and decryption operations with enhanced KeyStore file handling and error reporting.
 */
public class EncryptedFileSystem {
    private static final Logger LOGGER = Logger.getLogger(EncryptedFileSystem.class.getName());
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/CTR/NoPadding";
    private static final int KEY_LENGTH = 256;
    private static final int IV_LENGTH = 16;
    private static final int PBKDF2_ITERATIONS = 65536;
    private static final int MAX_BUFFER_SIZE = 1024 * 1024 * 1024; // 1GB
    private static final String KEYSTORE_TYPE = "JCEKS";

    private SecretKey secretKey;

    /**
     * Generates and stores a key in a KeyStore with validated file path and password.
     */
    public void generateKey(String keyFilePath, String password) throws Exception {
        File keyFile = new File(keyFilePath);
        File parentDir = keyFile.getParentFile();
        if (parentDir == null || !parentDir.exists() || !parentDir.canWrite()) {
            throw new IOException("키 파일 저장 경로에 쓰기 권한이 없거나 유효하지 않습니다: " + keyFilePath);
        }

        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes(salt);

        SecretKey key = deriveKey(password, salt);
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
        keyStore.load(null, password.toCharArray());

        KeyStore.SecretKeyEntry keyEntry = new KeyStore.SecretKeyEntry(key);
        KeyStore.ProtectionParameter protParam = new KeyStore.PasswordProtection(password.toCharArray());
        keyStore.setEntry("encryptionKey", keyEntry, protParam);

        try (FileOutputStream fos = new FileOutputStream(keyFile)) {
            keyStore.store(fos, password.toCharArray());
            LOGGER.info("Key generated and stored: " + keyFilePath);
        }
    }

    /**
     * Loads a key from a KeyStore with detailed error handling.
     */
    public void loadKey(String keyFilePath, String password) throws Exception {
        try (FileInputStream fis = new FileInputStream(keyFilePath)) {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
            try {
                keyStore.load(fis, password.toCharArray());
            } catch (IOException e) {
                throw new IllegalArgumentException("키 파일이 손상되었거나 잘못된 형식입니다: " + keyFilePath, e);
            } catch (Exception e) {
                throw new IllegalArgumentException("비밀번호가 잘못되었습니다.", e);
            }

            SecretKey key = (SecretKey) keyStore.getKey("encryptionKey", password.toCharArray());
            if (key == null) {
                throw new IllegalArgumentException("키 파일에 유효한 키가 없습니다: " + keyFilePath);
            }
            secretKey = key;
            LOGGER.info("Key loaded successfully from: " + keyFilePath);
        } catch (FileNotFoundException e) {
            throw new IllegalArgumentException("키 파일을 찾을 수 없습니다: " + keyFilePath, e);
        }
    }

    /**
     * Encrypts a file with dynamic buffer sizing up to 1GB.
     */
    public String encryptFile(String inputPath, int chunkSize) throws Exception {
        if (secretKey == null) throw new IllegalStateException("키가 로드되지 않았습니다.");
        File inputFile = new File(inputPath);
        String outputPath = inputPath + ".lock";
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);

        int bufferSize = Math.min(chunkSize, MAX_BUFFER_SIZE);
        long startTime = System.nanoTime();
        long usedMemoryBefore = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);

        try (FileChannel inChannel = new FileInputStream(inputFile).getChannel();
             FileChannel outChannel = new FileOutputStream(outputPath).getChannel()) {
            byte[] iv = cipher.getIV();
            outChannel.write(ByteBuffer.wrap(iv));

            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            while (inChannel.read(buffer) > 0) {
                buffer.flip();
                byte[] input = new byte[buffer.remaining()];
                buffer.get(input);
                byte[] output = cipher.update(input);
                outChannel.write(ByteBuffer.wrap(output));
                buffer.clear();
            }
            byte[] finalOutput = cipher.doFinal();
            outChannel.write(ByteBuffer.wrap(finalOutput));
        }

        long usedMemoryAfter = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        LOGGER.info(String.format("Encrypted %s in %d ms, memory used: %d MB -> %d MB",
                inputPath, (System.nanoTime() - startTime) / 1_000_000, usedMemoryBefore, usedMemoryAfter));
        return outputPath;
    }

    /**
     * Decrypts a file with dynamic buffer sizing up to 1GB.
     */
    public String decryptFile(String inputPath, String outputPath) throws Exception {
        if (secretKey == null) throw new IllegalStateException("키가 로드되지 않았습니다.");
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);

        long startTime = System.nanoTime();
        long usedMemoryBefore = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);

        try (FileChannel inChannel = new FileInputStream(inputPath).getChannel();
             FileChannel outChannel = new FileOutputStream(outputPath).getChannel()) {
            ByteBuffer ivBuffer = ByteBuffer.allocate(IV_LENGTH);
            inChannel.read(ivBuffer);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(ivBuffer.array()));

            ByteBuffer buffer = ByteBuffer.allocate(MAX_BUFFER_SIZE);
            while (inChannel.read(buffer) > 0) {
                buffer.flip();
                byte[] input = new byte[buffer.remaining()];
                buffer.get(input);
                byte[] output = cipher.update(input);
                outChannel.write(ByteBuffer.wrap(output));
                buffer.clear();
            }
            byte[] finalOutput = cipher.doFinal();
            outChannel.write(ByteBuffer.wrap(finalOutput));
        }

        long usedMemoryAfter = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        LOGGER.info(String.format("Decrypted %s in %d ms, memory used: %d MB -> %d MB",
                inputPath, (System.nanoTime() - startTime) / 1_000_000, usedMemoryBefore, usedMemoryAfter));
        return outputPath;
    }

    /**
     * Securely deletes a file with configurable overwrite passes.
     */
    public void secureDelete(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) return;

        try (RandomAccessFile raf = new RandomAccessFile(file, "rws")) {
            byte[] overwrite = new byte[1024];
            new SecureRandom().nextBytes(overwrite);
            long length = file.length();
            for (long i = 0; i < length; i += overwrite.length) {
                raf.write(overwrite, 0, (int) Math.min(overwrite.length, length - i));
            }
        }

        if (!file.delete()) {
            throw new IOException("파일 삭제 실패: " + filePath);
        }
        LOGGER.info("Securely deleted: " + filePath);
    }

    /**
     * Deletes an encrypted file securely.
     */
    public void deleteEncryptedFile(String filePath) throws IOException {
        secureDelete(filePath);
    }

    private SecretKey deriveKey(String password, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, ALGORITHM);
    }
}