package com.javaee.documentservice.service;

import com.javaee.common.exception.BusinessException;
import com.javaee.documentservice.dto.ToolboxJobRequest;
import com.javaee.documentservice.util.DocumentParserUtil;
import com.javaee.documentservice.vo.DocumentVO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Service
public class ToolboxJobService {
    private static final String EXCHANGE = "file.exchange";
    private static final String QUEUE = "document.toolbox.queue";
    private static final String KEY = "document.toolbox";
    private static final String REDIS_JOB_PREFIX = "smartdoc:toolbox:job:";
    private static final String REDIS_OUTPUT_PREFIX = "smartdoc:toolbox:output:";
    private static final String REDIS_TOKEN_PREFIX = "smartdoc:toolbox:token:";
    private static final String REDIS_USER_PREFIX = "smartdoc:toolbox:user:";
    private static final long JOB_TTL_DAYS = 7;
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "OCR", "PDF_SPLIT", "PDF_MERGE", "WORD_TABLE_EXCEL", "DOCX_TO_PDF", "PDF_TO_DOCX"
    );

    private final DocumentService documents;
    private final RabbitTemplate rabbit;
    private final RedisTemplate<String, Object> redis;
    private final RestTemplate http = new RestTemplate();
    private final Map<String, Map<String, Object>> jobs = new ConcurrentHashMap<>();
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Map<String, String> jobTokens = new ConcurrentHashMap<>();
    private final ThreadLocal<String> requestToken = new ThreadLocal<>();

    @Value("${file.service.url:http://localhost:8082}")
    private String fileUrl;

    public ToolboxJobService(DocumentService documents, RabbitTemplate rabbit, RedisTemplate<String, Object> redis) {
        this.documents = documents;
        this.rabbit = rabbit;
        this.redis = redis;
    }

    public Map<String, Object> submit(ToolboxJobRequest request, Long userId, String authorization) {
        if (request.getDocumentIds() == null || request.getDocumentIds().isEmpty()) {
            throw new BusinessException("Please select a source document");
        }
        String type = Optional.ofNullable(request.getToolType()).orElse("").toUpperCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(type)) {
            throw new BusinessException("Unsupported toolbox job type");
        }
        if ("PDF_MERGE".equals(type) && request.getDocumentIds().size() < 2) {
            throw new BusinessException("PDF merge requires at least two documents");
        }
        List<DocumentVO> sourceDocuments = request.getDocumentIds().stream()
                .map(id -> documents.getById(id, userId))
                .toList();

        String jobId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> job = new ConcurrentHashMap<>();
        job.put("jobId", jobId);
        job.put("userId", userId);
        job.put("toolType", type);
        job.put("documentIds", request.getDocumentIds());
        job.put("documentNames", sourceDocuments.stream().map(DocumentVO::getTitle).toList());
        job.put("pages", Optional.ofNullable(request.getPages()).orElse(""));
        job.put("status", "PENDING");
        job.put("progress", 5);
        job.put("message", "Task queued");
        job.put("createdAt", Instant.now().toString());
        jobs.put(jobId, job);
        jobTokens.put(jobId, authorization);
        persistJob(job);
        persistToken(jobId, authorization);
        rabbit.convertAndSend(EXCHANGE, KEY, jobId);
        return snapshot(jobId, userId);
    }

    public Map<String, Object> snapshot(String jobId, Long userId) {
        return new LinkedHashMap<>(owned(jobId, userId));
    }

    public List<Map<String, Object>> list(Long userId) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            Set<Object> ids = redis.opsForZSet().reverseRange(REDIS_USER_PREFIX + userId, 0, 99);
            if (ids != null) {
                for (Object id : ids) {
                    try { result.add(snapshot(String.valueOf(id), userId)); } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }
        if (result.isEmpty()) {
            jobs.values().stream()
                    .filter(job -> userId.equals(((Number) job.get("userId")).longValue()))
                    .sorted(Comparator.comparing(job -> String.valueOf(job.get("createdAt")), Comparator.reverseOrder()))
                    .map(LinkedHashMap::new)
                    .forEach(result::add);
        }
        return result;
    }

    public Map<String, Object> retry(String jobId, Long userId, String authorization) {
        Map<String, Object> job = owned(jobId, userId);
        if (!"FAILED".equals(String.valueOf(job.get("status")))) {
            throw new BusinessException("Only failed tasks can be retried");
        }
        files.remove(jobId);
        try { redis.delete(REDIS_OUTPUT_PREFIX + jobId); } catch (Exception ignored) { }
        job.put("status", "PENDING");
        job.put("progress", 5);
        job.put("message", "Retry queued");
        job.put("retryCount", ((Number) job.getOrDefault("retryCount", 0)).intValue() + 1);
        job.put("updatedAt", Instant.now().toString());
        jobTokens.put(jobId, authorization);
        persistToken(jobId, authorization);
        persistJob(job);
        rabbit.convertAndSend(EXCHANGE, KEY, jobId);
        return snapshot(jobId, userId);
    }

    public byte[] output(String jobId, Long userId) {
        owned(jobId, userId);
        byte[] output = files.get(jobId);
        if (output == null) {
            try {
                Object stored = redis.opsForValue().get(REDIS_OUTPUT_PREFIX + jobId);
                if (stored instanceof byte[] bytes) {
                    output = bytes;
                    files.put(jobId, bytes);
                }
            } catch (Exception ignored) { }
        }
        if (output == null) {
            throw new BusinessException("The result file is not ready yet");
        }
        return output;
    }

    @RabbitListener(queues = QUEUE)
    public void process(String jobId) {
        Map<String, Object> job = jobs.get(jobId);
        if (job == null) {
            job = loadJob(jobId);
            if (job != null) jobs.put(jobId, job);
        }
        if (job == null) return;
        String token = jobTokens.get(jobId);
        if (token == null) token = loadToken(jobId);
        requestToken.set(token);
        try {
            update(job, "PROCESSING", 15, "Reading source file");
            @SuppressWarnings("unchecked")
            List<String> ids = (List<String>) job.get("documentIds");
            Long userId = ((Number) job.get("userId")).longValue();
            List<DocumentVO> documentsToProcess = new ArrayList<>();
            for (String documentId : ids) {
                documentsToProcess.add(documents.getById(documentId, userId));
            }
            String type = (String) job.get("toolType");
            byte[] result = switch (type) {
                case "OCR" -> ocr(documentsToProcess.get(0), job);
                case "PDF_SPLIT" -> split(documentsToProcess.get(0), (String) job.get("pages"), job);
                case "PDF_MERGE" -> merge(documentsToProcess, job);
                case "WORD_TABLE_EXCEL" -> excel(documentsToProcess.get(0), job);
                case "DOCX_TO_PDF" -> docxToPdf(documentsToProcess.get(0), job);
                case "PDF_TO_DOCX" -> pdfToDocx(documentsToProcess.get(0), job);
                default -> throw new BusinessException("Unsupported toolbox job type");
            };
            files.put(jobId, result);
            persistOutput(jobId, result);
            job.put("fileName", fileName(type));
            job.put("contentType", contentType(type));
            update(job, "SUCCESS", 100, "Completed. Your result is ready to download.");
        } catch (Exception exception) {
            update(job, "FAILED", 100, "Processing failed: " + safeMessage(exception));
        } finally {
            requestToken.remove();
            jobTokens.remove(jobId);
            try { redis.delete(REDIS_TOKEN_PREFIX + jobId); } catch (Exception ignored) { }
        }
    }

    private byte[] ocr(DocumentVO document, Map<String, Object> job) {
        update(job, "PROCESSING", 50, "Running OCR recognition");
        String text = DocumentParserUtil.parseDocument(download(document), document.getTitle());
        if (text.isBlank()) {
            throw new BusinessException("No text was detected in this file");
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] split(DocumentVO document, String rawPages, Map<String, Object> job) throws IOException {
        update(job, "PROCESSING", 50, "Splitting PDF pages");
        Set<Integer> pages = parsePages(rawPages);
        try (PDDocument input = Loader.loadPDF(download(document));
             PDDocument output = new PDDocument();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            for (int index = 0; index < input.getNumberOfPages(); index++) {
                if (pages.isEmpty() || pages.contains(index + 1)) {
                    output.importPage(input.getPage(index));
                }
            }
            if (output.getNumberOfPages() == 0) {
                throw new BusinessException("The requested PDF pages do not exist");
            }
            output.save(bytes);
            return bytes.toByteArray();
        }
    }

    private byte[] merge(List<DocumentVO> documentsToMerge, Map<String, Object> job) throws IOException {
        try (PDDocument output = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            for (int index = 0; index < documentsToMerge.size(); index++) {
                update(job, "PROCESSING", 25 + index * 55 / documentsToMerge.size(), "Merging PDF " + (index + 1));
                try (PDDocument input = Loader.loadPDF(download(documentsToMerge.get(index)))) {
                    for (int page = 0; page < input.getNumberOfPages(); page++) {
                        output.importPage(input.getPage(page));
                    }
                }
            }
            output.save(bytes);
            return bytes.toByteArray();
        }
    }

    private byte[] excel(DocumentVO document, Map<String, Object> job) throws IOException {
        update(job, "PROCESSING", 50, "Extracting Word tables into Excel");
        try (XWPFDocument word = new XWPFDocument(new ByteArrayInputStream(download(document)));
             XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (word.getTables().isEmpty()) {
                throw new BusinessException("No table was found in this DOCX file");
            }
            int sheetNumber = 1;
            for (var table : word.getTables()) {
                var sheet = workbook.createSheet("Table" + sheetNumber++);
                int rowIndex = 0;
                for (var row : table.getRows()) {
                    var excelRow = sheet.createRow(rowIndex++);
                    for (int cellIndex = 0; cellIndex < row.getTableCells().size(); cellIndex++) {
                        excelRow.createCell(cellIndex).setCellValue(row.getCell(cellIndex).getText());
                    }
                }
            }
            workbook.write(bytes);
            return bytes.toByteArray();
        }
    }

    private byte[] docxToPdf(DocumentVO document, Map<String, Object> job) throws Exception {
        requireExtension(document, ".docx", "DOCX to PDF");
        update(job, "PROCESSING", 45, "Converting DOCX to PDF");
        Path directory = Files.createTempDirectory("smartdoc-docx-pdf-");
        try {
            Path input = directory.resolve("source.docx");
            Path output = directory.resolve("source.pdf");
            Files.write(input, download(document));
            Process process = new ProcessBuilder(
                    "libreoffice", "--headless", "--convert-to", "pdf", "--outdir", directory.toString(), input.toString()
            ).redirectErrorStream(true).start();
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new BusinessException("DOCX to PDF conversion timed out");
            }
            String commandOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0 || !Files.exists(output)) {
                throw new BusinessException("DOCX to PDF conversion failed" + (commandOutput.isBlank() ? "" : ": " + commandOutput.trim()));
            }
            update(job, "PROCESSING", 85, "Generating PDF result");
            return Files.readAllBytes(output);
        } finally {
            deleteDirectory(directory);
        }
    }

    private byte[] pdfToDocx(DocumentVO document, Map<String, Object> job) throws IOException {
        requireExtension(document, ".pdf", "PDF to DOCX");
        update(job, "PROCESSING", 45, "Extracting editable text from PDF");
        try (PDDocument pdf = Loader.loadPDF(download(document));
             XWPFDocument word = new XWPFDocument();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            String extractedText = new PDFTextStripper().getText(pdf).trim();
            if (extractedText.isBlank()) {
                throw new BusinessException("The PDF contains no extractable text. Please use OCR first.");
            }
            for (String paragraph : extractedText.split("\\R{2,}")) {
                String content = paragraph.trim();
                if (!content.isBlank()) {
                    word.createParagraph().createRun().setText(content);
                }
            }
            update(job, "PROCESSING", 85, "Generating editable DOCX");
            word.write(bytes);
            return bytes.toByteArray();
        }
    }

    private byte[] download(DocumentVO document) {
        if (document.getFileId() == null || document.getFileId().isBlank()) {
            throw new BusinessException("This document has no source file");
        }
        HttpHeaders headers = new HttpHeaders();
        String token = requestToken.get();
        if (token != null && !token.isBlank()) {
            headers.set("Authorization", token);
        }
        var response = http.exchange(
                fileUrl + "/api/files/download/" + document.getFileId(),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class
        );
        if (response.getBody() == null) {
            throw new BusinessException("File service returned an empty source file");
        }
        return response.getBody();
    }

    private Map<String, Object> owned(String jobId, Long userId) {
        Map<String, Object> job = jobs.get(jobId);
        if (job == null) {
            job = loadJob(jobId);
            if (job != null) jobs.put(jobId, job);
        }
        if (job == null) {
            throw new BusinessException("Task does not exist or has expired");
        }
        if (!userId.equals(((Number) job.get("userId")).longValue())) {
            throw new BusinessException("You do not have permission to access this task");
        }
        return job;
    }

    private void update(Map<String, Object> job, String status, int progress, String message) {
        job.put("status", status);
        job.put("progress", progress);
        job.put("message", message);
        job.put("updatedAt", Instant.now().toString());
        persistJob(job);
    }

    private void persistJob(Map<String, Object> job) {
        try {
            String jobId = String.valueOf(job.get("jobId"));
            Long userId = ((Number) job.get("userId")).longValue();
            String key = REDIS_JOB_PREFIX + jobId;
            redis.opsForValue().set(key, new LinkedHashMap<>(job), JOB_TTL_DAYS, TimeUnit.DAYS);
            String userKey = REDIS_USER_PREFIX + userId;
            redis.opsForZSet().add(userKey, jobId, Instant.parse(String.valueOf(job.get("createdAt"))).toEpochMilli());
            redis.expire(userKey, JOB_TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception ignored) {
            // Redis unavailable: keep the in-memory fallback active.
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadJob(String jobId) {
        try {
            Object value = redis.opsForValue().get(REDIS_JOB_PREFIX + jobId);
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> restored = new ConcurrentHashMap<>();
                map.forEach((key, item) -> restored.put(String.valueOf(key), item));
                return restored;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void persistOutput(String jobId, byte[] output) {
        try {
            redis.opsForValue().set(REDIS_OUTPUT_PREFIX + jobId, output, JOB_TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception ignored) { }
    }

    private void persistToken(String jobId, String authorization) {
        if (authorization == null || authorization.isBlank()) return;
        try {
            redis.opsForValue().set(REDIS_TOKEN_PREFIX + jobId, authorization, 2, TimeUnit.HOURS);
        } catch (Exception ignored) { }
    }

    private String loadToken(String jobId) {
        try {
            Object token = redis.opsForValue().get(REDIS_TOKEN_PREFIX + jobId);
            return token == null ? null : String.valueOf(token);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Set<Integer> parsePages(String rawPages) {
        Set<Integer> pages = new TreeSet<>();
        if (rawPages == null || rawPages.isBlank()) {
            return pages;
        }
        for (String part : rawPages.split(",")) {
            String[] range = part.trim().split("-");
            int start = Integer.parseInt(range[0].trim());
            int end = range.length > 1 ? Integer.parseInt(range[1].trim()) : start;
            if (start < 1 || end < 1) {
                throw new BusinessException("PDF page numbers must start at 1");
            }
            for (int page = Math.min(start, end); page <= Math.max(start, end); page++) {
                pages.add(page);
            }
        }
        return pages;
    }

    private void requireExtension(DocumentVO document, String extension, String operation) {
        String title = Optional.ofNullable(document.getTitle()).orElse("").toLowerCase(Locale.ROOT);
        if (!title.endsWith(extension)) {
            throw new BusinessException(operation + " requires a " + extension + " file");
        }
    }

    private String fileName(String type) {
        return switch (type) {
            case "OCR" -> "ocr-result.txt";
            case "WORD_TABLE_EXCEL" -> "word-tables.xlsx";
            case "PDF_SPLIT" -> "pdf-pages.pdf";
            case "PDF_MERGE" -> "merged.pdf";
            case "DOCX_TO_PDF" -> "smartdoc-result.pdf";
            case "PDF_TO_DOCX" -> "smartdoc-editable.docx";
            default -> "smartdoc-result.bin";
        };
    }

    private String contentType(String type) {
        return switch (type) {
            case "OCR" -> "text/plain;charset=UTF-8";
            case "WORD_TABLE_EXCEL" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "PDF_TO_DOCX" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default -> "application/pdf";
        };
    }

    private void deleteDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort cleanup of a per-job temporary directory.
                }
            });
        } catch (IOException ignored) {
            // Best effort cleanup of a per-job temporary directory.
        }
    }

    private String safeMessage(Exception exception) {
        return Optional.ofNullable(exception.getMessage()).filter(message -> !message.isBlank()).orElse("Unknown error");
    }
}