package com.renteasebd.tenant;

import com.renteasebd.audit.dto.AuditTimelineItemResponse;
import com.renteasebd.common.AppException;
import com.renteasebd.common.AuditService;
import com.renteasebd.domain.audit.AuditLog;
import com.renteasebd.domain.property.OccupancyStatus;
import com.renteasebd.domain.property.PropertyUnit;
import com.renteasebd.domain.invoice.Invoice;
import com.renteasebd.domain.invoice.InvoicePayment;
import com.renteasebd.domain.tenant.SecurityDepositStatus;
import com.renteasebd.domain.tenant.Tenant;
import com.renteasebd.domain.tenant.TenantStatus;
import com.renteasebd.domain.tenant.TenantUnitHistory;
import com.renteasebd.domain.user.UserRole;
import com.renteasebd.security.ResourceAccessService;
import com.renteasebd.repository.InvoicePaymentRepository;
import com.renteasebd.repository.InvoiceRepository;
import com.renteasebd.repository.PropertyUnitRepository;
import com.renteasebd.repository.SecurityDepositTransactionRepository;
import com.renteasebd.repository.AuditLogRepository;
import com.renteasebd.repository.TenantRepository;
import com.renteasebd.repository.TenantUnitHistoryRepository;
import com.renteasebd.tenant.dto.CreateTenantRequest;
import com.renteasebd.tenant.dto.TenantDetailResponse;
import com.renteasebd.tenant.dto.TenantHistoryResponse;
import com.renteasebd.tenant.dto.TenantLedgerResponse;
import com.renteasebd.tenant.dto.TenantResponse;
import com.renteasebd.tenant.dto.UpdateTenantRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class TenantService {

    private final TenantRepository tenantRepository;
    private final PropertyUnitRepository unitRepository;
    private final TenantUnitHistoryRepository historyRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoicePaymentRepository invoicePaymentRepository;
    private final SecurityDepositTransactionRepository securityDepositTransactionRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuditService auditService;
    private final ResourceAccessService access;

    public TenantService(
        TenantRepository tenantRepository,
        PropertyUnitRepository unitRepository,
        TenantUnitHistoryRepository historyRepository,
        InvoiceRepository invoiceRepository,
        InvoicePaymentRepository invoicePaymentRepository,
        SecurityDepositTransactionRepository securityDepositTransactionRepository,
        AuditLogRepository auditLogRepository,
        AuditService auditService,
        ResourceAccessService access
    ) {
        this.tenantRepository = tenantRepository;
        this.unitRepository = unitRepository;
        this.historyRepository = historyRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoicePaymentRepository = invoicePaymentRepository;
        this.securityDepositTransactionRepository = securityDepositTransactionRepository;
        this.auditLogRepository = auditLogRepository;
        this.auditService = auditService;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public List<TenantResponse> list(TenantStatus status, Long propertyId, Long actorUserId, UserRole role) {
        List<Tenant> tenants;
        if (propertyId != null) {
            access.requireProperty(propertyId, actorUserId, role);
            List<Long> unitIds = unitRepository.findByPropertyId(propertyId).stream().map(PropertyUnit::getId).toList();
            if (unitIds.isEmpty()) {
                return Collections.emptyList();
            }
            tenants = status == null
                ? tenantRepository.findByPropertyUnitIdIn(unitIds)
                : tenantRepository.findByPropertyUnitIdInAndStatus(unitIds, status);
        } else {
            List<Long> unitIds = access.accessibleProperties(actorUserId, role).stream()
                .flatMap(property -> unitRepository.findByPropertyId(property.getId()).stream())
                .map(PropertyUnit::getId).toList();
            if (unitIds.isEmpty()) return List.of();
            tenants = status == null
                ? tenantRepository.findByPropertyUnitIdIn(unitIds)
                : tenantRepository.findByPropertyUnitIdInAndStatus(unitIds, status);
        }
        return tenants.stream()
            .sorted(Comparator.comparing(Tenant::getId).reversed())
            .map(this::toResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public TenantResponse get(Long tenantId, Long actorUserId, UserRole role) {
        return toResponse(getTenant(tenantId, actorUserId, role));
    }

    @Transactional(readOnly = true)
    public TenantDetailResponse getDetail(Long tenantId, Long actorUserId, UserRole role) {
        return toDetailResponse(getTenant(tenantId, actorUserId, role), role == UserRole.OWNER);
    }

    @Transactional
    public TenantResponse create(CreateTenantRequest request, Long actorUserId, UserRole role) {
        validateTenantBusinessRules(request.getLeaseStartDate(), request.getLeaseEndDate(), request.getNidImage());

        PropertyUnit unit = getUnit(request.getPropertyUnitId());
        access.requireUnit(unit.getId(), actorUserId, role);
        assertUnitCanAcceptActiveTenant(unit.getId(), unit.getOccupancyStatus());

        Tenant tenant = new Tenant();
        tenant.setFullName(request.getFullName());
        tenant.setPhonePrimary(request.getPhonePrimary());
        tenant.setPhoneSecondary(request.getPhoneSecondary());
        tenant.setNidNumber(request.getNidNumber());
        if (request.getNidImage() != null && !request.getNidImage().isEmpty()) {
            tenant.setNidImage(readBytes(request.getNidImage()));
            tenant.setNidImageMimeType(request.getNidImage().getContentType());
        } else {
            tenant.setNidImage(null);
            tenant.setNidImageMimeType(null);
        }
        tenant.setDateOfBirth(request.getDateOfBirth());
        tenant.setPermanentAddress(request.getPermanentAddress());
        tenant.setCurrentAddress(request.getCurrentAddress() == null || request.getCurrentAddress().isBlank()
            ? request.getPermanentAddress() : request.getCurrentAddress());
        tenant.setEmergencyContactName(request.getEmergencyContactName());
        tenant.setEmergencyContactPhone(request.getEmergencyContactPhone());
        tenant.setEmergencyContactRelation(request.getEmergencyContactRelation());
        tenant.setLeaseStartDate(request.getLeaseStartDate());
        tenant.setLeaseEndDate(request.getLeaseEndDate());
        tenant.setMonthlyRentBdt(request.getMonthlyRentBdt());
        tenant.setSecurityDepositBdt(request.getSecurityDepositBdt());
        tenant.setSecurityDepositStatus(SecurityDepositStatus.HELD);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setNotes(request.getNotes());
        tenant.setPropertyUnitId(unit.getId());
        tenant.setCreatedBy(actorUserId);

        Tenant saved = tenantRepository.save(tenant);
        unit.setOccupancyStatus(OccupancyStatus.OCCUPIED);
        unitRepository.save(unit);

        TenantUnitHistory history = new TenantUnitHistory();
        history.setTenantId(saved.getId());
        history.setPropertyUnitId(unit.getId());
        history.setStartDate(saved.getLeaseStartDate());
        historyRepository.save(history);

        auditService.log(actorUserId, "TENANT_CREATED", "TENANT", String.valueOf(saved.getId()), null, toResponse(saved));
        return toResponse(saved);
    }

    @Transactional
    public TenantResponse update(Long tenantId, UpdateTenantRequest request, Long actorUserId, UserRole role) {
        if (request.leaseEndDate() != null && !request.leaseEndDate().isAfter(request.leaseStartDate())) {
            throw new AppException("INVALID_LEASE_DATE_RANGE", "Lease end date must be later than lease start date", "leaseEndDate");
        }

        Tenant tenant = getTenant(tenantId, actorUserId, role);
        TenantResponse oldState = toResponse(tenant);

        Long oldUnitId = tenant.getPropertyUnitId();
        Long newUnitId = request.propertyUnitId();
        if (!oldUnitId.equals(newUnitId)) {
            PropertyUnit newUnit = getUnit(newUnitId);
            access.requireUnit(newUnitId, actorUserId, role);
            Long oldOwnerId = access.requireUnit(oldUnitId, actorUserId, role).getOwnerId();
            Long newOwnerId = access.requireUnit(newUnitId, actorUserId, role).getOwnerId();
            if (!oldOwnerId.equals(newOwnerId)) {
                throw new AppException("FORBIDDEN", "Tenant cannot move between owners", "propertyUnitId");
            }
            assertUnitCanAcceptActiveTenant(newUnit.getId(), newUnit.getOccupancyStatus());

            PropertyUnit oldUnit = getUnit(oldUnitId);
            oldUnit.setOccupancyStatus(OccupancyStatus.VACANT);
            unitRepository.save(oldUnit);

            newUnit.setOccupancyStatus(OccupancyStatus.OCCUPIED);
            unitRepository.save(newUnit);

            historyRepository.findTopByTenantIdAndEndDateIsNullOrderByStartDateDesc(tenantId).ifPresent(history -> {
                history.setEndDate(LocalDate.now());
                historyRepository.save(history);
            });

            TenantUnitHistory newHistory = new TenantUnitHistory();
            newHistory.setTenantId(tenantId);
            newHistory.setPropertyUnitId(newUnitId);
            newHistory.setStartDate(LocalDate.now());
            historyRepository.save(newHistory);
        }

        tenant.setFullName(request.fullName());
        tenant.setPhonePrimary(request.phonePrimary());
        tenant.setPhoneSecondary(request.phoneSecondary());
        tenant.setNidNumber(request.nidNumber());
        tenant.setDateOfBirth(request.dateOfBirth());
        tenant.setPermanentAddress(request.permanentAddress());
        tenant.setCurrentAddress(request.currentAddress() == null || request.currentAddress().isBlank()
            ? request.permanentAddress() : request.currentAddress());
        tenant.setEmergencyContactName(request.emergencyContactName());
        tenant.setEmergencyContactPhone(request.emergencyContactPhone());
        tenant.setEmergencyContactRelation(request.emergencyContactRelation());
        tenant.setLeaseStartDate(request.leaseStartDate());
        tenant.setLeaseEndDate(request.leaseEndDate());
        tenant.setMonthlyRentBdt(request.monthlyRentBdt());
        tenant.setSecurityDepositBdt(request.securityDepositBdt());
        tenant.setStatus(request.status());
        tenant.setNotes(request.notes());
        tenant.setPropertyUnitId(request.propertyUnitId());

        Tenant saved = tenantRepository.save(tenant);
        auditService.log(actorUserId, "TENANT_UPDATED", "TENANT", String.valueOf(saved.getId()), oldState, toResponse(saved));
        return toResponse(saved);
    }

    @Transactional
    public void deactivate(Long tenantId, Long actorUserId, UserRole role) {
        Tenant tenant = getTenant(tenantId, actorUserId, role);
        TenantResponse oldState = toResponse(tenant);
        tenant.setStatus(TenantStatus.INACTIVE);
        tenantRepository.save(tenant);

        PropertyUnit unit = getUnit(tenant.getPropertyUnitId());
        unit.setOccupancyStatus(OccupancyStatus.VACANT);
        unitRepository.save(unit);

        historyRepository.findTopByTenantIdAndEndDateIsNullOrderByStartDateDesc(tenantId).ifPresent(history -> {
            history.setEndDate(LocalDate.now());
            historyRepository.save(history);
        });

        auditService.log(actorUserId, "TENANT_DEACTIVATED", "TENANT", String.valueOf(tenant.getId()), oldState, toResponse(tenant));
    }

    @Transactional(readOnly = true)
    public List<TenantHistoryResponse> history(Long tenantId, Long actorUserId, UserRole role) {
        requireOwnerForHistoricalAggregate(role);
        getTenant(tenantId, actorUserId, role);
        return historyRepository.findByTenantIdOrderByStartDateDesc(tenantId).stream()
            .map(h -> new TenantHistoryResponse(h.getId(), h.getTenantId(), h.getPropertyUnitId(), h.getStartDate(), h.getEndDate()))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<AuditTimelineItemResponse> activity(Long tenantId, Long actorUserId, UserRole role) {
        requireOwnerForHistoricalAggregate(role);
        getTenant(tenantId, actorUserId, role);
        return auditLogRepository.findTop100ByEntityTypeAndEntityIdOrderByCreatedAtDesc("TENANT", String.valueOf(tenantId)).stream()
            .map(this::toTimelineResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public TenantLedgerResponse ledger(Long tenantId, Long actorUserId, UserRole role) {
        requireOwnerForHistoricalAggregate(role);
        getTenant(tenantId, actorUserId, role);

        List<Invoice> invoices = invoiceRepository.findByTenantIdOrderByBillingPeriodStartDescIdDesc(tenantId);
        List<Long> invoiceIds = invoices.stream().map(Invoice::getId).toList();
        List<InvoicePayment> payments = invoiceIds.isEmpty()
            ? List.of()
            : invoicePaymentRepository.findByInvoiceIdInOrderByPaymentDateDescIdDesc(invoiceIds);
        var deposits = securityDepositTransactionRepository.findByTenantIdOrderByTransactionDateDescIdDesc(tenantId);

        BigDecimal totalInvoiced = invoices.stream()
            .map(Invoice::getTotalDueBdt)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal outstanding = invoices.stream()
            .map(Invoice::getBalanceDueBdt)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalPaid = payments.stream()
            .map(InvoicePayment::getAmountBdt)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalDepositNet = deposits.stream()
            .map(txn -> {
                if ("DEDUCTION".equals(txn.getTxnType()) || "REFUND".equals(txn.getTxnType())) {
                    return txn.getAmountBdt().negate();
                }
                return txn.getAmountBdt();
            })
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new TenantLedgerResponse(
            tenantId,
            totalInvoiced,
            outstanding,
            totalPaid,
            totalDepositNet,
            invoices.stream()
                .map(inv -> new TenantLedgerResponse.InvoiceLedgerItem(
                    inv.getId(),
                    inv.getBillingPeriodStart(),
                    inv.getBillingPeriodEnd(),
                    inv.getDueDate(),
                    inv.getStatus().name(),
                    inv.getTotalDueBdt(),
                    inv.getBalanceDueBdt()))
                .toList(),
            payments.stream()
                .map(pay -> new TenantLedgerResponse.PaymentLedgerItem(
                    pay.getId(),
                    pay.getInvoiceId(),
                    pay.getPaymentDate(),
                    pay.getAmountBdt(),
                    pay.getPaymentMethod(),
                    pay.getNotes()))
                .toList(),
            deposits.stream()
                .map(txn -> new TenantLedgerResponse.DepositLedgerItem(
                    txn.getId(),
                    txn.getTransactionDate(),
                    txn.getTxnType(),
                    txn.getAmountBdt(),
                    txn.getReason()))
                .toList()
        );
    }

    @Transactional(readOnly = true)
    public byte[] getNidImage(Long tenantId, Long actorUserId, UserRole role) {
        Tenant tenant = getTenant(tenantId, actorUserId, role);
        access.requireIdentityDocument(tenant, actorUserId, role);
        if (tenant.getNidImage() == null || tenant.getNidImage().length == 0) {
            throw new AppException("NID_IMAGE_NOT_FOUND", "Tenant does not have an uploaded NID image", "nidImage");
        }
        return tenant.getNidImage();
    }

    @Transactional(readOnly = true)
    public String getNidImageMimeType(Long tenantId, Long actorUserId, UserRole role) {
        Tenant tenant = getTenant(tenantId, actorUserId, role);
        access.requireIdentityDocument(tenant, actorUserId, role);
        if (tenant.getNidImage() == null || tenant.getNidImage().length == 0 || tenant.getNidImageMimeType() == null) {
            throw new AppException("NID_IMAGE_NOT_FOUND", "Tenant does not have an uploaded NID image", "nidImage");
        }
        return tenant.getNidImageMimeType();
    }

    @Transactional
    public void updateNidImage(Long tenantId, MultipartFile file, Long actorUserId, UserRole role) {
        validateNidFile(file);
        Tenant tenant = getTenant(tenantId, actorUserId, role);
        access.requireIdentityDocument(tenant, actorUserId, role);
        tenant.setNidImage(readBytes(file));
        tenant.setNidImageMimeType(file.getContentType());
        tenantRepository.save(tenant);
        auditService.log(actorUserId, "TENANT_NID_IMAGE_UPDATED", "TENANT", String.valueOf(tenantId), null, null);
    }

    private Tenant getTenant(Long tenantId, Long actorUserId, UserRole role) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new AppException("TENANT_NOT_FOUND", "Tenant not found", "tenantId"));
        access.requireTenant(tenant, actorUserId, role);
        return tenant;
    }

    private void requireOwnerForHistoricalAggregate(UserRole role) {
        if (role != UserRole.OWNER) {
            throw new AppException("FORBIDDEN", "Historical tenant records require owner access", "tenantId");
        }
    }

    private PropertyUnit getUnit(Long unitId) {
        return unitRepository.findById(unitId)
            .orElseThrow(() -> new AppException("UNIT_NOT_FOUND", "Unit not found", "propertyUnitId"));
    }

    private void validateTenantBusinessRules(LocalDate leaseStartDate, LocalDate leaseEndDate, MultipartFile nidImage) {
        if (leaseEndDate != null && !leaseEndDate.isAfter(leaseStartDate)) {
            throw new AppException("INVALID_LEASE_DATE_RANGE", "Lease end date must be later than lease start date", "leaseEndDate");
        }
        if (nidImage != null && !nidImage.isEmpty()) {
            validateNidFile(nidImage);
        }
    }

    private void validateNidFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AppException("NID_IMAGE_REQUIRED", "NID image is required", "nidImage");
        }
        if (file.getSize() > (5L * 1024 * 1024)) {
            throw new AppException("FILE_TOO_LARGE", "NID image must be at most 5MB", "nidImage");
        }
        String mime = file.getContentType();
        if (!"image/jpeg".equalsIgnoreCase(mime) && !"image/png".equalsIgnoreCase(mime)) {
            throw new AppException("INVALID_FILE_TYPE", "Only JPEG or PNG is allowed for NID image", "nidImage");
        }
    }

    private void assertUnitCanAcceptActiveTenant(Long unitId, OccupancyStatus status) {
        if (status == OccupancyStatus.OCCUPIED || tenantRepository.existsByPropertyUnitIdAndStatus(unitId, TenantStatus.ACTIVE)) {
            throw new AppException("UNIT_ALREADY_OCCUPIED", "Unit already has an active tenant", "propertyUnitId");
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new AppException("FILE_READ_ERROR", "Failed to read uploaded file", "nidImage");
        }
    }

    private TenantResponse toResponse(Tenant tenant) {
        return new TenantResponse(
            tenant.getId(),
            tenant.getFullName(),
            tenant.getPhonePrimary(),
            tenant.getPhoneSecondary(),
            maskNid(tenant.getNidNumber()),
            tenant.getDateOfBirth(),
            tenant.getPermanentAddress(),
            tenant.getCurrentAddress(),
            tenant.getEmergencyContactName(),
            tenant.getEmergencyContactPhone(),
            tenant.getEmergencyContactRelation(),
            tenant.getLeaseStartDate(),
            tenant.getLeaseEndDate(),
            tenant.getMonthlyRentBdt(),
            tenant.getSecurityDepositBdt(),
            tenant.getSecurityDepositStatus(),
            tenant.getStatus(),
            tenant.getNidImage() != null && tenant.getNidImage().length > 0,
            tenant.getNotes(),
            tenant.getPropertyUnitId()
        );
    }

    private TenantDetailResponse toDetailResponse(Tenant tenant, boolean includeIdentity) {
        return new TenantDetailResponse(
            tenant.getId(),
            tenant.getFullName(),
            tenant.getPhonePrimary(),
            tenant.getPhoneSecondary(),
            includeIdentity ? tenant.getNidNumber() : null,
            tenant.getDateOfBirth(),
            tenant.getPermanentAddress(),
            tenant.getCurrentAddress(),
            tenant.getEmergencyContactName(),
            tenant.getEmergencyContactPhone(),
            tenant.getEmergencyContactRelation(),
            tenant.getLeaseStartDate(),
            tenant.getLeaseEndDate(),
            tenant.getMonthlyRentBdt(),
            tenant.getSecurityDepositBdt(),
            tenant.getSecurityDepositStatus(),
            tenant.getStatus(),
            tenant.getNidImage() != null && tenant.getNidImage().length > 0,
            tenant.getNotes(),
            tenant.getPropertyUnitId()
        );
    }

    private String maskNid(String nid) {
        if (nid == null || nid.length() < 4) {
            return "*************";
        }
        return "*************" + nid.substring(nid.length() - 4);
    }

    private AuditTimelineItemResponse toTimelineResponse(AuditLog log) {
        return new AuditTimelineItemResponse(
            log.getId(),
            log.getActorUserId(),
            log.getAction(),
            log.getEntityType(),
            log.getEntityId(),
            log.getCreatedAt()
        );
    }
}
