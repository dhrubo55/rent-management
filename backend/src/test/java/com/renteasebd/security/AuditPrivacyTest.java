package com.renteasebd.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.renteasebd.common.AuditService;
import com.renteasebd.domain.audit.AuditLog;
import com.renteasebd.repository.AuditLogRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AuditPrivacyTest {
    @Test
    void auditNeverPersistsSensitiveStateObjects() {
        AuditLogRepository repository = Mockito.mock(AuditLogRepository.class);
        AuditService audit = new AuditService(repository);
        audit.log(1L, "TENANT_UPDATED", "TENANT", "2",
            Map.of("phonePrimary", "+8801712345678"),
            Map.of("nidNumber", "12345678901234567", "document", new byte[] {1, 2, 3}));

        ArgumentCaptor<AuditLog> captured = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captured.capture());
        assertThat(captured.getValue().getOldStateJson()).isNull();
        assertThat(captured.getValue().getNewStateJson()).isNull();
        assertThat(captured.getValue().getAction()).isEqualTo("TENANT_UPDATED");
        assertThat(captured.getValue().getEntityId()).isEqualTo("2");
    }
}
