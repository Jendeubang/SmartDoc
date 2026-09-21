package com.javaee.fileservice.security;

import com.javaee.fileservice.config.FileStorageConfig;
import com.javaee.fileservice.util.FileUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/** Validates upload size, filename, extension and common file signatures before persistence. */
@Component
public class FileUploadValidator {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "txt", "md", "csv", "xls", "xlsx", "ppt", "pptx",
            "jpg", "jpeg", "png", "gif", "bmp", "webp",
            "mp3", "wav", "ogg", "flac", "aac", "m4a", "webm");
    private static final Set<String> TEXT_TYPES = Set.of("txt", "md", "csv");
    private static final Set<String> EXECUTABLE_MARKERS = Set.of("exe", "dll", "bat", "cmd", "com", "msi", "ps1", "sh", "js", "jar", "class", "php");

    private final FileStorageConfig storageConfig;

    public FileUploadValidator(FileStorageConfig storageConfig) {
        this.storageConfig = storageConfig;
    }

    public void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String fileName = validateFileName(file.getOriginalFilename());
        if (!com.javaee.fileservice.util.FileUtils.checkFileSize(file, storageConfig.getMaxSize())) {
            throw new IllegalArgumentException("文件超过 " + storageConfig.getMaxSize() + "MB 上传限制");
        }
        String extension = requiredAllowedExtension(fileName);
        try (InputStream input = file.getInputStream()) {
            byte[] header = input.readNBytes(4096);
            validateSignature(extension, header);
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取上传文件", e);
        }
    }

    public void validateBytes(String originalFileName, byte[] bytes) {
        String fileName = validateFileName(originalFileName);
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        long maxBytes = storageConfig.getMaxSize() * 1024L * 1024L;
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("文件超过 " + storageConfig.getMaxSize() + "MB 上传限制");
        }
        validateSignature(requiredAllowedExtension(fileName),
                Arrays.copyOf(bytes, Math.min(bytes.length, 4096)));
    }

    public String validateFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String fileName = originalFileName.trim();
        if (fileName.length() > 255 || fileName.contains("/") || fileName.contains("\\") || fileName.contains("\0")
                || fileName.chars().anyMatch(ch -> ch < 32)) {
            throw new IllegalArgumentException("文件名不合法");
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String marker : EXECUTABLE_MARKERS) {
            if (lower.matches(".*\\." + marker + "(\\.|$).*")) {
                throw new IllegalArgumentException("不允许上传可执行脚本或程序文件");
            }
        }
        requiredAllowedExtension(fileName);
        return fileName;
    }

    private String requiredAllowedExtension(String fileName) {
        String extension = FileUtils.getFileExtension(fileName);
        if (extension == null || !ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("不支持该文件类型");
        }
        return extension;
    }

    private void validateSignature(String extension, byte[] header) {
        boolean valid = switch (extension) {
            case "pdf" -> startsWith(header, "%PDF".getBytes(StandardCharsets.US_ASCII));
            case "jpg", "jpeg" -> startsWith(header, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            case "png" -> startsWith(header, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
            case "gif" -> startsWith(header, "GIF8".getBytes(StandardCharsets.US_ASCII));
            case "bmp" -> startsWith(header, "BM".getBytes(StandardCharsets.US_ASCII));
            case "webp" -> header.length >= 12 && asciiAt(header, 0, "RIFF") && asciiAt(header, 8, "WEBP");
            case "docx", "xlsx", "pptx" -> startsWith(header, new byte[]{0x50, 0x4B, 0x03, 0x04});
            case "doc", "xls", "ppt" -> startsWith(header, new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0});
            case "wav" -> header.length >= 12 && asciiAt(header, 0, "RIFF") && asciiAt(header, 8, "WAVE");
            case "ogg" -> asciiAt(header, 0, "OggS");
            case "flac" -> asciiAt(header, 0, "fLaC");
            case "mp3" -> asciiAt(header, 0, "ID3") || (header.length >= 2 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xE0) == 0xE0);
            case "m4a" -> asciiAt(header, 4, "ftyp");
            case "aac" -> header.length >= 2 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xF0) == 0xF0;
            case "webm" -> startsWith(header, new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3});
            default -> TEXT_TYPES.contains(extension) && looksLikeText(header);
        };
        if (!valid) {
            throw new IllegalArgumentException("文件内容与扩展名不匹配，已拒绝上传");
        }
    }

    private boolean looksLikeText(byte[] bytes) {
        if (bytes.length == 0) return false;
        long controls = 0;
        for (byte value : bytes) {
            int b = value & 0xFF;
            if (b == 0) return false;
            if (b < 9 || (b > 13 && b < 32)) controls++;
        }
        return controls * 20 < bytes.length;
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        return value.length >= prefix.length && Arrays.equals(Arrays.copyOf(value, prefix.length), prefix);
    }

    private boolean asciiAt(byte[] value, int offset, String marker) {
        byte[] bytes = marker.getBytes(StandardCharsets.US_ASCII);
        if (value.length < offset + bytes.length) return false;
        for (int i = 0; i < bytes.length; i++) if (value[offset + i] != bytes[i]) return false;
        return true;
    }
}
