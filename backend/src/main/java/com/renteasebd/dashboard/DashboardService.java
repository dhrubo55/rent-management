package com.renteasebd.dashboard;

import com.renteasebd.dashboard.dto.DashboardOverdueAgingResponse;
import com.renteasebd.dashboard.dto.DashboardOverdueBucketResponse;
import com.renteasebd.dashboard.dto.DashboardPropertyBreakdownResponse;
import com.renteasebd.dashboard.dto.DashboardSummaryResponse;
import com.renteasebd.dashboard.dto.DashboardTrendPointResponse;
import com.renteasebd.domain.invoice.Invoice;
import com.renteasebd.domain.invoice.InvoiceStatus;
import com.renteasebd.domain.property.OccupancyStatus;
import com.renteasebd.domain.property.Property;
import com.renteasebd.domain.property.PropertyUnit;
import com.renteasebd.domain.tenant.Tenant;
import com.renteasebd.domain.tenant.TenantStatus;
import com.renteasebd.domain.user.UserRole;
import com.renteasebd.repository.InvoiceRepository;
import com.renteasebd.repository.PropertyUnitRepository;
import com.renteasebd.repository.TenantRepository;
import com.renteasebd.security.ResourceAccessService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {

    private static final Set<InvoiceStatus> EXCLUDED_INVOICE_STATUSES = Set.of(InvoiceStatus.CANCELLED, InvoiceStatus.VOID);

    private final PropertyUnitRepository propertyUnitRepository;
    private final TenantRepository tenantRepository;
    private final InvoiceRepository invoiceRepository;
    private final ResourceAccessService access;

    public DashboardService(
        PropertyUnitRepository propertyUnitRepository,
        TenantRepository tenantRepository,
        InvoiceRepository invoiceRepository,
        ResourceAccessService access
    ) {
        this.propertyUnitRepository = propertyUnitRepository;
        this.tenantRepository = tenantRepository;
        this.invoiceRepository = invoiceRepository;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public DashboardSummaryResponse summary(Long actorUserId, UserRole role, Long propertyId, String fromMonth, String toMonth) {
        DataScope scope = resolveScope(actorUserId, role, propertyId);
        List<Invoice> scopedInvoices = filterInvoicesByBillingRange(scope.invoices(), fromMonth, toMonth);
        int totalProperties = scope.properties().size();
        int totalUnits = scope.units().size();
        int occupiedUnits = (int) scope.units().stream()
            .filter(unit -> unit.getOccupancyStatus() == OccupancyStatus.OCCUPIED)
            .count();
        int activeTenants = (int) scope.tenants().stream().filter(tenant -> tenant.getStatus() == TenantStatus.ACTIVE).count();

        List<Invoice> receivableInvoices = receivableInvoices(scopedInvoices);
        BigDecimal receivable = receivableInvoices.stream()
            .map(invoice -> safeMoney(invoice.getBalanceDueBdt()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        int overdueCount = (int) receivableInvoices.stream().filter(this::isOverdue).count();

        BigDecimal occupancyRate = totalUnits == 0
            ? BigDecimal.ZERO
            : BigDecimal.valueOf(occupiedUnits)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(totalUnits), 2, RoundingMode.HALF_UP);

        return new DashboardSummaryResponse(
            totalProperties,
            totalUnits,
            occupiedUnits,
            activeTenants,
            occupancyRate,
            scaleMoney(receivable),
            overdueCount
        );
    }

    @Transactional(readOnly = true)
    public List<DashboardTrendPointResponse> trend(
        Long actorUserId,
        UserRole role,
        Integer months,
        Long propertyId,
        String fromMonth,
        String toMonth
    ) {
        DataScope scope = resolveScope(actorUserId, role, propertyId);
        int monthWindow = normalizeMonthWindow(months);
        List<Invoice> scopedInvoices = filterInvoicesByBillingRange(scope.invoices(), fromMonth, toMonth);

        YearMonth current = YearMonth.now();
        LinkedHashMap<YearMonth, TrendAggregate> series = new LinkedHashMap<>();
        for (int i = monthWindow - 1; i >= 0; i--) {
            YearMonth bucket = current.minusMonths(i);
            series.put(bucket, new TrendAggregate());
        }

        for (Invoice invoice : receivableInvoices(scopedInvoices)) {
            YearMonth bucket = YearMonth.from(invoice.getBillingPeriodStart());
            TrendAggregate aggregate = series.get(bucket);
            if (aggregate == null) {
                continue;
            }

            BigDecimal totalDue = safeMoney(invoice.getTotalDueBdt());
            BigDecimal outstanding = safeMoney(invoice.getBalanceDueBdt());
            BigDecimal collected = totalDue.subtract(outstanding).max(BigDecimal.ZERO);

            aggregate.invoiceCount++;
            aggregate.totalDueBdt = aggregate.totalDueBdt.add(totalDue);
            aggregate.collectedBdt = aggregate.collectedBdt.add(collected);
            aggregate.outstandingBdt = aggregate.outstandingBdt.add(outstanding);
        }

        List<DashboardTrendPointResponse> points = new ArrayList<>();
        for (Map.Entry<YearMonth, TrendAggregate> entry : series.entrySet()) {
            TrendAggregate aggregate = entry.getValue();
            points.add(new DashboardTrendPointResponse(
                entry.getKey().toString(),
                aggregate.invoiceCount,
                scaleMoney(aggregate.totalDueBdt),
                scaleMoney(aggregate.collectedBdt),
                scaleMoney(aggregate.outstandingBdt)
            ));
        }
        return points;
    }

    @Transactional(readOnly = true)
    public List<DashboardPropertyBreakdownResponse> propertyBreakdown(
        Long actorUserId,
        UserRole role,
        Long propertyId,
        String fromMonth,
        String toMonth
    ) {
        DataScope scope = resolveScope(actorUserId, role, propertyId);
        List<Invoice> scopedInvoices = filterInvoicesByBillingRange(scope.invoices(), fromMonth, toMonth);

        Map<Long, List<PropertyUnit>> unitsByProperty = scope.units().stream()
            .collect(Collectors.groupingBy(PropertyUnit::getPropertyId));

        Map<Long, List<Tenant>> tenantsByProperty = scope.tenants().stream()
            .collect(Collectors.groupingBy(tenant -> {
                PropertyUnit unit = scope.unitById().get(tenant.getPropertyUnitId());
                return unit == null ? -1L : unit.getPropertyId();
            }));

        Map<Long, List<Invoice>> invoicesByProperty = scopedInvoices.stream()
            .collect(Collectors.groupingBy(invoice -> {
                PropertyUnit unit = scope.unitById().get(invoice.getPropertyUnitId());
                return unit == null ? -1L : unit.getPropertyId();
            }));

        return scope.properties().stream()
            .sorted(Comparator.comparing(Property::getPropertyName, String.CASE_INSENSITIVE_ORDER))
            .map(property -> {
                List<PropertyUnit> propertyUnits = unitsByProperty.getOrDefault(property.getId(), List.of());
                List<Tenant> propertyTenants = tenantsByProperty.getOrDefault(property.getId(), List.of());
                List<Invoice> propertyInvoices = receivableInvoices(invoicesByProperty.getOrDefault(property.getId(), List.of()));

                int totalUnits = propertyUnits.size();
                int occupiedUnits = (int) propertyUnits.stream()
                    .filter(unit -> unit.getOccupancyStatus() == OccupancyStatus.OCCUPIED)
                    .count();
                int activeTenants = (int) propertyTenants.stream().filter(tenant -> tenant.getStatus() == TenantStatus.ACTIVE).count();
                BigDecimal receivable = propertyInvoices.stream()
                    .map(invoice -> safeMoney(invoice.getBalanceDueBdt()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                int overdueCount = (int) propertyInvoices.stream().filter(this::isOverdue).count();

                return new DashboardPropertyBreakdownResponse(
                    property.getId(),
                    property.getPropertyName(),
                    totalUnits,
                    occupiedUnits,
                    activeTenants,
                    scaleMoney(receivable),
                    overdueCount
                );
            })
            .toList();
    }

    @Transactional(readOnly = true)
    public DashboardOverdueAgingResponse overdueAging(
        Long actorUserId,
        UserRole role,
        Long propertyId,
        String fromMonth,
        String toMonth
    ) {
        DataScope scope = resolveScope(actorUserId, role, propertyId);
        List<Invoice> scopedInvoices = filterInvoicesByBillingRange(scope.invoices(), fromMonth, toMonth);

        AgingBucket b1 = new AgingBucket("1-30 days");
        AgingBucket b2 = new AgingBucket("31-60 days");
        AgingBucket b3 = new AgingBucket("61-90 days");
        AgingBucket b4 = new AgingBucket("90+ days");

        for (Invoice invoice : receivableInvoices(scopedInvoices)) {
            if (!isOverdue(invoice)) {
                continue;
            }
            long daysPastDue = ChronoUnit.DAYS.between(invoice.getDueDate(), LocalDate.now());
            BigDecimal amount = safeMoney(invoice.getBalanceDueBdt());

            if (daysPastDue <= 30) {
                b1.add(amount);
            } else if (daysPastDue <= 60) {
                b2.add(amount);
            } else if (daysPastDue <= 90) {
                b3.add(amount);
            } else {
                b4.add(amount);
            }
        }

        List<DashboardOverdueBucketResponse> buckets = List.of(
            b1.toResponse(),
            b2.toResponse(),
            b3.toResponse(),
            b4.toResponse()
        );

        int overdueCount = buckets.stream().mapToInt(DashboardOverdueBucketResponse::invoiceCount).sum();
        BigDecimal overdueAmount = buckets.stream()
            .map(DashboardOverdueBucketResponse::amountBdt)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new DashboardOverdueAgingResponse(buckets, overdueCount, scaleMoney(overdueAmount));
    }

    private DataScope resolveScope(Long actorUserId, UserRole role, Long propertyId) {
        List<Property> properties = access.accessibleProperties(actorUserId, role);
        if (propertyId != null) {
            properties = properties.stream().filter(property -> property.getId().equals(propertyId)).toList();
        }
        if (properties.isEmpty()) {
            return new DataScope(List.of(), List.of(), List.of(), List.of(), Map.of());
        }

        List<Long> propertyIds = properties.stream().map(Property::getId).toList();
        List<PropertyUnit> units = propertyUnitRepository.findByPropertyIdIn(propertyIds);
        if (units.isEmpty()) {
            return new DataScope(properties, List.of(), List.of(), List.of(), Map.of());
        }

        List<Long> unitIds = units.stream().map(PropertyUnit::getId).toList();
        List<Tenant> tenants = tenantRepository.findByPropertyUnitIdIn(unitIds);
        List<Invoice> invoices = invoiceRepository.findByPropertyUnitIdInOrderByBillingPeriodStartDescIdDesc(unitIds);
        Map<Long, PropertyUnit> unitById = units.stream().collect(Collectors.toMap(PropertyUnit::getId, unit -> unit));

        return new DataScope(properties, units, tenants, invoices, unitById);
    }

    private List<Invoice> receivableInvoices(List<Invoice> invoices) {
        return invoices.stream()
            .filter(invoice -> !EXCLUDED_INVOICE_STATUSES.contains(invoice.getStatus()))
            .filter(invoice -> safeMoney(invoice.getBalanceDueBdt()).signum() > 0)
            .toList();
    }

    private boolean isOverdue(Invoice invoice) {
        return invoice.getDueDate() != null && invoice.getDueDate().isBefore(LocalDate.now());
    }

    private int normalizeMonthWindow(Integer months) {
        if (months == null) {
            return 12;
        }
        return Math.max(1, Math.min(months, 24));
    }

    private BigDecimal safeMoney(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private List<Invoice> filterInvoicesByBillingRange(List<Invoice> invoices, String fromMonth, String toMonth) {
        YearMonth from = parseYearMonth(fromMonth, "fromMonth");
        YearMonth to = parseYearMonth(toMonth, "toMonth");
        if (from != null && to != null && from.isAfter(to)) {
            throw new com.renteasebd.common.AppException(
                "INVALID_DATE_RANGE",
                "fromMonth must be less than or equal to toMonth",
                "fromMonth"
            );
        }
        return invoices.stream()
            .filter(invoice -> {
                YearMonth period = YearMonth.from(invoice.getBillingPeriodStart());
                if (from != null && period.isBefore(from)) {
                    return false;
                }
                if (to != null && period.isAfter(to)) {
                    return false;
                }
                return true;
            })
            .toList();
    }

    private YearMonth parseYearMonth(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(value);
        } catch (Exception ex) {
            throw new com.renteasebd.common.AppException("INVALID_MONTH", field + " must be YYYY-MM", field);
        }
    }

    private BigDecimal scaleMoney(BigDecimal value) {
        return safeMoney(value).setScale(2, RoundingMode.HALF_UP);
    }

    private record DataScope(
        List<Property> properties,
        List<PropertyUnit> units,
        List<Tenant> tenants,
        List<Invoice> invoices,
        Map<Long, PropertyUnit> unitById
    ) {
    }

    private static class TrendAggregate {
        private int invoiceCount = 0;
        private BigDecimal totalDueBdt = BigDecimal.ZERO;
        private BigDecimal collectedBdt = BigDecimal.ZERO;
        private BigDecimal outstandingBdt = BigDecimal.ZERO;
    }

    private static class AgingBucket {
        private final String label;
        private int count;
        private BigDecimal amount = BigDecimal.ZERO;

        private AgingBucket(String label) {
            this.label = label;
        }

        private void add(BigDecimal amountBdt) {
            count++;
            amount = amount.add(amountBdt);
        }

        private DashboardOverdueBucketResponse toResponse() {
            return new DashboardOverdueBucketResponse(label, count, amount.setScale(2, RoundingMode.HALF_UP));
        }
    }
}
