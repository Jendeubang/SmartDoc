package com.javaee.documentservice.collaborate;

import com.javaee.documentservice.service.ParagraphLockService;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;

@Component
public class CollaborationDisconnectListener {
    private final CollaborateSessionManager sessionManager;
    private final CursorSyncHandler cursorSyncHandler;
    private final ParagraphLockService paragraphLockService;
    private final SimpMessagingTemplate messagingTemplate;

    public CollaborationDisconnectListener(CollaborateSessionManager sessionManager,
                                           CursorSyncHandler cursorSyncHandler,
                                           ParagraphLockService paragraphLockService,
                                           SimpMessagingTemplate messagingTemplate) {
        this.sessionManager = sessionManager;
        this.cursorSyncHandler = cursorSyncHandler;
        this.paragraphLockService = paragraphLockService;
        this.messagingTemplate = messagingTemplate;
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        Principal principal = event.getUser();
        if (principal == null) return;
        String userId = principal.getName();
        String documentId = sessionManager.getUserCurrentDocument(userId);
        if (documentId == null) return;
        sessionManager.userLeaveDocument(documentId, userId);
        cursorSyncHandler.removeCursor(documentId, userId);
        paragraphLockService.releaseAllByUser(documentId, Long.valueOf(userId));
        DocumentJoinMessage leave = new DocumentJoinMessage(documentId, userId, userId, "leave");
        messagingTemplate.convertAndSend("/topic/doc/" + documentId, CollaborateMessage.leave(leave));
    }
}
