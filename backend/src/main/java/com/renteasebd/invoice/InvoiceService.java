package com.renteasebd.invoice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.renteasebd.common.AppException;
import com.renteasebd.common.AuditService;
import com.renteasebd.domain.invoice.Invoice;
import com.renteasebd.domain.invoice.InvoicePayment;
import com.renteasebd.domain.invoice.InvoiceStatus;
import com.renteasebd.domain.property.Property;
import com.renteasebd.domain.property.PropertySettings;
import com.renteasebd.domain.property.PropertyUnit;
import com.renteasebd.domain.tenant.Tenant;
import com.renteasebd.domain.tenant.TenantStatus;
import com.renteasebd.domain.user.UserRole;
import com.renteasebd.notification.NotificationService;
import com.renteasebd.invoice.dto.ApplyFifoPaymentRequest;
import com.renteasebd.invoice.dto.ApplyFifoPaymentResponse;
import com.renteasebd.invoice.dto.BulkGenerateInvoiceItem;
import com.renteasebd.invoice.dto.BulkGenerateInvoicesRequest;
import com.renteasebd.invoice.dto.BulkGenerateInvoicesResponse;
import com.renteasebd.invoice.dto.CancelInvoiceRequest;
import com.renteasebd.invoice.dto.CreateInvoiceRequest;
import com.renteasebd.invoice.dto.GenerateInvoiceResponse;
import com.renteasebd.invoice.dto.InvoiceResponse;
import com.renteasebd.invoice.dto.PaymentAllocationItem;
import com.renteasebd.invoice.dto.RecordInvoicePaymentRequest;
import com.renteasebd.invoice.dto.RecordInvoicePaymentResponse;
import com.renteasebd.invoice.dto.UtilityChargeInput;
import com.renteasebd.invoice.dto.VoidInvoiceRequest;
import com.renteasebd.repository.InvoicePaymentRepository;
import com.renteasebd.repository.InvoiceRepository;
import com.renteasebd.repository.PropertyRepository;
import com.renteasebd.repository.PropertySettingsRepository;
import com.renteasebd.repository.PropertyUnitRepository;
import com.renteasebd.repository.TenantRepository;
import com.renteasebd.security.ResourceAccessService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvoiceService {

    private static final Set<InvoiceStatus> DUPLICATE_EXEMPT_STATUSES = EnumSet.of(InvoiceStatus.CANCELLED, InvoiceStatus.VOID);
    private static final Set<InvoiceStatus> PAYABLE_STATUSES = EnumSet.of(
        InvoiceStatus.SENT,
        InvoiceStatus.PARTIALLY_PAID,
        InvoiceStatus.OVERDUE
    );
    private static final ZoneId DHAKA_ZONE = ZoneId.of("Asia/Dhaka");
    private static final DateTimeFormatter SMS_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd MMM uuuu");

    private final InvoiceRepository invoiceRepository;
    private final InvoicePaymentRepository invoicePaymentRepository;
    private final TenantRepository tenantRepository;
    private final PropertyUnitRepository propertyUnitRepository;
    private final PropertyRepository propertyRepository;
    private final PropertySettingsRepository propertySettingsRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;
    private final InvoiceDownloadTokenService invoiceDownloadTokenService;
    private final ResourceAccessService access;

    public InvoiceService(
        InvoiceRepository invoiceRepository,
        InvoicePaymentRepository invoicePaymentRepository,
        TenantRepository tenantRepository,
        PropertyUnitRepository propertyUnitRepository,
        PropertyRepository propertyRepository,
        PropertySettingsRepository propertySettingsRepository,
        AuditService auditService,
        ObjectMapper objectMapper,
        NotificationService notificationService,
        InvoiceDownloadTokenService invoiceDownloadTokenService,
        ResourceAccessService access
    ) {
        this.invoiceRepository = invoiceRepository;
        this.invoicePaymentRepository = invoicePaymentRepository;
        this.tenantRepository = tenantRepository;
        this.propertyUnitRepository = propertyUnitRepository;
        this.propertyRepository = propertyRepository;
        this.propertySettingsRepository = propertySettingsRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
        this.invoiceDownloadTokenService = invoiceDownloadTokenService;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> list(
        Long actorUserId,
        UserRole role,
        Long tenantId,
        Long propertyId,
        InvoiceStatus status,
        String billingMonth
    ) {
        LocalDate billingStartFilter = parseBillingMonth(billingMonth);
        List<Property> accessibleProperties = resolveAccessibleProperties(actorUserId, role, propertyId);
        if (accessibleProperties.isEmpty()) {
            return List.of();
        }

        List<Long> propertyIds = accessibleProperties.stream().map(Property::getId).toList();
        List<PropertyUnit> units = propertyUnitRepository.findByPropertyIdIn(propertyIds);
        if (units.isEmpty()) {
            return List.of();
        }
        List<Long> unitIds = units.stream().map(PropertyUnit::getId).toList();

        List<Invoice> invoices = invoiceRepository.findByPropertyUnitIdInOrderByBillingPeriodStartDescIdDesc(unitIds);
        if (invoices.isEmpty()) {
            return List.of();
        }

        List<Invoice> filteredInvoices = invoices.stream()
            .filter(invoice -> tenantId == null || invoice.getTenantId().equals(tenantId))
            .filter(invoice -> status == null || invoice.getStatus() == status)
            .filter(invoice -> billingStartFilter == null || invoice.getBillingPeriodStart().equals(billingStartFilter))
            .sorted(Comparator.comparing(Invoice::getId).reversed())
            .toList();

        if (filteredInvoices.isEmpty()) {
            return List.of();
        }

        return toResponses(filteredInvoices);
    }

    @Transactional(readOnly = true)
    public InvoiceResponse get(Long invoiceId, Long actorUserId, UserRole role) {
        Invoice invoice = getAccessibleInvoice(invoiceId, actorUserId, role);
        return toResponses(List.of(invoice)).getFirst();
    }

    @Transactional(readOnly = true)
    public InvoiceResponse getPublicDownloadable(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new AppException("INVOICE_NOT_FOUND", "Invoice not found", "invoiceId"));
        if (invoice.getStatus() == InvoiceStatus.DRAFT
            || invoice.getStatus() == InvoiceStatus.CANCELLED
            || invoice.getStatus() == InvoiceStatus.VOID) {
            throw new AppException("INVOICE_NOT_DOWNLOADABLE", "Invoice cannot be downloaded in current status", "status");
        }
        return toResponses(List.of(invoice)).getFirst();
    }

    @Transactional
    public GenerateInvoiceResponse generateSingle(CreateInvoiceRequest request, Long actorUserId, UserRole role) {
        Tenant tenant = tenantRepository.findById(request.tenantId())
            .orElseThrow(() -> new AppException("TENANT_NOT_FOUND", "Tenant not found", "tenantId"));

        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new AppException("TENANT_NOT_ACTIVE", "Invoice can only be generated for active tenant", "tenantId");
        }

        PropertyUnit unit = getUnitOrThrow(tenant.getPropertyUnitId());
        Property property = getAccessibleProperty(unit.getPropertyId(), actorUserId, role);

        InvoiceBuildResult buildResult = buildInvoice(
            tenant,
            unit,
            property,
            request.billingPeriodStart(),
            request.utilityCharges(),
            actorUserId
        );

        Invoice saved = invoiceRepository.save(buildResult.invoice());
        InvoiceResponse response = toResponses(List.of(saved)).getFirst();
        auditService.log(actorUserId, "INVOICE_GENERATED", "INVOICE", String.valueOf(saved.getId()), null, response);

        return new GenerateInvoiceResponse(response, buildResult.warnings());
    }

    @Transactional
    public BulkGenerateInvoicesResponse generateBulk(BulkGenerateInvoicesRequest request, Long actorUserId, UserRole role) {
        Property property = getAccessibleProperty(request.propertyId(), actorUserId, role);
        List<PropertyUnit> propertyUnits = propertyUnitRepository.findByPropertyId(property.getId());
        if (propertyUnits.isEmpty()) {
            return new BulkGenerateInvoicesResponse(0, 0, 0, List.of());
        }

        List<Long> unitIds = propertyUnits.stream().map(PropertyUnit::getId).toList();
        List<Tenant> activeTenants = tenantRepository.findByPropertyUnitIdInAndStatus(unitIds, TenantStatus.ACTIVE);
        if (activeTenants.isEmpty()) {
            return new BulkGenerateInvoicesResponse(0, 0, 0, List.of());
        }

        Map<Long, PropertyUnit> unitsById = propertyUnits.stream().collect(Collectors.toMap(PropertyUnit::getId, unit -> unit));
        List<BulkGenerateInvoiceItem> items = new ArrayList<>();
        int generatedCount = 0;

        for (Tenant tenant : activeTenants) {
            PropertyUnit unit = unitsById.get(tenant.getPropertyUnitId());
            if (unit == null) {
                items.add(new BulkGenerateInvoiceItem(
                    tenant.getId(),
                    tenant.getFullName(),
                    "SKIPPED",
                    null,
                    "UNIT_NOT_FOUND",
                    List.of()
                ));
                continue;
            }

            try {
                InvoiceBuildResult buildResult = buildInvoice(
                    tenant,
                    unit,
                    property,
                    request.billingPeriodStart(),
                    request.utilityCharges(),
                    actorUserId
                );

                Invoice saved = invoiceRepository.save(buildResult.invoice());
                InvoiceResponse response = toResponses(List.of(saved)).getFirst();
                auditService.log(actorUserId, "INVOICE_GENERATED", "INVOICE", String.valueOf(saved.getId()), null, response);

                generatedCount++;
                items.add(new BulkGenerateInvoiceItem(
                    tenant.getId(),
                    tenant.getFullName(),
                    "GENERATED",
                    saved.getId(),
                    null,
                    buildResult.warnings()
                ));
            } catch (AppException ex) {
                if ("DUPLICATE_INVOICE".equals(ex.getCode())) {
                    items.add(new BulkGenerateInvoiceItem(
                        tenant.getId(),
                        tenant.getFullName(),
                        "SKIPPED",
                        null,
                        "DUPLICATE_INVOICE",
                        List.of()
                    ));
                } else {
                    items.add(new BulkGenerateInvoiceItem(
                        tenant.getId(),
                        tenant.getFullName(),
                        "FAILED",
                        null,
                        ex.getCode(),
                        List.of()
                    ));
                }
            }
        }

        int requestedCount = activeTenants.size();
        int skippedCount = requestedCount - generatedCount;
        return new BulkGenerateInvoicesResponse(requestedCount, generatedCount, skippedCount, items);
    }

    @Transactional
    public InvoiceResponse send(Long invoiceId, Long actorUserId, UserRole role) {
        Invoice invoice = getAccessibleInvoice(invoiceId, actorUserId, role);
        InvoiceResponse oldState = toResponses(List.of(invoice)).getFirst();

        if (invoice.getStatus() == InvoiceStatus.CANCELLED || invoice.getStatus() == InvoiceStatus.VOID) {
            throw new AppException("INVALID_INVOICE_STATUS", "Cancelled/void invoice cannot be sent", "status");
        }
        if (invoice.getStatus() != InvoiceStatus.DRAFT && invoice.getStatus() != InvoiceStatus.SENT) {
            throw new AppException("INVALID_INVOICE_STATUS", "Only draft invoice can be sent", "status");
        }

        Tenant tenant = getTenantOrThrow(invoice.getTenantId());
        PropertyUnit unit = getUnitOrThrow(invoice.getPropertyUnitId());
        Property property = getPropertyOrThrow(unit.getPropertyId());
        String downloadUrl = invoiceDownloadTokenService.issueOneTimeDownloadUrl(invoice.getId(), actorUserId);

        invoice.setStatus(InvoiceStatus.SENT);
        invoice.setSmsText(composeSmsText(invoice, tenant, unit, property, downloadUrl));
        Invoice saved = invoiceRepository.save(invoice);

        InvoiceResponse newState = toResponses(List.of(saved)).getFirst();
        auditService.log(actorUserId, "INVOICE_SENT", "INVOICE", String.valueOf(saved.getId()), oldState, newState);
        notificationService.onInvoiceSent(saved, tenant, property);
        return newState;
    }

    @Transactional
    public InvoiceResponse cancel(Long invoiceId, CancelInvoiceRequest request, Long actorUserId, UserRole role) {
        Invoice invoice = getAccessibleInvoice(invoiceId, actorUserId, role);
        InvoiceResponse oldState = toResponses(List.of(invoice)).getFirst();

        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            return oldState;
        }
        if (invoice.getStatus() != InvoiceStatus.DRAFT && invoice.getStatus() != InvoiceStatus.SENT) {
            throw new AppException("INVALID_INVOICE_STATUS", "Only draft or sent invoice can be cancelled", "status");
        }

        invoice.setStatus(InvoiceStatus.CANCELLED);
        Invoice saved = invoiceRepository.save(invoice);
        InvoiceResponse newState = toResponses(List.of(saved)).getFirst();
        auditService.log(
            actorUserId,
            "INVOICE_CANCELLED",
            "INVOICE",
            String.valueOf(saved.getId()),
            oldState,
            Map.of("invoice", newState, "reason", request.reason().trim())
        );
        return newState;
    }

    @Transactional
    public InvoiceResponse voidInvoice(Long invoiceId, VoidInvoiceRequest request, Long actorUserId, UserRole role) {
        Invoice invoice = getAccessibleInvoice(invoiceId, actorUserId, role);
        InvoiceResponse oldState = toResponses(List.of(invoice)).getFirst();

        if (invoice.getStatus() == InvoiceStatus.VOID) {
            throw new AppException("INVALID_INVOICE_STATUS", "Invoice is already void", "status");
        }

        invoice.setStatus(InvoiceStatus.VOID);
        Invoice saved = invoiceRepository.save(invoice);
        InvoiceResponse newState = toResponses(List.of(saved)).getFirst();
        auditService.log(
            actorUserId,
            "INVOICE_VOIDED",
            "INVOICE",
            String.valueOf(saved.getId()),
            oldState,
            Map.of("invoice", newState, "reason", request.reason().trim())
        );
        return newState;
    }

    @Transactional
    public RecordInvoicePaymentResponse recordPayment(
        Long invoiceId,
        RecordInvoicePaymentRequest request,
        Long actorUserId,
        UserRole role
    ) {
        Invoice invoice = getAccessibleInvoice(invoiceId, actorUserId, role);
        InvoiceResponse oldState = toResponses(List.of(invoice)).getFirst();
        assertInvoicePayable(invoice);
        Tenant tenant = getTenantOrThrow(invoice.getTenantId());
        PropertyUnit unit = getUnitOrThrow(invoice.getPropertyUnitId());
        Property property = getPropertyOrThrow(unit.getPropertyId());

        BigDecimal amount = request.amountBdt().setScale(2, RoundingMode.HALF_UP);
        BigDecimal balance = invoice.getBalanceDueBdt().setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(balance) > 0) {
            throw new AppException("OVERPAYMENT", "Payment exceeds invoice balance", "amountBdt");
        }

        savePayment(invoice.getId(), amount, request.paymentDate(), request.paymentMethod(), request.notes(), actorUserId);
        applyPaymentToInvoice(invoice, amount, request.paymentDate());
        Invoice saved = invoiceRepository.save(invoice);
        InvoiceResponse newState = toResponses(List.of(saved)).getFirst();

        Map<String, Object> paymentAudit = new LinkedHashMap<>();
        paymentAudit.put("amountBdt", amount);
        paymentAudit.put("paymentDate", request.paymentDate());
        paymentAudit.put("paymentMethod", request.paymentMethod());
        paymentAudit.put("notes", request.notes());

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("invoice", newState);
        auditPayload.put("payment", paymentAudit);

        auditService.log(
            actorUserId,
            "INVOICE_PAYMENT_RECORDED",
            "INVOICE",
            String.valueOf(saved.getId()),
            oldState,
            auditPayload
        );
        notificationService.onPaymentReceipt(
            tenant,
            property,
            saved,
            amount,
            request.paymentDate(),
            request.paymentMethod()
        );

        return new RecordInvoicePaymentResponse(saved.getId(), amount, newState);
    }

    @Transactional
    public ApplyFifoPaymentResponse applyFifoPayment(
        ApplyFifoPaymentRequest request,
        Long actorUserId,
        UserRole role
    ) {
        Tenant tenant = getTenantOrThrow(request.tenantId());
        PropertyUnit unit = getUnitOrThrow(tenant.getPropertyUnitId());
        Property property = getAccessibleProperty(unit.getPropertyId(), actorUserId, role);

        List<Invoice> outstanding = invoiceRepository.findByTenantIdAndStatusInOrderByBillingPeriodStartAscIdAsc(
            tenant.getId(),
            PAYABLE_STATUSES
        ).stream().filter(invoice -> invoice.getBalanceDueBdt().compareTo(BigDecimal.ZERO) > 0).toList();

        if (outstanding.isEmpty()) {
            throw new AppException("INVALID_FIFO_PAYMENT", "No outstanding invoice found for tenant", "tenantId");
        }

        BigDecimal requestedAmount = request.totalAmountBdt().setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalOutstanding = outstanding.stream()
            .map(invoice -> invoice.getBalanceDueBdt().setScale(2, RoundingMode.HALF_UP))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);
        if (requestedAmount.compareTo(totalOutstanding) > 0) {
            throw new AppException("OVERPAYMENT", "Payment exceeds outstanding invoice total", "totalAmountBdt");
        }

        BigDecimal remaining = requestedAmount;
        List<PaymentAllocationItem> allocations = new ArrayList<>();
        List<Map<String, Object>> auditAllocations = new ArrayList<>();

        for (Invoice invoice : outstanding) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal currentBalance = invoice.getBalanceDueBdt().setScale(2, RoundingMode.HALF_UP);
            BigDecimal applied = remaining.min(currentBalance).setScale(2, RoundingMode.HALF_UP);

            savePayment(invoice.getId(), applied, request.paymentDate(), request.paymentMethod(), request.notes(), actorUserId);
            applyPaymentToInvoice(invoice, applied, request.paymentDate());
            Invoice saved = invoiceRepository.save(invoice);

            remaining = remaining.subtract(applied).setScale(2, RoundingMode.HALF_UP);
            allocations.add(new PaymentAllocationItem(
                saved.getId(),
                saved.getBillingPeriodStart(),
                applied,
                saved.getBalanceDueBdt(),
                saved.getStatus()
            ));
            notificationService.onPaymentReceipt(
                tenant,
                property,
                saved,
                applied,
                request.paymentDate(),
                request.paymentMethod()
            );
            auditAllocations.add(Map.of(
                "invoiceId", saved.getId(),
                "appliedAmountBdt", applied,
                "remainingBalanceBdt", saved.getBalanceDueBdt(),
                "newStatus", saved.getStatus()
            ));
        }

        BigDecimal appliedAmount = requestedAmount.subtract(remaining).setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> fifoAudit = new LinkedHashMap<>();
        fifoAudit.put("tenantId", tenant.getId());
        fifoAudit.put("requestedAmountBdt", requestedAmount);
        fifoAudit.put("appliedAmountBdt", appliedAmount);
        fifoAudit.put("remainingUnappliedBdt", remaining);
        fifoAudit.put("paymentDate", request.paymentDate());
        fifoAudit.put("paymentMethod", request.paymentMethod());
        fifoAudit.put("notes", request.notes());
        fifoAudit.put("allocations", auditAllocations);

        auditService.log(
            actorUserId,
            "FIFO_PAYMENT_APPLIED",
            "TENANT",
            String.valueOf(tenant.getId()),
            null,
            fifoAudit
        );

        return new ApplyFifoPaymentResponse(
            tenant.getId(),
            requestedAmount,
            appliedAmount,
            remaining,
            allocations
        );
    }

    private InvoiceBuildResult buildInvoice(
        Tenant tenant,
        PropertyUnit unit,
        Property property,
        LocalDate requestedBillingPeriodStart,
        List<UtilityChargeInput> requestedUtilityCharges,
        Long actorUserId
    ) {
        LocalDate billingPeriodStart = normalizeBillingPeriodStart(requestedBillingPeriodStart);
        if (invoiceRepository.existsByTenantIdAndBillingPeriodStartAndStatusNotIn(
            tenant.getId(),
            billingPeriodStart,
            DUPLICATE_EXEMPT_STATUSES
        )) {
            throw new AppException(
                "DUPLICATE_INVOICE",
                "An active invoice for tenant and billing month already exists",
                "billingPeriodStart"
            );
        }

        PropertySettings settings = propertySettingsRepository.findByPropertyId(property.getId())
            .orElseGet(() -> defaultSettings(property.getId()));

        YearMonth billingMonth = YearMonth.from(billingPeriodStart);
        LocalDate billingPeriodEnd = billingMonth.atEndOfMonth();
        int dueDay = Math.min(settings.getInvoiceDueDayOfMonth(), billingMonth.lengthOfMonth());
        LocalDate dueDate = billingMonth.atDay(dueDay);

        List<Map<String, Object>> utilityChargeSnapshot = normalizeUtilityCharges(requestedUtilityCharges);
        BigDecimal utilityTotal = utilityChargeSnapshot.stream()
            .map(line -> new BigDecimal(line.get("amountBdt").toString()))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);

        BigDecimal baseRent = tenant.getMonthlyRentBdt().setScale(2, RoundingMode.HALF_UP);
        BigDecimal lateFee = calculateLateFee(dueDate, settings.getLateFeeGraceDays(), settings.getLateFeeFlatBdt());

        BigDecimal subTotal = baseRent.add(utilityTotal).add(lateFee);
        BigDecimal tax = subTotal
            .multiply(settings.getTaxPercent())
            .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        BigDecimal totalDue = subTotal.add(tax).setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("invoiceDueDayOfMonth", settings.getInvoiceDueDayOfMonth());
        rules.put("lateFeeGraceDays", settings.getLateFeeGraceDays());
        rules.put("lateFeeFlatBdt", settings.getLateFeeFlatBdt());
        rules.put("taxPercent", settings.getTaxPercent());
        rules.put("invoiceFooterText", settings.getInvoiceFooterText());

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("utilityCharges", utilityChargeSnapshot);
        snapshot.put("rules", rules);

        Invoice invoice = new Invoice();
        invoice.setTenantId(tenant.getId());
        invoice.setPropertyUnitId(unit.getId());
        invoice.setBillingPeriodStart(billingPeriodStart);
        invoice.setBillingPeriodEnd(billingPeriodEnd);
        invoice.setDueDate(dueDate);
        invoice.setStatus(InvoiceStatus.DRAFT);
        invoice.setBaseRentBdt(baseRent);
        invoice.setUtilityChargesJson(toJson(snapshot));
        invoice.setLateFeeBdt(lateFee);
        invoice.setTaxBdt(tax);
        invoice.setTotalDueBdt(totalDue);
        invoice.setBalanceDueBdt(totalDue);
        invoice.setSmsText(null);
        invoice.setCreatedBy(actorUserId);

        List<String> warnings = new ArrayList<>();
        if (tenant.getLeaseEndDate() != null
            && !tenant.getLeaseEndDate().isBefore(billingPeriodStart)
            && !tenant.getLeaseEndDate().isAfter(billingPeriodEnd)) {
            warnings.add("Lease ends on " + tenant.getLeaseEndDate() + " during this billing period.");
        }

        return new InvoiceBuildResult(invoice, warnings);
    }

    private List<Map<String, Object>> normalizeUtilityCharges(List<UtilityChargeInput> requestedUtilityCharges) {
        if (requestedUtilityCharges == null || requestedUtilityCharges.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> normalized = new ArrayList<>();
        for (UtilityChargeInput item : requestedUtilityCharges) {
            String label = item.label().trim();
            BigDecimal amount = item.amountBdt().setScale(2, RoundingMode.HALF_UP);
            if (amount.compareTo(BigDecimal.ZERO) < 0) {
                throw new AppException("NEGATIVE_CHARGE_AMOUNT", "Utility amount cannot be negative", "utilityCharges");
            }
            if (amount.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            normalized.add(Map.of("label", label, "amountBdt", amount));
        }
        return normalized;
    }

    private BigDecimal calculateLateFee(LocalDate dueDate, Integer graceDays, BigDecimal configuredLateFee) {
        int resolvedGraceDays = Optional.ofNullable(graceDays).orElse(0);
        BigDecimal lateFee = Optional.ofNullable(configuredLateFee).orElse(BigDecimal.ZERO);
        LocalDate today = LocalDate.now(DHAKA_ZONE);
        if (today.isAfter(dueDate.plusDays(resolvedGraceDays))) {
            return lateFee.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    private void assertInvoicePayable(Invoice invoice) {
        if (!PAYABLE_STATUSES.contains(invoice.getStatus())) {
            throw new AppException("INVALID_INVOICE_STATUS", "Invoice is not payable in current status", "status");
        }
        if (invoice.getBalanceDueBdt().compareTo(BigDecimal.ZERO) <= 0) {
            throw new AppException("INVALID_INVOICE_STATUS", "Invoice is already fully paid", "status");
        }
    }

    private void savePayment(
        Long invoiceId,
        BigDecimal amountBdt,
        LocalDate paymentDate,
        String paymentMethod,
        String notes,
        Long actorUserId
    ) {
        InvoicePayment payment = new InvoicePayment();
        payment.setInvoiceId(invoiceId);
        payment.setAmountBdt(amountBdt);
        payment.setPaymentDate(paymentDate);
        payment.setPaymentMethod(paymentMethod.trim());
        payment.setNotes(notes == null || notes.isBlank() ? null : notes.trim());
        payment.setCreatedBy(actorUserId);
        invoicePaymentRepository.save(payment);
    }

    private void applyPaymentToInvoice(Invoice invoice, BigDecimal amountBdt, LocalDate paymentDate) {
        BigDecimal currentBalance = invoice.getBalanceDueBdt().setScale(2, RoundingMode.HALF_UP);
        BigDecimal updatedBalance = currentBalance.subtract(amountBdt).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        invoice.setBalanceDueBdt(updatedBalance);
        invoice.setStatus(resolveStatusAfterPayment(invoice, updatedBalance, paymentDate));
    }

    private InvoiceStatus resolveStatusAfterPayment(Invoice invoice, BigDecimal updatedBalance, LocalDate paymentDate) {
        if (updatedBalance.compareTo(BigDecimal.ZERO) == 0) {
            return InvoiceStatus.PAID;
        }
        if (paymentDate.isAfter(invoice.getDueDate())) {
            return InvoiceStatus.OVERDUE;
        }
        return InvoiceStatus.PARTIALLY_PAID;
    }

    private List<InvoiceResponse> toResponses(List<Invoice> invoices) {
        List<Long> tenantIds = invoices.stream().map(Invoice::getTenantId).distinct().toList();
        List<Long> unitIds = invoices.stream().map(Invoice::getPropertyUnitId).distinct().toList();

        Map<Long, Tenant> tenantMap = tenantRepository.findAllById(tenantIds).stream()
            .collect(Collectors.toMap(Tenant::getId, tenant -> tenant));
        Map<Long, PropertyUnit> unitMap = propertyUnitRepository.findAllById(unitIds).stream()
            .collect(Collectors.toMap(PropertyUnit::getId, unit -> unit));

        List<Long> propertyIds = unitMap.values().stream().map(PropertyUnit::getPropertyId).distinct().toList();
        Map<Long, Property> propertyMap = propertyRepository.findAllById(propertyIds).stream()
            .collect(Collectors.toMap(Property::getId, property -> property));

        List<InvoiceResponse> responses = new ArrayList<>();
        for (Invoice invoice : invoices) {
            Tenant tenant = tenantMap.get(invoice.getTenantId());
            PropertyUnit unit = unitMap.get(invoice.getPropertyUnitId());
            Property property = unit == null ? null : propertyMap.get(unit.getPropertyId());
            responses.add(toResponse(invoice, tenant, unit, property));
        }
        return responses;
    }

    private InvoiceResponse toResponse(Invoice invoice, Tenant tenant, PropertyUnit unit, Property property) {
        return new InvoiceResponse(
            invoice.getId(),
            invoice.getTenantId(),
            tenant == null ? null : tenant.getFullName(),
            invoice.getPropertyUnitId(),
            unit == null ? null : unit.getUnitIdentifier(),
            property == null ? null : property.getId(),
            property == null ? null : property.getPropertyName(),
            invoice.getBillingPeriodStart(),
            invoice.getBillingPeriodEnd(),
            invoice.getDueDate(),
            invoice.getStatus(),
            invoice.getBaseRentBdt(),
            invoice.getLateFeeBdt(),
            invoice.getTaxBdt(),
            invoice.getTotalDueBdt(),
            invoice.getBalanceDueBdt(),
            invoice.getSmsText(),
            extractDownloadUrl(invoice.getSmsText()),
            normalizeUtilityChargesJson(invoice.getUtilityChargesJson())
        );
    }

    private LocalDate parseBillingMonth(String billingMonth) {
        if (billingMonth == null || billingMonth.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(billingMonth).atDay(1);
        } catch (Exception ex) {
            throw new AppException("INVALID_BILLING_MONTH", "billingMonth must be YYYY-MM", "billingMonth");
        }
    }

    private LocalDate normalizeBillingPeriodStart(LocalDate rawDate) {
        if (rawDate == null) {
            throw new AppException("INVALID_BILLING_PERIOD", "billingPeriodStart is required", "billingPeriodStart");
        }
        return rawDate.withDayOfMonth(1);
    }

    private String composeSmsText(Invoice invoice, Tenant tenant, PropertyUnit unit, Property property, String downloadUrl) {
        String invoiceCode = "INV-" + invoice.getId();
        return String.format(
            "Dear %s, your rent invoice %s for %s Unit %s is BDT %s. Due by %s. Download: %s",
            tenant.getFullName(),
            invoiceCode,
            property.getPropertyName(),
            unit.getUnitIdentifier(),
            invoice.getTotalDueBdt(),
            invoice.getDueDate().format(SMS_DATE_FORMATTER),
            downloadUrl
        );
    }

    private String extractDownloadUrl(String smsText) {
        if (smsText == null || smsText.isBlank()) {
            return null;
        }
        int marker = smsText.indexOf("Download:");
        if (marker < 0) {
            return null;
        }
        String value = smsText.substring(marker + "Download:".length()).trim();
        return value.isBlank() ? null : value;
    }

    private Invoice getAccessibleInvoice(Long invoiceId, Long actorUserId, UserRole role) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new AppException("INVOICE_NOT_FOUND", "Invoice not found", "invoiceId"));
        PropertyUnit unit = getUnitOrThrow(invoice.getPropertyUnitId());
        getAccessibleProperty(unit.getPropertyId(), actorUserId, role);
        return invoice;
    }

    private Property getAccessibleProperty(Long propertyId, Long actorUserId, UserRole role) {
        return access.requireProperty(propertyId, actorUserId, role);
    }

    private List<Property> resolveAccessibleProperties(Long actorUserId, UserRole role, Long propertyId) {
        if (propertyId != null) {
            return List.of(getAccessibleProperty(propertyId, actorUserId, role));
        }
        return access.accessibleProperties(actorUserId, role);
    }

    private PropertyUnit getUnitOrThrow(Long unitId) {
        return propertyUnitRepository.findById(unitId)
            .orElseThrow(() -> new AppException("UNIT_NOT_FOUND", "Unit not found", "propertyUnitId"));
    }

    private Tenant getTenantOrThrow(Long tenantId) {
        return tenantRepository.findById(tenantId)
            .orElseThrow(() -> new AppException("TENANT_NOT_FOUND", "Tenant not found", "tenantId"));
    }

    private Property getPropertyOrThrow(Long propertyId) {
        return propertyRepository.findById(propertyId)
            .orElseThrow(() -> new AppException("PROPERTY_NOT_FOUND", "Property not found", "propertyId"));
    }

    private PropertySettings defaultSettings(Long propertyId) {
        PropertySettings settings = new PropertySettings();
        settings.setPropertyId(propertyId);
        settings.setInvoiceDueDayOfMonth(5);
        settings.setLateFeeFlatBdt(BigDecimal.ZERO);
        settings.setLateFeeGraceDays(0);
        settings.setTaxPercent(BigDecimal.ZERO);
        return settings;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private String normalizeUtilityChargesJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }

        String normalized = raw.trim();
        for (int i = 0; i < 3; i++) {
            try {
                var node = objectMapper.readTree(normalized);
                if (node.isTextual()) {
                    normalized = node.asText();
                    continue;
                }
                return normalized;
            } catch (Exception ex) {
                return raw;
            }
        }
        return normalized;
    }

    private record InvoiceBuildResult(Invoice invoice, List<String> warnings) {
    }
}
