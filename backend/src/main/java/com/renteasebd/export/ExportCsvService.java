package com.renteasebd.export;

import com.renteasebd.common.AppException;
import com.renteasebd.domain.deposit.SecurityDepositTransaction;
import com.renteasebd.domain.expense.Expense;
import com.renteasebd.domain.invoice.Invoice;
import com.renteasebd.domain.invoice.InvoicePayment;
import com.renteasebd.domain.property.Property;
import com.renteasebd.domain.property.PropertyUnit;
import com.renteasebd.domain.tenant.Tenant;
import com.renteasebd.domain.user.UserRole;
import com.renteasebd.repository.ExpenseRepository;
import com.renteasebd.repository.InvoicePaymentRepository;
import com.renteasebd.repository.InvoiceRepository;
import com.renteasebd.repository.PropertyUnitRepository;
import com.renteasebd.repository.SecurityDepositTransactionRepository;
import com.renteasebd.repository.TenantRepository;
import com.renteasebd.security.ResourceAccessService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExportCsvService {

    private static final List<String> SUPPORTED_DATASETS = List.of("tenants", "invoices", "payments", "expenses", "deposits");

    private final PropertyUnitRepository propertyUnitRepository;
    private final TenantRepository tenantRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoicePaymentRepository invoicePaymentRepository;
    private final ExpenseRepository expenseRepository;
    private final SecurityDepositTransactionRepository securityDepositTransactionRepository;
    private final ResourceAccessService access;

    public ExportCsvService(
        PropertyUnitRepository propertyUnitRepository,
        TenantRepository tenantRepository,
        InvoiceRepository invoiceRepository,
        InvoicePaymentRepository invoicePaymentRepository,
        ExpenseRepository expenseRepository,
        SecurityDepositTransactionRepository securityDepositTransactionRepository,
        ResourceAccessService access
    ) {
        this.propertyUnitRepository = propertyUnitRepository;
        this.tenantRepository = tenantRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoicePaymentRepository = invoicePaymentRepository;
        this.expenseRepository = expenseRepository;
        this.securityDepositTransactionRepository = securityDepositTransactionRepository;
        this.access = access;
    }

    public List<String> supportedDatasets() {
        return SUPPORTED_DATASETS;
    }

    @Transactional(readOnly = true)
    public byte[] exportDataset(String dataset, Long actorUserId, UserRole role) {
        return switch (normalizeDataset(dataset)) {
            case "tenants" -> exportTenants(actorUserId, role);
            case "invoices" -> exportInvoices(actorUserId, role);
            case "payments" -> exportPayments(actorUserId, role);
            case "expenses" -> exportExpenses(actorUserId, role);
            case "deposits" -> exportDeposits(actorUserId, role);
            default -> throw new AppException("INVALID_DATASET", "Unsupported dataset", "dataset");
        };
    }

    @Transactional(readOnly = true)
    public byte[] exportTenants(Long actorUserId, UserRole role) {
        AccessScope scope = resolveScope(actorUserId, role);
        List<List<String>> rows = scope.tenants().stream().map(tenant -> List.of(
            asText(tenant.getId()),
            nullSafe(tenant.getFullName()),
            asText(tenant.getStatus()),
            asText(tenant.getPropertyUnitId()),
            asText(tenant.getMonthlyRentBdt()),
            asText(tenant.getLeaseStartDate()),
            asText(tenant.getLeaseEndDate())
        )).toList();

        return buildCsv(List.of(
            "id", "full_name", "status", "property_unit_id", "monthly_rent_bdt", "lease_start_date", "lease_end_date"
        ), rows);
    }

    @Transactional(readOnly = true)
    public byte[] exportInvoices(Long actorUserId, UserRole role) {
        AccessScope scope = resolveScope(actorUserId, role);
        List<List<String>> rows = scope.invoices().stream().map(invoice -> List.of(
            asText(invoice.getId()),
            asText(invoice.getTenantId()),
            asText(invoice.getPropertyUnitId()),
            asText(invoice.getBillingPeriodStart()),
            asText(invoice.getDueDate()),
            asText(invoice.getStatus()),
            asText(invoice.getTotalDueBdt()),
            asText(invoice.getBalanceDueBdt())
        )).toList();

        return buildCsv(List.of(
            "id", "tenant_id", "property_unit_id", "billing_period_start", "due_date", "status", "total_due_bdt", "balance_due_bdt"
        ), rows);
    }

    @Transactional(readOnly = true)
    public byte[] exportPayments(Long actorUserId, UserRole role) {
        AccessScope scope = resolveScope(actorUserId, role);
        if (scope.invoiceIds().isEmpty()) {
            return buildCsv(List.of("id", "invoice_id", "amount_bdt", "payment_date", "payment_method", "notes"), List.of());
        }

        List<InvoicePayment> payments = invoicePaymentRepository.findByInvoiceIdInOrderByPaymentDateDescIdDesc(scope.invoiceIds());
        List<List<String>> rows = payments.stream().map(payment -> List.of(
            asText(payment.getId()),
            asText(payment.getInvoiceId()),
            asText(payment.getAmountBdt()),
            asText(payment.getPaymentDate()),
            nullSafe(payment.getPaymentMethod()),
            nullSafe(payment.getNotes())
        )).toList();

        return buildCsv(List.of("id", "invoice_id", "amount_bdt", "payment_date", "payment_method", "notes"), rows);
    }

    @Transactional(readOnly = true)
    public byte[] exportExpenses(Long actorUserId, UserRole role) {
        AccessScope scope = resolveScope(actorUserId, role);
        if (scope.propertyIds().isEmpty()) {
            return buildCsv(List.of("id", "property_id", "category", "amount_bdt", "expense_date", "description"), List.of());
        }

        List<Expense> expenses = expenseRepository.findByPropertyIdInOrderByExpenseDateDescIdDesc(scope.propertyIds());
        List<List<String>> rows = expenses.stream().map(expense -> List.of(
            asText(expense.getId()),
            asText(expense.getPropertyId()),
            nullSafe(expense.getCategory()),
            asText(expense.getAmountBdt()),
            asText(expense.getExpenseDate()),
            nullSafe(expense.getDescription())
        )).toList();

        return buildCsv(List.of("id", "property_id", "category", "amount_bdt", "expense_date", "description"), rows);
    }

    @Transactional(readOnly = true)
    public byte[] exportDeposits(Long actorUserId, UserRole role) {
        AccessScope scope = resolveScope(actorUserId, role);
        if (scope.tenantIds().isEmpty()) {
            return buildCsv(List.of("id", "tenant_id", "txn_type", "amount_bdt", "reason", "transaction_date"), List.of());
        }

        List<SecurityDepositTransaction> txns = securityDepositTransactionRepository
            .findByTenantIdInOrderByTransactionDateDescIdDesc(scope.tenantIds());
        List<List<String>> rows = txns.stream().map(txn -> List.of(
            asText(txn.getId()),
            asText(txn.getTenantId()),
            nullSafe(txn.getTxnType()),
            asText(txn.getAmountBdt()),
            nullSafe(txn.getReason()),
            asText(txn.getTransactionDate())
        )).toList();

        return buildCsv(List.of("id", "tenant_id", "txn_type", "amount_bdt", "reason", "transaction_date"), rows);
    }

    private AccessScope resolveScope(Long actorUserId, UserRole role) {
        List<Property> properties = access.accessibleProperties(actorUserId, role);
        if (properties.isEmpty()) {
            return new AccessScope(List.of(), List.of(), List.of(), List.of(), List.of());
        }

        List<Long> propertyIds = properties.stream().map(Property::getId).toList();
        List<PropertyUnit> units = propertyUnitRepository.findByPropertyIdIn(propertyIds);
        if (units.isEmpty()) {
            return new AccessScope(propertyIds, List.of(), List.of(), List.of(), List.of());
        }

        List<Long> unitIds = units.stream().map(PropertyUnit::getId).toList();
        List<Tenant> tenants = tenantRepository.findByPropertyUnitIdIn(unitIds);
        List<Long> tenantIds = tenants.stream().map(Tenant::getId).toList();

        List<Invoice> invoices = invoiceRepository.findByPropertyUnitIdInOrderByBillingPeriodStartDescIdDesc(unitIds);
        List<Long> invoiceIds = invoices.stream().map(Invoice::getId).toList();

        return new AccessScope(propertyIds, unitIds, tenants, tenantIds, invoices, invoiceIds);
    }

    private String normalizeDataset(String dataset) {
        if (dataset == null || dataset.isBlank()) {
            throw new AppException("INVALID_DATASET", "Dataset is required", "dataset");
        }
        String normalized = dataset.trim().toLowerCase();
        if (!SUPPORTED_DATASETS.contains(normalized)) {
            throw new AppException("INVALID_DATASET", "Dataset is not supported", "dataset");
        }
        return normalized;
    }

    private byte[] buildCsv(List<String> headers, List<List<String>> rows) {
        StringBuilder builder = new StringBuilder();
        builder.append('\uFEFF');
        appendCsvRow(builder, headers);
        for (List<String> row : rows) {
            appendCsvRow(builder, row);
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendCsvRow(StringBuilder builder, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(escapeCsv(values.get(i)));
        }
        builder.append('\n');
    }

    private String escapeCsv(String raw) {
        String value = raw == null ? "" : raw;
        boolean quote = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        String escaped = value.replace("\"", "\"\"");
        return quote ? "\"" + escaped + "\"" : escaped;
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private record AccessScope(
        List<Long> propertyIds,
        List<Long> unitIds,
        List<Tenant> tenants,
        List<Long> tenantIds,
        List<Invoice> invoices,
        List<Long> invoiceIds
    ) {
        private AccessScope(List<Long> propertyIds, List<Long> unitIds, List<Tenant> tenants, List<Long> tenantIds, List<Invoice> invoices) {
            this(propertyIds, unitIds, tenants, tenantIds, invoices, invoices.stream().map(Invoice::getId).toList());
        }
    }
}
