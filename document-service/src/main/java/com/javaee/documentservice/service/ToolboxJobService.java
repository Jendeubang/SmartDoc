package com.javaee.documentservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.common.exception.BusinessException;
import com.javaee.documentservice.client.ToolboxFileClient;
import com.javaee.documentservice.dto.ToolboxJobRequest;
import com.javaee.documentservice.entity.ToolboxJob;
import com.javaee.documentservice.mapper.ToolboxJobMapper;
import com.javaee.documentservice.util.DocumentParserUtil;
import com.javaee.documentservice.vo.DocumentVO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 工具箱任务服务。MySQL 保存任务状态，RabbitMQ 负责调度，Redis 只缓存快照，MinIO 保存结果正文。
 */
@Service
public class ToolboxJobService {
    private static final Logger log = LoggerFactory.getLogger(ToolboxJobService.class);
    private static final String EXCHANGE = "file.exchange";
    private static final String QUEUE = "document.toolbox.queue";
    private static final String KEY = "document.toolbox";
    private static final String REDIS_JOB_PREFIX = "smartdoc:toolbox:job:";
    private static final String REDIS_USER_PREFIX = "smartdoc:toolbox:user:";
    private static final long JOB_TTL_DAYS = 7;
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "OCR", "PDF_SPLIT", "PDF_MERGE", "WORD_TABLE_EXCEL", "DOCX_TO_PDF", "PDF_TO_DOCX"
    );
    private static final long PROCESSING_LEASE_MINUTES = 10;

    private final DocumentService documents;
    private final RabbitTemplate rabbit;
    private final RedisTemplate<String, Object> redis;
    private final ToolboxFileClient fileClient;
    private final ToolboxJobMapper mapper;
    private final ToolboxResultStore resultStore;
    private final ObjectMapper objectMapper;

    public ToolboxJobService(DocumentService documents,
                             RabbitTemplate rabbit,
                             RedisTemplate<String, Object> redis,
                             ToolboxFileClient fileClient,
                             ToolboxJobMapper mapper,
                             ToolboxResultStore resultStore,
                             ObjectMapper objectMapper) {
        this.documents = documents;
        this.rabbit = rabbit;
        this.redis = redis;
        this.fileClient = fileClient;
        this.mapper = mapper;
        this.resultStore = resultStore;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> submit(ToolboxJobRequest request, Long userId) {
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
        String organizationId = singleOrganization(sourceDocuments);
        ToolboxJob job = new ToolboxJob();
        job.setJobId(UUID.randomUUID().toString().replace("-", ""));
        job.setUserId(userId);
        job.setOrganizationId(organizationId);
        job.setToolType(type);
        job.setDocumentIdsJson(writeJson(request.getDocumentIds()));
        job.setDocumentNamesJson(writeJson(sourceDocuments.stream().map(DocumentVO::getTitle).toList()));
        job.setPages(Optional.ofNullable(request.getPages()).orElse(""));
        job.setStatus("PENDING");
        job.setProgress(5);
        job.setMessage("Task queued");
        job.setRetryCount(0);
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(job.getCreatedAt());
        mapper.insert(job);
        cache(job);
        publish(job);
        return toMap(job);
    }

    public Map<String, Object> snapshot(String jobId, Long userId) {
        return toMap(owned(jobId, userId));
    }

    public List<Map<String, Object>> list(Long userId) {
        return mapper.listByUser(userId, 100).stream().map(this::toMap).toList();
    }

    public Map<String, Object> retry(String jobId, Long userId) {
        ToolboxJob job = owned(jobId, userId);
        if (!"FAILED".equals(job.getStatus())) {
            throw new BusinessException("Only failed tasks can be retried");
        }
        resultStore.delete(job);
        if (mapper.retryFailed(job.getJobId(), userId, job.getOrganizationId()) != 1) {
            throw new BusinessException("The task changed before it could be retried");
        }
        job.setStatus("PENDING");
        job.setProgress(5);
        job.setMessage("Retry queued");
        job.setRetryCount(Optional.ofNullable(job.getRetryCount()).orElse(0) + 1);
        job.setResultObjectKey(null);
        job.setFileName(null);
        job.setContentType(null);
        job.setUpdatedAt(LocalDateTime.now());
        cache(job);
        publish(job);
        return toMap(job);
    }

    public byte[] output(String jobId, Long userId) {
        ToolboxJob job = owned(jobId, userId);
        if (!"SUCCESS".equals(job.getStatus())) {
            throw new BusinessException("The result file is not ready yet");
        }
        return resultStore.get(job);
    }

    /** RabbitMQ redelivery is safe because only one consumer can transition PENDING to PROCESSING. */
    @RabbitListener(queues = QUEUE)
    public void process(String message) {
        MessageContext context = parseMessage(message);
        if (context == null) return;
        int claimed = mapper.claimPending(context.jobId(), Instant.now().minusSeconds(PROCESSING_LEASE_MINUTES * 60));
        if (claimed == 0) return;

        ToolboxJob job = mapper.selectById(context.jobId());
        if (job == null || !context.organizationId().equals(job.getOrganizationId())
                || !context.userId().equals(job.getUserId())) {
            mapper.markFailure(context.jobId(), "Invalid toolbox task ownership context");
            return;
        }

        Map<String, Object> snapshot = toMap(job);
        update(snapshot, 15, "Reading source file");
        String storedObjectKey = null;
        try {
            List<String> ids = readJsonList(job.getDocumentIdsJson());
            List<DocumentVO> sourceDocuments = new ArrayList<>();
            for (String documentId : ids) {
                DocumentVO document = documents.getById(documentId, job.getUserId());
                if (!sameOrganization(job.getOrganizationId(), document.getOrganizationId())) {
                    throw new BusinessException("The source document is outside the task tenant scope");
                }
                sourceDocuments.add(document);
            }

            byte[] result = switch (job.getToolType()) {
                case "OCR" -> ocr(sourceDocuments.get(0), snapshot);
                case "PDF_SPLIT" -> split(sourceDocuments.get(0), job.getPages(), snapshot);
                case "PDF_MERGE" -> merge(sourceDocuments, snapshot);
                case "WORD_TABLE_EXCEL" -> excel(sourceDocuments.get(0), snapshot);
                case "DOCX_TO_PDF" -> docxToPdf(sourceDocuments.get(0), snapshot);
                case "PDF_TO_DOCX" -> pdfToDocx(sourceDocuments.get(0), snapshot);
                default -> throw new BusinessException("Unsupported toolbox job type");
            };
            String fileName = fileName(job.getToolType());
            String contentType = contentType(job.getToolType());
            job.setFileName(fileName);
            job.setContentType(contentType);
            storedObjectKey = resultStore.put(job, result, contentType);
            job.setResultObjectKey(storedObjectKey);
            if (mapper.markSuccess(job.getJobId(), storedObjectKey, fileName, contentType,
                    "Completed. Your result is ready to download.") != 1) {
                resultStore.delete(job);
                return;
            }
            job.setStatus("SUCCESS");
            job.setProgress(100);
            job.setMessage("Completed. Your result is ready to download.");
            cache(job);
        } catch (Exception exception) {
            if (storedObjectKey != null) {
                try {
                    resultStore.delete(job);
                } catch (Exception cleanupException) {
                    log.warn("工具箱结果补偿删除失败 jobId={} objectKey={} error={}",
                            job.getJobId(), storedObjectKey, safeMessage(cleanupException));
                }
            }
            mapper.markFailure(job.getJobId(), "Processing failed: " + safeMessage(exception));
            snapshot.put("status", "FAILED");
            snapshot.put("progress", 100);
            snapshot.put("message", "Processing failed: " + safeMessage(exception));
            cache(snapshot, job);
        }
    }

    /**
     * Re-publish old pending jobs after a broker/worker interruption. The dispatch
     * timestamp is advanced before publishing, so a broker failure is retried on
     * the next sweep instead of flooding the queue in the same sweep.
     */
    @Scheduled(fixedDelayString = "${toolbox.recovery.fixed-delay-ms:30000}")
    void recoverPendingJobs() {
        Instant now = Instant.now();
        Instant staleBefore = now.minusSeconds(PROCESSING_LEASE_MINUTES * 60);
        mapper.resetAllStaleProcessing(staleBefore);
        List<ToolboxJob> candidates = mapper.selectDispatchable(
                now.minusSeconds(com.javaee.documentservice.config.RabbitMQConfig.TOOLBOX_PENDING_REQUEUE_SECONDS),
                com.javaee.documentservice.config.RabbitMQConfig.TOOLBOX_RECOVERY_BATCH_SIZE);
        for (ToolboxJob job : candidates) {
            if (mapper.markDispatched(job.getJobId()) != 1) continue;
            try {
                publish(job);
            } catch (Exception exception) {
                log.warn("工具箱任务重新投递失败 jobId={} organizationId={} error={}",
                        job.getJobId(), job.getOrganizationId(), safeMessage(exception));
            }
        }
    }

    /** Delete expired result objects first; a failed object deletion leaves the row retryable. */
    @Scheduled(fixedDelayString = "${toolbox.cleanup.fixed-delay-ms:3600000}")
    void cleanupExpiredResults() {
        Instant now = Instant.now();
        for (ToolboxJob job : mapper.selectExpired(now,
                com.javaee.documentservice.config.RabbitMQConfig.TOOLBOX_RECOVERY_BATCH_SIZE)) {
            try {
                resultStore.delete(job);
                if (mapper.markExpired(job.getJobId(), now) == 1) {
                    job.setStatus("EXPIRED");
                    job.setResultObjectKey(null);
                    job.setMessage("Result expired");
                    job.setUpdatedAt(LocalDateTime.now());
                    cache(job);
                }
            } catch (Exception exception) {
                log.warn("工具箱结果清理失败 jobId={} organizationId={} error={}",
                        job.getJobId(), job.getOrganizationId(), safeMessage(exception));
            }
        }
    }

    private byte[] ocr(DocumentVO document, Map<String, Object> job) {
        update(job, 50, "Running OCR recognition");
        String text = DocumentParserUtil.parseDocument(fileClient.download(document), document.getTitle());
        if (text.isBlank()) throw new BusinessException("No text was detected in this file");
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] split(DocumentVO document, String rawPages, Map<String, Object> job) throws IOException {
        update(job, 50, "Splitting PDF pages");
        Set<Integer> pages = parsePages(rawPages);
        try (PDDocument input = Loader.loadPDF(fileClient.download(document));
             PDDocument output = new PDDocument();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            for (int index = 0; index < input.getNumberOfPages(); index++) {
                if (pages.isEmpty() || pages.contains(index + 1)) output.importPage(input.getPage(index));
            }
            if (output.getNumberOfPages() == 0) throw new BusinessException("The requested PDF pages do not exist");
            output.save(bytes);
            return bytes.toByteArray();
        }
    }

    private byte[] merge(List<DocumentVO> documentsToMerge, Map<String, Object> job) throws IOException {
        try (PDDocument output = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            for (int index = 0; index < documentsToMerge.size(); index++) {
                update(job, 25 + index * 55 / documentsToMerge.size(), "Merging PDF " + (index + 1));
                try (PDDocument input = Loader.loadPDF(fileClient.download(documentsToMerge.get(index)))) {
                    for (int page = 0; page < input.getNumberOfPages(); page++) output.importPage(input.getPage(page));
                }
            }
            output.save(bytes);
            return bytes.toByteArray();
        }
    }

    private byte[] excel(DocumentVO document, Map<String, Object> job) throws IOException {
        update(job, 50, "Extracting Word tables into Excel");
        try (XWPFDocument word = new XWPFDocument(new ByteArrayInputStream(fileClient.download(document)));
             XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (word.getTables().isEmpty()) throw new BusinessException("No table was found in this DOCX file");
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
        update(job, 45, "Converting DOCX to PDF");
        Path directory = Files.createTempDirectory("smartdoc-docx-pdf-");
        try {
            Path input = directory.resolve("source.docx");
            Path output = directory.resolve("source.pdf");
            Files.write(input, fileClient.download(document));
            Process process = new ProcessBuilder("libreoffice", "--headless", "--convert-to", "pdf", "--outdir", directory.toString(), input.toString())
                    .redirectErrorStream(true).start();
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new BusinessException("DOCX to PDF conversion timed out");
            }
            String commandOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0 || !Files.exists(output)) {
                throw new BusinessException("DOCX to PDF conversion failed" + (commandOutput.isBlank() ? "" : ": " + commandOutput.trim()));
            }
            update(job, 85, "Generating PDF result");
            return Files.readAllBytes(output);
        } finally {
            deleteDirectory(directory);
        }
    }

    private byte[] pdfToDocx(DocumentVO document, Map<String, Object> job) throws IOException {
        requireExtension(document, ".pdf", "PDF to DOCX");
        update(job, 45, "Extracting editable text from PDF");
        try (PDDocument pdf = Loader.loadPDF(fileClient.download(document));
             XWPFDocument word = new XWPFDocument();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            String extractedText = new PDFTextStripper().getText(pdf).trim();
            if (extractedText.isBlank()) throw new BusinessException("The PDF contains no extractable text. Please use OCR first.");
            for (String paragraph : extractedText.split("\\R{2,}")) {
                String content = paragraph.trim();
                if (!content.isBlank()) word.createParagraph().createRun().setText(content);
            }
            update(job, 85, "Generating editable DOCX");
            word.write(bytes);
            return bytes.toByteArray();
        }
    }

    private ToolboxJob owned(String jobId, Long userId) {
        ToolboxJob job = mapper.selectByUser(jobId, userId);
        if (job == null) throw new BusinessException("Task does not exist or has expired");
        return job;
    }

    private void update(Map<String, Object> job, int progress, String message) {
        job.put("status", "PROCESSING");
        job.put("progress", progress);
        job.put("message", message);
        mapper.updateProgress(String.valueOf(job.get("jobId")), progress, message);
        ToolboxJob entity = mapper.selectById(String.valueOf(job.get("jobId")));
        if (entity != null) cache(entity);
    }

    private void publish(ToolboxJob job) {
        rabbit.convertAndSend(EXCHANGE, KEY, tenantMessage(job));
    }

    private void cache(ToolboxJob job) {
        cache(toMap(job), job);
    }

    private void cache(Map<String, Object> snapshot, ToolboxJob job) {
        try {
            String key = tenantPrefix(job) + REDIS_JOB_PREFIX + job.getJobId();
            redis.opsForValue().set(key, new LinkedHashMap<>(snapshot), JOB_TTL_DAYS, TimeUnit.DAYS);
            String userKey = REDIS_USER_PREFIX + job.getUserId();
            long score = job.getCreatedAt() == null ? System.currentTimeMillis() : job.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
            redis.opsForZSet().add(userKey, job.getJobId(), score);
            redis.expire(userKey, JOB_TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception ignored) {
            // Redis is a cache/progress channel; MySQL remains authoritative.
        }
    }

    private String tenantMessage(ToolboxJob job) {
        return job.getOrganizationId() + "|" + job.getUserId() + "|" + job.getJobId();
    }

    private String tenantPrefix(ToolboxJob job) {
        return "tenant:" + job.getOrganizationId() + ":";
    }

    private String singleOrganization(List<DocumentVO> sourceDocuments) {
        List<String> organizationIds = sourceDocuments.stream().map(DocumentVO::getOrganizationId)
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
        if (organizationIds.size() > 1 || (!organizationIds.isEmpty() && sourceDocuments.stream()
                .anyMatch(document -> document.getOrganizationId() == null || document.getOrganizationId().isBlank()))) {
            throw new BusinessException("A toolbox task cannot mix documents from different tenant scopes");
        }
        return organizationIds.isEmpty() ? "personal" : organizationIds.get(0);
    }

    private boolean sameOrganization(String taskOrganization, String documentOrganization) {
        String normalized = documentOrganization == null || documentOrganization.isBlank() ? "personal" : documentOrganization;
        return taskOrganization.equals(normalized);
    }

    private MessageContext parseMessage(String message) {
        if (message == null || message.isBlank()) return null;
        String[] parts = message.split("\\|", -1);
        if (parts.length != 3) return null;
        try {
            return new MessageContext(parts[0], Long.valueOf(parts[1]), parts[2]);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Map<String, Object> toMap(ToolboxJob job) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", job.getJobId());
        result.put("userId", job.getUserId());
        result.put("organizationId", job.getOrganizationId());
        result.put("toolType", job.getToolType());
        result.put("documentIds", readJsonList(job.getDocumentIdsJson()));
        result.put("documentNames", readJsonList(job.getDocumentNamesJson()));
        result.put("pages", Optional.ofNullable(job.getPages()).orElse(""));
        result.put("status", job.getStatus());
        result.put("progress", Optional.ofNullable(job.getProgress()).orElse(0));
        result.put("message", job.getMessage());
        result.put("retryCount", Optional.ofNullable(job.getRetryCount()).orElse(0));
        result.put("resultObjectKey", job.getResultObjectKey());
        result.put("fileName", job.getFileName());
        result.put("contentType", job.getContentType());
        result.put("createdAt", stringTime(job.getCreatedAt()));
        result.put("updatedAt", stringTime(job.getUpdatedAt()));
        result.put("startedAt", stringTime(job.getStartedAt()));
        result.put("finishedAt", stringTime(job.getFinishedAt()));
        result.put("expiresAt", stringTime(job.getExpiresAt()));
        return result;
    }

    private String stringTime(LocalDateTime time) {
        return time == null ? null : time.toString();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("Failed to persist toolbox task context");
        }
    }

    private List<String> readJsonList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new BusinessException("Toolbox task context is corrupted");
        }
    }

    private Set<Integer> parsePages(String rawPages) {
        Set<Integer> pages = new TreeSet<>();
        if (rawPages == null || rawPages.isBlank()) return pages;
        for (String part : rawPages.split(",")) {
            String[] range = part.trim().split("-");
            int start = Integer.parseInt(range[0].trim());
            int end = range.length > 1 ? Integer.parseInt(range[1].trim()) : start;
            if (start < 1 || end < 1) throw new BusinessException("PDF page numbers must start at 1");
            for (int page = Math.min(start, end); page <= Math.max(start, end); page++) pages.add(page);
        }
        return pages;
    }

    private void requireExtension(DocumentVO document, String extension, String operation) {
        String title = Optional.ofNullable(document.getTitle()).orElse("").toLowerCase(Locale.ROOT);
        if (!title.endsWith(extension)) throw new BusinessException(operation + " requires a " + extension + " file");
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
        if (directory == null) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private String safeMessage(Exception exception) {
        return Optional.ofNullable(exception.getMessage()).filter(message -> !message.isBlank()).orElse("Unknown error");
    }

    private record MessageContext(String organizationId, Long userId, String jobId) { }
}
