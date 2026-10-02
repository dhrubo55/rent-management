package com.renteasebd.deposit;

import com.renteasebd.common.AppException;
import com.renteasebd.common.AuditService;
import com.renteasebd.deposit.dto.CreateDepositTransactionRequest;
import com.renteasebd.deposit.dto.DepositBalanceResponse;
import com.renteasebd.deposit.dto.DepositLedgerResponse;
import com.renteasebd.deposit.dto.DepositTransactionResponse;
import com.renteasebd.deposit.dto.MoveOutSettlementRequest;
import com.renteasebd.deposit.dto.MoveOutSettlementResponse;
import com.renteasebd.domain.deposit.SecurityDepositTransaction;
import com.renteasebd.domain.property.Property;
import com.renteasebd.domain.property.PropertyUnit;
import com.renteasebd.domain.tenant.SecurityDepositStatus;
import com.renteasebd.domain.tenant.Tenant;
import com.renteasebd.domain.tenant.TenantStatus;
import com.renteasebd.domain.user.UserRole;
import com.renteasebd.notification.NotificationService;
import com.renteasebd.repository.PropertyUnitRepository;
import com.renteasebd.repository.SecurityDepositTransactionRepository;
import com.renteasebd.repository.TenantRepository;
import com.renteasebd.tenant.TenantService;
import com.renteasebd.security.ResourceAccessService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DepositService {

    private static final String TXN_COLLECTED = "COLLECTED";
    private static final String TXN_DEDUCTION = "DEDUCTION";
    private static final String TXN_REFUND = "REFUND";
    private static final Set<String> SUPPORTED_TYPES = Set.of(TXN_COLLECTED, TXN_DEDUCTION, TXN_REFUND);

    private final SecurityDepositTransactionRepository securityDepositTransactionRepository;
    private final TenantRepository tenantRepository;
    private final PropertyUnitRepository propertyUnitRepository;
    private final TenantService tenantService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final ResourceAccessService access;

    public DepositService(
        SecurityDepositTransactionRepository securityDepositTransactionRepository,
        TenantRepository tenantRepository,
        PropertyUnitRepository propertyUnitRepository,
        TenantService tenantService,
        AuditService auditService,
        NotificationService notificationService,
        ResourceAccessService access
    ) {
        this.securityDepositTransactionRepository = securityDepositTransactionRepository;
        this.tenantRepository = tenantRepository;
        this.propertyUnitRepository = propertyUnitRepository;
        this.tenantService = tenantService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public List<DepositTransactionResponse> list(Long actorUserId, UserRole role, Long tenantId, Long propertyId) {
        List<Property> accessibleProperties = resolveAccessibleProperties(actorUserId, role);
        if (accessibleProperties.isEmpty()) {
            return List.of();
        }

        if (propertyId != null) {
            getAccessibleProperty(propertyId, actorUserId, role);
            accessibleProperties = accessibleProperties.stream().filter(property -> property.getId().equals(propertyId)).toList();
        }

        List<Long> propertyIds = accessibleProperties.stream().map(Property::getId).toList();
        if (propertyIds.isEmpty()) {
            return List.of();
        }

        List<PropertyUnit> units = propertyUnitRepository.findByPropertyIdIn(propertyIds);
        if (units.isEmpty()) {
            return List.of();
        }
        Map<Long, PropertyUnit> unitsById = units.stream().collect(Collectors.toMap(PropertyUnit::getId, unit -> unit));

        List<Long> unitIds = units.stream().map(PropertyUnit::getId).toList();
        List<Tenant> tenants = tenantRepository.findByPropertyUnitIdIn(unitIds);
        if (tenants.isEmpty()) {
            return List.of();
        }

        if (tenantId != null) {
            TenantContext context = getAccessibleTenantContext(tenantId, actorUserId, role);
            tenants = tenants.stream().filter(tenant -> tenant.getId().equals(context.tenant().getId())).toList();
        }

        List<Long> tenantIds = tenants.stream().map(Tenant::getId).toList();
        if (tenantIds.isEmpty()) {
            return List.of();
        }

        Map<Long, Tenant> tenantsById = tenants.stream().collect(Collectors.toMap(Tenant::getId, tenant -> tenant));
        Map<Long, Property> propertiesById = accessibleProperties.stream().collect(Collectors.toMap(Property::getId, property -> property));

        return securityDepositTransactionRepository.findByTenantIdInOrderByTransactionDateDescIdDesc(tenantIds).stream()
            .map(txn -> toResponse(txn, tenantsById.get(txn.getTenantId()), unitsById, propertiesById))
            .toList();
    }

    @Transactional(readOnly = true)
    public DepositBalanceResponse getBalance(Long tenantId, Long actorUserId, UserRole role) {
        TenantContext context = getAccessibleTenantContext(tenantId, actorUserId, role);
        List<SecurityDepositTransaction> txns = securityDepositTransactionRepository
            .findByTenantIdOrderByTransactionDateAscIdAsc(tenantId);
        BalanceSummary summary = summarize(context.tenant(), txns);
        return toBalanceResponse(context.tenant(), summary);
    }

    @Transactional(readOnly = true)
    public DepositLedgerResponse getLedger(Long tenantId, Long actorUserId, UserRole role) {
        TenantContext context = getAccessibleTenantContext(tenantId, actorUserId, role);
        List<SecurityDepositTransaction> txns = securityDepositTransactionRepository
            .findByTenantIdOrderByTransactionDateDescIdDesc(tenantId);

        List<DepositTransactionResponse> items = txns.stream()
            .map(txn -> toResponse(txn, context.tenant(), context.unit(), context.property()))
            .toList();

        BalanceSummary summary = summarize(
            context.tenant(),
            securityDepositTransactionRepository.findByTenantIdOrderByTransactionDateAscIdAsc(tenantId)
        );
        return new DepositLedgerResponse(toBalanceResponse(context.tenant(), summary), items);
    }

    @Transactional
    public DepositTransactionResponse createTransaction(
        Long tenantId,
        CreateDepositTransactionRequest request,
        Long actorUserId,
        UserRole role
    ) {
        TenantContext context = getAccessibleTenantContext(tenantId, actorUserId, role);
        String txnType = normalizeType(request.txnType());
        BigDecimal amount = normalizePositiveMoney(request.amountBdt());

        List<SecurityDepositTransaction> existing = securityDepositTransactionRepository
            .findByTenantIdOrderByTransactionDateAscIdAsc(tenantId);
        BalanceSummary before = summarize(context.tenant(), existing);

        if (isOutflow(txnType) && amount.compareTo(before.currentBalance()) > 0) {
            throw new AppException(
                "DEPOSIT_BOUNDS_EXCEEDED",
                "Refund and deduction cannot exceed current security deposit balance",
                "amountBdt"
            );
        }

        SecurityDepositTransaction txn = new SecurityDepositTransaction();
        txn.setTenantId(context.tenant().getId());
        txn.setTxnType(txnType);
        txn.setAmountBdt(amount);
        txn.setReason(cleanReason(request.reason()));
        txn.setTransactionDate(request.transactionDate());
        txn.setCreatedBy(actorUserId);
        SecurityDepositTransaction saved = securityDepositTransactionRepository.save(txn);

        BalanceSummary after = summarize(context.tenant(), append(existing, saved));
        updateTenantDepositStatus(context.tenant(), after);

        DepositTransactionResponse response = toResponse(saved, context.tenant(), context.unit(), context.property());
        auditService.log(
            actorUserId,
            "DEPOSIT_TXN_CREATED",
            "SECURITY_DEPOSIT_TRANSACTION",
            String.valueOf(saved.getId()),
            toBalanceResponse(context.tenant(), before),
            toBalanceResponse(context.tenant(), after)
        );
        return response;
    }

    @Transactional
    public MoveOutSettlementResponse settleMoveOut(
        Long tenantId,
        MoveOutSettlementRequest request,
        Long actorUserId,
        UserRole role
    ) {
        TenantContext context = getAccessibleTenantContext(tenantId, actorUserId, role);
        BigDecimal deduction = normalizeNonNegativeMoney(request.deductionBdt());
        BigDecimal refund = normalizeNonNegativeMoney(request.refundBdt());

        if (deduction.signum() == 0 && refund.signum() == 0) {
            throw new AppException("INVALID_SETTLEMENT", "Provide deduction or refund amount", "refundBdt");
        }

        List<SecurityDepositTransaction> existing = securityDepositTransactionRepository
            .findByTenantIdOrderByTransactionDateAscIdAsc(tenantId);
        BalanceSummary before = summarize(context.tenant(), existing);

        BigDecimal totalOutflow = deduction.add(refund);
        if (totalOutflow.compareTo(before.currentBalance()) > 0) {
            throw new AppException(
                "DEPOSIT_BOUNDS_EXCEEDED",
                "Move-out settlement exceeds available security deposit balance",
                "refundBdt"
            );
        }

        Long deductionTxnId = null;
        Long refundTxnId = null;
        String reason = cleanReason(request.reason());

        if (deduction.signum() > 0) {
            SecurityDepositTransaction deductionTxn = new SecurityDepositTransaction();
            deductionTxn.setTenantId(context.tenant().getId());
            deductionTxn.setTxnType(TXN_DEDUCTION);
            deductionTxn.setAmountBdt(deduction);
            deductionTxn.setReason(reason == null ? "Move-out deduction" : reason);
            deductionTxn.setTransactionDate(request.moveOutDate());
            deductionTxn.setCreatedBy(actorUserId);
            deductionTxnId = securityDepositTransactionRepository.save(deductionTxn).getId();
        }

        if (refund.signum() > 0) {
            SecurityDepositTransaction refundTxn = new SecurityDepositTransaction();
            refundTxn.setTenantId(context.tenant().getId());
            refundTxn.setTxnType(TXN_REFUND);
            refundTxn.setAmountBdt(refund);
            refundTxn.setReason(reason == null ? "Move-out refund" : reason);
            refundTxn.setTransactionDate(request.moveOutDate());
            refundTxn.setCreatedBy(actorUserId);
            refundTxnId = securityDepositTransactionRepository.save(refundTxn).getId();
        }

        if (context.tenant().getStatus() == TenantStatus.ACTIVE) {
            tenantService.deactivate(context.tenant().getId(), actorUserId, role);
        }

        Tenant refreshedTenant = tenantRepository.findById(context.tenant().getId())
            .orElseThrow(() -> new AppException("TENANT_NOT_FOUND", "Tenant not found", "tenantId"));

        BalanceSummary after = summarize(
            refreshedTenant,
            securityDepositTransactionRepository.findByTenantIdOrderByTransactionDateAscIdAsc(tenantId)
        );
        updateTenantDepositStatus(refreshedTenant, after);

        MoveOutSettlementResponse response = new MoveOutSettlementResponse(
            refreshedTenant.getId(),
            deductionTxnId,
            refundTxnId,
            after.currentBalance(),
            refreshedTenant.getStatus(),
            refreshedTenant.getSecurityDepositStatus()
        );
        notificationService.onMoveOutSettlement(refreshedTenant, context.property(), deduction, refund, request.moveOutDate());
        auditService.log(
            actorUserId,
            "DEPOSIT_MOVE_OUT_SETTLED",
            "TENANT",
            String.valueOf(refreshedTenant.getId()),
            toBalanceResponse(refreshedTenant, before),
            response
        );
        return response;
    }

    private TenantContext getAccessibleTenantContext(Long tenantId, Long actorUserId, UserRole role) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new AppException("TENANT_NOT_FOUND", "Tenant not found", "tenantId"));

        PropertyUnit unit = propertyUnitRepository.findById(tenant.getPropertyUnitId())
            .orElseThrow(() -> new AppException("UNIT_NOT_FOUND", "Unit not found", "propertyUnitId"));

        Property property = getAccessibleProperty(unit.getPropertyId(), actorUserId, role);
        return new TenantContext(tenant, unit, property);
    }

    private List<Property> resolveAccessibleProperties(Long actorUserId, UserRole role) {
        return access.accessibleProperties(actorUserId, role);
    }

    private Property getAccessibleProperty(Long propertyId, Long actorUserId, UserRole role) {
        return access.requireProperty(propertyId, actorUserId, role);
    }

    private BalanceSummary summarize(Tenant tenant, List<SecurityDepositTransaction> txns) {
        BigDecimal opening = normalizeNonNegativeMoney(tenant.getSecurityDepositBdt());
        BigDecimal collected = BigDecimal.ZERO;
        BigDecimal deducted = BigDecimal.ZERO;
        BigDecimal refunded = BigDecimal.ZERO;

        for (SecurityDepositTransaction txn : txns) {
            String type = normalizeType(txn.getTxnType());
            BigDecimal amount = normalizeNonNegativeMoney(txn.getAmountBdt());
            if (TXN_COLLECTED.equals(type)) {
                collected = collected.add(amount);
            } else if (TXN_DEDUCTION.equals(type)) {
                deducted = deducted.add(amount);
            } else if (TXN_REFUND.equals(type)) {
                refunded = refunded.add(amount);
            }
        }

        BigDecimal current = opening.add(collected).subtract(deducted).subtract(refunded);
        if (current.signum() < 0) {
            current = BigDecimal.ZERO;
        }

        return new BalanceSummary(
            scaleMoney(opening),
            scaleMoney(collected),
            scaleMoney(deducted),
            scaleMoney(refunded),
            scaleMoney(current)
        );
    }

    private void updateTenantDepositStatus(Tenant tenant, BalanceSummary summary) {
        SecurityDepositStatus newStatus;
        if (summary.currentBalance().signum() == 0) {
            newStatus = SecurityDepositStatus.REFUNDED;
        } else if (summary.deductedBdt().add(summary.refundedBdt()).signum() > 0) {
            newStatus = SecurityDepositStatus.PARTIALLY_REFUNDED;
        } else {
            newStatus = SecurityDepositStatus.HELD;
        }

        if (tenant.getSecurityDepositStatus() != newStatus) {
            tenant.setSecurityDepositStatus(newStatus);
            tenantRepository.save(tenant);
        }
    }

    private DepositTransactionResponse toResponse(
        SecurityDepositTransaction txn,
        Tenant tenant,
        PropertyUnit unit,
        Property property
    ) {
        return new DepositTransactionResponse(
            txn.getId(),
            tenant.getId(),
            tenant.getFullName(),
            unit.getId(),
            unit.getUnitIdentifier(),
            property.getId(),
            property.getPropertyName(),
            normalizeType(txn.getTxnType()),
            scaleMoney(txn.getAmountBdt()),
            txn.getReason(),
            txn.getTransactionDate(),
            TXN_REFUND.equalsIgnoreCase(txn.getTxnType())
        );
    }

    private DepositTransactionResponse toResponse(
        SecurityDepositTransaction txn,
        Tenant tenant,
        Map<Long, PropertyUnit> unitsById,
        Map<Long, Property> propertiesById
    ) {
        if (tenant == null) {
            throw new AppException("TENANT_NOT_FOUND", "Tenant not found", "tenantId");
        }

        PropertyUnit unit = unitsById.get(tenant.getPropertyUnitId());
        if (unit == null) {
            throw new AppException("UNIT_NOT_FOUND", "Unit not found", "propertyUnitId");
        }

        Property property = propertiesById.get(unit.getPropertyId());
        if (property == null) {
            throw new AppException("PROPERTY_NOT_FOUND", "Property not found", "propertyId");
        }

        return toResponse(txn, tenant, unit, property);
    }

    private DepositBalanceResponse toBalanceResponse(Tenant tenant, BalanceSummary summary) {
        return new DepositBalanceResponse(
            tenant.getId(),
            summary.openingDepositBdt(),
            summary.collectedBdt(),
            summary.deductedBdt(),
            summary.refundedBdt(),
            summary.currentBalance(),
            tenant.getSecurityDepositStatus()
        );
    }

    private BigDecimal normalizePositiveMoney(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new AppException("VALIDATION_ERROR", "Amount must be greater than 0", "amountBdt");
        }
        return scaleMoney(amount);
    }

    private BigDecimal normalizeNonNegativeMoney(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            throw new AppException("VALIDATION_ERROR", "Amount must be zero or greater", "amountBdt");
        }
        return scaleMoney(amount);
    }

    private BigDecimal scaleMoney(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
    }

    private String normalizeType(String txnType) {
        String normalized = txnType == null ? "" : txnType.trim().toUpperCase();
        if (!SUPPORTED_TYPES.contains(normalized)) {
            throw new AppException("INVALID_DEPOSIT_TXN_TYPE", "txnType must be COLLECTED, DEDUCTION, or REFUND", "txnType");
        }
        return normalized;
    }

    private boolean isOutflow(String txnType) {
        return TXN_DEDUCTION.equals(txnType) || TXN_REFUND.equals(txnType);
    }

    private String cleanReason(String reason) {
        if (reason == null) {
            return null;
        }
        String value = reason.trim();
        return value.isEmpty() ? null : value;
    }

    private List<SecurityDepositTransaction> append(List<SecurityDepositTransaction> existing, SecurityDepositTransaction item) {
        return java.util.stream.Stream.concat(existing.stream(), java.util.stream.Stream.of(item)).toList();
    }

    private record TenantContext(Tenant tenant, PropertyUnit unit, Property property) {
    }

    private record BalanceSummary(
        BigDecimal openingDepositBdt,
        BigDecimal collectedBdt,
        BigDecimal deductedBdt,
        BigDecimal refundedBdt,
        BigDecimal currentBalance
    ) {
    }
}
