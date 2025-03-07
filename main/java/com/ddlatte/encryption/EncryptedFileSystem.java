package com.ddlatte.encryption;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.*;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

public class EncryptedFileSystem {
    private SecretKeySpec key;
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 16;
    private static final int KEY_LENGTH = 256;
    private static final int ITERATION_COUNT = 100000;
    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final int MAX_PASSWORD_LENGTH = 128;

    public EncryptedFileSystem() {
        this.key = null;
    }

    public void generateKey(String keyPath, String password) throws Exception {
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("비밀번호는 " + MAX_PASSWORD_LENGTH + "자를 넘을 수 없습니다");
        }
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);

        SecretKeyFactory factory;
        try {
            factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        } catch (NoSuchAlgorithmException e) {
            throw new Exception("키 생성 알고리즘이 지원되지 않음");
        }
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH);
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        this.key = new SecretKeySpec(keyBytes, "AES");

        try (FileOutputStream fos = new FileOutputStream(keyPath);
             DataOutputStream dos = new DataOutputStream(fos)) {
            dos.writeInt(SALT_LENGTH);
            dos.write(salt);
            dos.writeInt(keyBytes.length);
            dos.write(keyBytes);
        } catch (IOException e) {
            throw new Exception("디스크 쓰기 권한 부족: " + e.getMessage());
        }
    }

    public void loadKey(String keyPath, String password) throws Exception {
        try (FileInputStream fis = new FileInputStream(keyPath);
             DataInputStream dis = new DataInputStream(fis)) {
            int saltLength = dis.readInt();
            if (saltLength != SALT_LENGTH) {
                throw new Exception("키 파일이 손상됨: 잘못된 소금 길이");
            }
            byte[] salt = new byte[saltLength];
            dis.readFully(salt);

            int keyLength = dis.readInt();
            byte[] storedKey = new byte[keyLength];
            dis.readFully(storedKey);

            SecretKeyFactory factory;
            try {
                factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            } catch (NoSuchAlgorithmException e) {
                throw new Exception("키 로드 알고리즘이 지원되지 않음");
            }
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH);
            byte[] generatedKey = factory.generateSecret(spec).getEncoded();

            if (!java.util.Arrays.equals(generatedKey, storedKey)) {
                throw new Exception("잘못된 비밀번호");
            }
            this.key = new SecretKeySpec(generatedKey, "AES");
        } catch (EOFException e) {
            throw new Exception("키 파일 손상: 데이터 부족");
        } catch (IOException e) {
            throw new Exception("키 파일 읽기 실패: " + e.getMessage());
        }
    }

    public String encryptFile(String filePath, int chunkSize) throws Exception {
        if (this.key == null) {
            throw new Exception("키가 로드되지 않음");
        }

        String encryptedFilePath = filePath + ".lock";
        SecureRandom random = new SecureRandom();
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);

        Cipher cipher;
        try {
            cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
        } catch (Exception e) {
            throw new Exception("암호화 초기화 실패: " + e.getMessage());
        }

        try (FileInputStream fis = new FileInputStream(filePath);
             FileOutputStream fos = new FileOutputStream(encryptedFilePath);
             FileChannel channel = fos.getChannel();
             FileLock lock = channel.tryLock()) {
            if (lock == null) {
                throw new Exception("파일이 다른 프로세스에서 잠겨 있습니다");
            }

            DataOutputStream dos = new DataOutputStream(fos);
            dos.writeInt(chunkSize);
            dos.write(iv);

            byte[] buffer = new byte[chunkSize];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                byte[] encryptedChunk = cipher.update(buffer, 0, bytesRead);
                if (encryptedChunk != null) {
                    dos.writeInt(encryptedChunk.length);
                    dos.write(encryptedChunk);
                }
            }
            byte[] finalChunk = cipher.doFinal();
            if (finalChunk != null) {
                dos.writeInt(finalChunk.length);
                dos.write(finalChunk);
            }
        } catch (FileNotFoundException e) {
            throw new Exception("파일을 읽을 수 없음: " + e.getMessage());
        } catch (IOException e) {
            throw new Exception("디스크 공간 부족 또는 쓰기 오류: " + e.getMessage());
        }

        return encryptedFilePath;
    }

    public String decryptFile(String encryptedFilePath, String outputPath) throws Exception {
        if (this.key == null) {
            throw new Exception("키가 로드되지 않음");
        }

        try (FileInputStream fis = new FileInputStream(encryptedFilePath);
             DataInputStream dis = new DataInputStream(fis);
             FileOutputStream fos = new FileOutputStream(outputPath)) {

            int chunkSize = dis.readInt();
            if (chunkSize <= 0) {
                throw new Exception("암호화 데이터 손상: 잘못된 청크 크기");
            }
            byte[] iv = new byte[IV_LENGTH];
            dis.readFully(iv);

            Cipher cipher;
            try {
                cipher = Cipher.getInstance(ALGORITHM);
                cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
            } catch (Exception e) {
                throw new Exception("복호화 초기화 실패: " + e.getMessage());
            }

            while (dis.available() > 0) {
                int encryptedChunkLength = dis.readInt();
                if (encryptedChunkLength < 0) {
                    throw new Exception("암호화 데이터 손상: 음수 청크 길이");
                }
                byte[] encryptedChunk = new byte[encryptedChunkLength];
                dis.readFully(encryptedChunk);

                byte[] decryptedChunk;
                try {
                    decryptedChunk = cipher.update(encryptedChunk);
                } catch (Exception e) {
                    throw new Exception("암호화 데이터 손상: " + e.getMessage());
                }
                if (decryptedChunk != null) {
                    fos.write(decryptedChunk);
                }
            }
            byte[] finalChunk;
            try {
                finalChunk = cipher.doFinal();
            } catch (Exception e) {
                throw new Exception("암호화 데이터 손상: " + e.getMessage());
            }
            if (finalChunk != null) {
                fos.write(finalChunk);
            }
        } catch (IOException e) {
            throw new Exception("파일 처리 오류: " + e.getMessage());
        }

        return outputPath;
    }

    public boolean deleteEncryptedFile(String encryptedFilePath) throws Exception {
        secureDelete(encryptedFilePath);
        return !new File(encryptedFilePath).exists();
    }

    public void secureDelete(String filePath) throws Exception {
        File file = new File(filePath);
        if (!file.exists()) {
            return;
        }

        long length = file.length();
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            byte[] randomData = new byte[1024];
            SecureRandom random = new SecureRandom();
            long written = 0;
            while (written < length) {
                random.nextBytes(randomData);
                int toWrite = (int) Math.min(1024, length - written);
                raf.write(randomData, 0, toWrite);
                written += toWrite;
            }
        } catch (IOException e) {
            throw new Exception("파일 쓰기 권한 부족: " + e.getMessage());
        }
        file.delete();
    }
}