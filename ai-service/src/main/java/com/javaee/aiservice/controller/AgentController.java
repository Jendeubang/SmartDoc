package com.javaee.aiservice.controller;

/**
 * 【简历：Agent 交互 API 入口】
 * 提供 Agent 聊天、任务执行、工具列表查询、重试、审批确认等接口。
 */

import com.javaee.aiservice.agent.ChatService;
import com.javaee.aiservice.agent.KnowledgeIndexAgent;
import com.javaee.aiservice.agent.execution.AgentExecutionService;
import com.javaee.aiservice.agent.execution.model.AgentExecutionRequest;
import com.javaee.aiservice.agent.execution.tool.AgentToolDefinition;
import com.javaee.aiservice.conversation.ConversationManager;
import com.javaee.aiservice.rag.DocumentSegmenter;
import com.javaee.aiservice.rag.KnowledgeBase;
import com.javaee.aiservice.security.RequestUserContext;
import com.javaee.common.model.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/agent")
@Tag(name = "AI Agent", description = "AI Agent相关接口")
public class AgentController {

    @Autowired
    private KnowledgeIndexAgent knowledgeIndexAgent;

    @Autowired
    private AgentExecutionService agentExecutionService;

    @Autowired
    private ConversationManager conversationManager;

    @Autowired
    private RequestUserContext requestUserContext;

    @Autowired
    private KnowledgeBase knowledgeBase;

    @Autowired
    private ChatService chatService;

    @PostMapping("/execute")
    @Operation(summary = "执行统一Agent链路", description = "自动完成任务规划、工具调用、RAG检索、最终回答和对话记忆")
    public Result<Map<String, Object>> executeAgent(@RequestBody AgentExecutionRequest request) {
        request.setUserId(requestUserContext.getRequiredUserId());
        Map<String, Object> result = agentExecutionService.execute(request);
        return Result.success(result);
    }

    @GetMapping("/tools")
    @Operation(summary = "获取Agent工具列表", description = "获取统一Agent可调用的全部工具能力")
    public Result<List<AgentToolDefinition>> listTools() {
        return Result.success(agentExecutionService.listTools());
    }

    @PostMapping("/approvals/confirm")
    @Operation(summary = "确认危险操作", description = "携带 agentApprovalToken 继续执行原挂起任务")
    public Result<Map<String, Object>> confirmApproval(@RequestBody AgentExecutionRequest request) {
        request.setUserId(requestUserContext.getRequiredUserId());
        Map<String, Object> result = agentExecutionService.confirmApproval(request);
        return Result.success(result);
    }

    @PostMapping("/knowledge/index")
    @Operation(summary = "Agent知识库索引", description = "异步对文档内容进行解析、分块、向量化并入库")
    public Result<Map<String, Object>> indexKnowledge(
            @Parameter(description = "文档ID") @RequestParam String documentId,
            @Parameter(description = "文档内容") @RequestBody String content) {
        Map<String, Object> metadata = userMetadata();
        Map<String, Object> result = knowledgeIndexAgent.indexDocumentAsync(documentId, content, metadata);
        return Result.success(result);
    }

    @PostMapping("/knowledge/index/segment")
    @Operation(summary = "Agent知识库索引（指定策略）", description = "指定分段策略对文档内容进行解析、分块、向量化并入库")
    public Result<Map<String, Object>> indexKnowledgeWithSegment(
            @Parameter(description = "文档ID") @RequestParam String documentId,
            @Parameter(description = "文档内容") @RequestBody String content,
            @Parameter(description = "分段策略: AUTO, FIXED_LENGTH, CHAPTER, SEMANTIC, HYBRID")
            @RequestParam(defaultValue = "AUTO") String strategy) {
        DocumentSegmenter.StrategyType strategyType;
        try {
            strategyType = DocumentSegmenter.StrategyType.valueOf(strategy.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Result.fail("无效的分段策略: " + strategy);
        }
        Map<String, Object> metadata = userMetadata();
        metadata.put("segmentStrategy", strategyType.name());
        Map<String, Object> result = knowledgeIndexAgent.indexDocumentAsync(documentId, content, metadata);
        return Result.success(result);
    }

    @GetMapping("/knowledge/search")
    @Operation(summary = "Agent知识库检索", description = "在已索引的知识库中检索相关文档片段")
    public Result<List<Map<String, Object>>> searchKnowledge(
            @Parameter(description = "查询词") @RequestParam String query,
            @Parameter(description = "返回数量") @RequestParam(defaultValue = "5") int topK) {
        String userId = requestUserContext.getRequiredUserId();
        List<Map<String, Object>> results = knowledgeIndexAgent.searchKnowledge(query, topK, userId, "default");
        return Result.success(results);
    }

    @GetMapping("/knowledge/jobs")
    @Operation(summary = "Agent知识库任务列表", description = "获取知识库索引任务列表")
    public Result<List<Map<String, Object>>> listKnowledgeJobs() {
        String userId = requestUserContext.getRequiredUserId();
        List<Map<String, Object>> jobs = knowledgeIndexAgent.listJobs(userId, "default");
        return Result.success(jobs);
    }

    @GetMapping("/knowledge/jobs/{jobId}")
    @Operation(summary = "查询Agent知识库任务状态", description = "根据任务ID查询知识库索引任务状态")
    public Result<Map<String, Object>> getKnowledgeJobStatus(
            @Parameter(description = "任务ID") @PathVariable String jobId) {
        Map<String, Object> status = knowledgeIndexAgent.getJobStatus(jobId);
        return Result.success(status);
    }

    @PostMapping("/knowledge/jobs/{jobId}/retry")
    @Operation(summary = "重试Agent知识库索引任务", description = "对失败的知识库索引任务进行重试")
    public Result<Map<String, Object>> retryKnowledgeJob(
            @Parameter(description = "任务ID") @PathVariable String jobId,
            @Parameter(description = "文档内容") @RequestBody String content) {
        Map<String, Object> result = knowledgeIndexAgent.retryJob(jobId, content, userMetadata());
        return Result.success(result);
    }

    @PostMapping("/rag/query")
    @Operation(summary = "Agent知识库问答", description = "基于RAG架构进行知识库问答")
    public Result<Map<String, Object>> ragQuery(
            @Parameter(description = "问题") @RequestBody String question,
            @Parameter(description = "重排序策略") @RequestParam(defaultValue = "HYBRID") String strategy) {
        com.javaee.aiservice.rag.Reranker.RerankStrategy rerankStrategy;
        try {
            rerankStrategy = com.javaee.aiservice.rag.Reranker.RerankStrategy.valueOf(strategy.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Result.fail("无效的重排序策略: " + strategy);
        }
        String userId = requestUserContext.getRequiredUserId();
        List<Map<String, Object>> results = knowledgeBase.hybridSearchWithRerank(
                question, 3, rerankStrategy, userId, "default");
        StringBuilder context = new StringBuilder();
        for (Map<String, Object> r : results) {
            context.append(r.getOrDefault("content", "")).append("\n\n");
        }
        String answer = context.isEmpty() ? "知识库中未找到相关信息。"
                : chatService.callChatApi(context.toString());
        return Result.success(Map.of("question", question, "answer", answer, "sources", results));
    }

    @GetMapping("/knowledge/statistics")
    @Operation(summary = "Agent知识库统计", description = "获取知识库的统计信息")
    public Result<Map<String, Object>> getKnowledgeStatistics() {
        String userId = requestUserContext.getRequiredUserId();
        Map<String, Object> statistics = knowledgeBase.getStatistics(userId, "default");
        return Result.success(statistics);
    }

    @GetMapping("/conversations/{conversationId}/history")
    @Operation(summary = "获取对话历史", description = "获取指定对话的消息历史列表")
    public Result<List<String>> getConversationHistory(
            @Parameter(description = "对话ID") @PathVariable String conversationId) {
        String userId = requestUserContext.getRequiredUserId();
        return Result.success(conversationManager.getConversationHistoryForUser(conversationId, userId));
    }

    @PostMapping("/chat")
    @Operation(summary = "Agent对话", description = "接收自然语言指令，自动判断是否走规划-执行链路")
    public Result<Map<String, Object>> chat(
            @Parameter(description = "对话内容") @RequestBody String message) {
        AgentExecutionRequest request = new AgentExecutionRequest();
        request.setTask(message);
        request.setUserId(requestUserContext.getRequiredUserId());
        request.setContext(Map.of("skipApproval", true));
        Map<String, Object> result = agentExecutionService.execute(request);
        return Result.success(result);
    }

    @PostMapping("/plan/execute")
    @Operation(summary = "执行规划任务", description = "兼容旧接口，内部使用统一Agent链路执行复杂任务")
    public Result<Map<String, Object>> executePlan(
            @Parameter(description = "任务描述") @RequestBody String task) {
        AgentExecutionRequest request = new AgentExecutionRequest();
        request.setTask(task);
        request.setUserId(requestUserContext.getRequiredUserId());
        request.setContext(Map.of());
        Map<String, Object> result = agentExecutionService.execute(request);
        return Result.success(result);
    }

    private Map<String, Object> userMetadata() {
        return Map.of(
                "userId", requestUserContext.getRequiredUserId(),
                "knowledgeBaseId", "default"
        );
    }
}
