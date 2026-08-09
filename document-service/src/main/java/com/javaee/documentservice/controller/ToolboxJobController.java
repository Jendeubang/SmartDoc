package com.javaee.documentservice.controller;

import com.javaee.common.model.Result;
import com.javaee.documentservice.dto.ToolboxJobRequest;
import com.javaee.documentservice.security.RequestUserContext;
import com.javaee.documentservice.service.ToolboxJobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents/toolbox/jobs")
public class ToolboxJobController {
    private final ToolboxJobService jobs; private final RequestUserContext users;
    public ToolboxJobController(ToolboxJobService jobs, RequestUserContext users) { this.jobs = jobs; this.users = users; }
    @PostMapping public Result<Map<String, Object>> submit(@RequestBody ToolboxJobRequest request, @RequestHeader("Authorization") String authorization) { return Result.success(jobs.submit(request, users.getRequiredUserId(), authorization)); }
    @GetMapping public Result<List<Map<String, Object>>> list() { return Result.success(jobs.list(users.getRequiredUserId())); }
    @PostMapping("/{jobId}/retry") public Result<Map<String, Object>> retry(@PathVariable String jobId, @RequestHeader("Authorization") String authorization) { return Result.success(jobs.retry(jobId, users.getRequiredUserId(), authorization)); }
    @GetMapping("/{jobId}") public Result<Map<String, Object>> status(@PathVariable String jobId) { return Result.success(jobs.snapshot(jobId, users.getRequiredUserId())); }
    @GetMapping("/{jobId}/download") public ResponseEntity<byte[]> download(@PathVariable String jobId) { Map<String, Object> job = jobs.snapshot(jobId, users.getRequiredUserId()); return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=" + job.get("fileName")).header("Content-Type", String.valueOf(job.get("contentType"))).body(jobs.output(jobId, users.getRequiredUserId())); }
}
