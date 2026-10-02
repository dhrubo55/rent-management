package com.renteasebd.common;

import com.renteasebd.domain.audit.AuditLog;
import com.renteasebd.repository.AuditLogRepository;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public void log(Long actorUserId, String action, String entityType, String entityId, Object oldState, Object newState) {
        AuditLog log = new AuditLog();
        log.setActorUserId(actorUserId);
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        // The audit envelope records who changed which resource. State objects can
        // contain decrypted phones, NIDs, tokens, or document bytes.
        log.setOldStateJson(null);
        log.setNewStateJson(null);
        log.setCreatedAt(LocalDateTime.now());
        auditLogRepository.save(log);
    }

}
