package com.javaee.documentservice.controller;

import com.javaee.common.utils.JwtUtils;
import com.javaee.documentservice.collaborate.*;
import com.javaee.documentservice.config.WebSocketAuthConfig.CollaborationPrincipal;
import com.javaee.documentservice.entity.Document;
import com.javaee.documentservice.mapper.DocumentMapper;
import com.javaee.documentservice.service.DocumentAccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@Controller
public class CollaborateController {

    private static final Logger log = LoggerFactory.getLogger(CollaborateController.class);

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private CollaborateSessionManager sessionManager;

    @Autowired
    private EditOperationHandler editOperationHandler;

    @Autowired
    private CursorSyncHandler cursorSyncHandler;

    @Autowired
    private DocumentSnapshotService snapshotService;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private DocumentAccessService documentAccessService;

    @MessageMapping("/collaborate/join")
    public void joinDocument(@Payload DocumentJoinMessage joinMessage, Principal principal) {
        String userId = extractUserId(principal);
        String userName = extractUserName(principal);
        assertCanRead(joinMessage.getDocumentId(), userId);

        joinMessage.setUserId(userId);
        joinMessage.setUserName(userName);

        sessionManager.userJoinDocument(joinMessage.getDocumentId(), userId, userName);

        CollaborateMessage joinMsg = CollaborateMessage.join(joinMessage);
        messagingTemplate.convertAndSend("/topic/doc/" + joinMessage.getDocumentId(), joinMsg);

        List<CursorPosition> cursors = cursorSyncHandler.getDocumentCursors(joinMessage.getDocumentId());
        for (CursorPosition cursor : cursors) {
            if (!cursor.getUserId().equals(userId)) {
                messagingTemplate.convertAndSend("/topic/doc/" + joinMessage.getDocumentId(),
                        CollaborateMessage.cursor(cursor));
            }
        }

        log.info("用户加入协同编辑: userId={}, userName={}, documentId={}",
                userId, userName, joinMessage.getDocumentId());
    }

    @MessageMapping("/collaborate/leave")
    public void leaveDocument(@Payload DocumentJoinMessage leaveMessage, Principal principal) {
        String userId = extractUserId(principal);
        String userName = extractUserName(principal);
        assertCanRead(leaveMessage.getDocumentId(), userId);

        leaveMessage.setUserId(userId);
        leaveMessage.setUserName(userName);

        sessionManager.userLeaveDocument(leaveMessage.getDocumentId(), userId);
        cursorSyncHandler.removeCursor(leaveMessage.getDocumentId(), userId);

        CollaborateMessage leaveMsg = CollaborateMessage.leave(leaveMessage);
        messagingTemplate.convertAndSend("/topic/doc/" + leaveMessage.getDocumentId(), leaveMsg);

        log.info("用户离开协同编辑: userId={}, documentId={}", userId, leaveMessage.getDocumentId());
    }

    @MessageMapping("/collaborate/edit")
    public void handleEdit(@Payload EditOperation operation, Principal principal) {
        String userId = extractUserId(principal);
        String userName = extractUserName(principal);
        assertCanWrite(operation.getDocumentId(), userId);

        operation.setUserId(userId);
        operation.setUserName(userName);

        long clock = editOperationHandler.getNextClock(operation.getDocumentId(), userId);
        operation.setClock(clock);
        operation.setOpId(userId + "-" + clock);

        editOperationHandler.saveOperation(operation);

        CollaborateMessage editMsg = CollaborateMessage.edit(operation);
        messagingTemplate.convertAndSend("/topic/doc/" + operation.getDocumentId(), editMsg);

        log.debug("编辑操作已广播: opId={}, type={}, documentId={}, userId={}",
                operation.getOpId(), operation.getType(), operation.getDocumentId(), userId);
    }

    @MessageMapping("/collaborate/cursor")
    public void handleCursor(@Payload CursorPosition cursor, Principal principal) {
        String userId = extractUserId(principal);
        String userName = extractUserName(principal);
        assertCanRead(cursor.getDocumentId(), userId);

        cursor.setUserId(userId);
        cursor.setUserName(userName);
        cursor.setTimestamp(System.currentTimeMillis());

        cursorSyncHandler.updateCursor(cursor);

        CollaborateMessage cursorMsg = CollaborateMessage.cursor(cursor);
        messagingTemplate.convertAndSend("/topic/doc/" + cursor.getDocumentId(), cursorMsg);
    }

    @MessageMapping("/collaborate/sync")
    public void handleSync(@Payload Map<String, Object> syncRequest, Principal principal) {
        String userId = extractUserId(principal);
        String documentId = (String) syncRequest.get("documentId");
        assertCanRead(documentId, userId);
        long sinceClock = syncRequest.get("sinceClock") != null
                ? ((Number) syncRequest.get("sinceClock")).longValue() : 0;

        List<EditOperation> missedOps = editOperationHandler.getOperationsSince(documentId, sinceClock);
        for (EditOperation op : missedOps) {
            CollaborateMessage editMsg = CollaborateMessage.edit(op);
            messagingTemplate.convertAndSendToUser(userId, "/topic/doc/" + documentId, editMsg);
        }

        log.debug("同步操作: userId={}, documentId={}, sinceClock={}, missedOps={}",
                userId, documentId, sinceClock, missedOps.size());
    }

    private String extractUserId(Principal principal) {
        if (principal == null) throw new IllegalArgumentException("WebSocket authentication required");
        return principal.getName();
    }

    private String extractUserName(Principal principal) {
        if (principal instanceof CollaborationPrincipal collaborationPrincipal) {
            return collaborationPrincipal.username() == null ? collaborationPrincipal.name() : collaborationPrincipal.username();
        }
        return extractUserId(principal);
    }

    private void assertCanRead(String documentId, String userId) {
        Document document = requiredDocument(documentId);
        documentAccessService.assertCanRead(document, Long.valueOf(userId));
    }

    private void assertCanWrite(String documentId, String userId) {
        Document document = requiredDocument(documentId);
        documentAccessService.assertCanWrite(document, Long.valueOf(userId));
    }

    private Document requiredDocument(String documentId) {
        if (documentId == null || documentId.isBlank()) throw new IllegalArgumentException("Document id is required");
        Document document = documentMapper.selectById(documentId);
        if (document == null) throw new IllegalArgumentException("Document does not exist");
        return document;
    }
}
