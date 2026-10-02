package com.renteasebd.notification;

import com.renteasebd.common.AuditService;
import com.renteasebd.domain.invoice.Invoice;
import com.renteasebd.domain.invoice.InvoiceStatus;
import com.renteasebd.domain.notification.NotificationChannel;
import com.renteasebd.domain.notification.NotificationEvent;
import com.renteasebd.domain.notification.NotificationStatus;
import com.renteasebd.domain.property.Property;
import com.renteasebd.domain.property.PropertyUnit;
import com.renteasebd.domain.tenant.Tenant;
import com.renteasebd.domain.user.UserRole;
import com.renteasebd.notification.dto.NotificationEventResponse;
import com.renteasebd.repository.InvoiceRepository;
import com.renteasebd.repository.NotificationEventRepository;
import com.renteasebd.repository.PropertyUnitRepository;
import com.renteasebd.security.ResourceAccessService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private static final Set<InvoiceStatus> OVERDUE_SOURCE_STATUSES = EnumSet.of(
        InvoiceStatus.SENT, InvoiceStatus.PARTIALLY_PAID, InvoiceStatus.OVERDUE
    );

    private final NotificationEventRepository notificationEventRepository;
    private final PropertyUnitRepository propertyUnitRepository;
    private final InvoiceRepository invoiceRepository;
    private final AuditService auditService;
    private final ResourceAccessService access;

    public NotificationService(
        NotificationEventRepository notificationEventRepository,
        PropertyUnitRepository propertyUnitRepository,
        InvoiceRepository invoiceRepository,
        AuditService auditService,
        ResourceAccessService access
    ) {
        this.notificationEventRepository = notificationEventRepository;
        this.propertyUnitRepository = propertyUnitRepository;
        this.invoiceRepository = invoiceRepository;
        this.auditService = auditService;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public List<NotificationEventResponse> list(Long actorUserId, UserRole role, Long tenantId) {
        if (role == UserRole.OWNER) {
            return (tenantId == null
                ? notificationEventRepository.findByOwnerUserIdOrderByCreatedAtDesc(actorUserId)
                : notificationEventRepository.findByOwnerUserIdAndTenantIdOrderByCreatedAtDesc(actorUserId, tenantId))
                .stream()
                .map(this::toResponse)
                .toList();
        }
        return List.of();
    }

    @Transactional
    public int createOverdueReminders(Long actorUserId, UserRole role) {
        List<Property> accessibleProperties = access.accessibleProperties(actorUserId, role);
        if (accessibleProperties.isEmpty()) {
            return 0;
        }
        List<Long> propertyIds = accessibleProperties.stream().map(Property::getId).toList();
        List<PropertyUnit> units = propertyUnitRepository.findByPropertyIdIn(propertyIds);
        if (units.isEmpty()) {
            return 0;
        }
        List<Long> unitIds = units.stream().map(PropertyUnit::getId).toList();
        List<Invoice> invoices = invoiceRepository.findByPropertyUnitIdInOrderByBillingPeriodStartDescIdDesc(unitIds).stream()
            .filter(invoice -> OVERDUE_SOURCE_STATUSES.contains(invoice.getStatus()))
            .filter(invoice -> invoice.getBalanceDueBdt().compareTo(BigDecimal.ZERO) > 0)
            .filter(invoice -> invoice.getDueDate().isBefore(LocalDate.now()))
            .toList();

        int created = 0;
        for (Invoice invoice : invoices) {
            PropertyUnit unit = units.stream().filter(u -> u.getId().equals(invoice.getPropertyUnitId())).findFirst()
                .orElse(null);
            if (unit == null) {
                continue;
            }
            Property property = accessibleProperties.stream().filter(p -> p.getId().equals(unit.getPropertyId())).findFirst()
                .orElse(null);
            if (property == null) {
                continue;
            }
            NotificationEvent event = buildMockSentEvent(
                property.getOwnerId(),
                invoice.getTenantId(),
                "OVERDUE_REMINDER",
                "Invoice #" + invoice.getId() + " is overdue with outstanding " + invoice.getBalanceDueBdt(),
                "INVOICE",
                String.valueOf(invoice.getId())
            );
            notificationEventRepository.save(event);
            created++;
        }

        auditService.log(actorUserId, "NOTIFICATION_OVERDUE_REMINDERS_CREATED", "NOTIFICATION", "BULK", null,
            java.util.Map.of("createdCount", created));
        return created;
    }

    @Transactional
    public void onInvoiceSent(Invoice invoice, Tenant tenant, Property property) {
        NotificationEvent event = buildMockSentEvent(
            property.getOwnerId(),
            tenant.getId(),
            "INVOICE_SENT",
            "Invoice #" + invoice.getId() + " sent. Due " + invoice.getDueDate() + ", amount " + invoice.getTotalDueBdt(),
            "INVOICE",
            String.valueOf(invoice.getId())
        );
        notificationEventRepository.save(event);
    }

    @Transactional
    public void onPaymentReceipt(
        Tenant tenant,
        Property property,
        Invoice invoice,
        BigDecimal amountBdt,
        LocalDate paymentDate,
        String paymentMethod
    ) {
        NotificationEvent event = buildMockSentEvent(
            property.getOwnerId(),
            tenant.getId(),
            "PAYMENT_RECEIPT",
            "Payment received for Invoice #" + invoice.getId() + ": " + amountBdt + " via " + paymentMethod + " on " + paymentDate,
            "INVOICE",
            String.valueOf(invoice.getId())
        );
        notificationEventRepository.save(event);
    }

    @Transactional
    public void onMoveOutSettlement(Tenant tenant, Property property, BigDecimal deduction, BigDecimal refund, LocalDate moveOutDate) {
        NotificationEvent event = buildMockSentEvent(
            property.getOwnerId(),
            tenant.getId(),
            "MOVE_OUT_SETTLEMENT",
            "Move-out settlement posted on " + moveOutDate + ". Deduction: " + deduction + ", Refund: " + refund,
            "TENANT",
            String.valueOf(tenant.getId())
        );
        notificationEventRepository.save(event);
    }

    private NotificationEvent buildMockSentEvent(
        Long ownerUserId,
        Long tenantId,
        String eventType,
        String message,
        String relatedEntityType,
        String relatedEntityId
    ) {
        NotificationEvent event = new NotificationEvent();
        event.setOwnerUserId(ownerUserId);
        event.setTenantId(tenantId);
        event.setEventType(eventType);
        event.setChannel(NotificationChannel.SMS);
        event.setStatus(NotificationStatus.MOCK_SENT);
        event.setDestination(null);
        event.setMessage(message);
        event.setRelatedEntityType(relatedEntityType);
        event.setRelatedEntityId(relatedEntityId);
        event.setSentAt(LocalDateTime.now());
        return event;
    }

    private NotificationEventResponse toResponse(NotificationEvent event) {
        return new NotificationEventResponse(
            event.getId(),
            event.getTenantId(),
            event.getEventType(),
            event.getChannel().name(),
            event.getStatus().name(),
            event.getDestination(),
            event.getMessage(),
            event.getRelatedEntityType(),
            event.getRelatedEntityId(),
            event.getErrorMessage(),
            event.getCreatedAt(),
            event.getSentAt()
        );
    }
}
