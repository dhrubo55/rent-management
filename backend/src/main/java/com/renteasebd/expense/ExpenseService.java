package com.renteasebd.expense;

import com.renteasebd.common.AppException;
import com.renteasebd.common.AuditService;
import com.renteasebd.domain.expense.Expense;
import com.renteasebd.domain.property.Property;
import com.renteasebd.domain.user.UserRole;
import com.renteasebd.expense.dto.CreateExpenseRequest;
import com.renteasebd.expense.dto.ExpenseResponse;
import com.renteasebd.expense.dto.UpdateExpenseRequest;
import com.renteasebd.repository.ExpenseRepository;
import com.renteasebd.security.ResourceAccessService;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ExpenseService {

    private static final long MAX_RECEIPT_BYTES = 5L * 1024 * 1024;

    private final ExpenseRepository expenseRepository;
    private final AuditService auditService;
    private final ResourceAccessService access;

    public ExpenseService(ExpenseRepository expenseRepository, AuditService auditService,
                          ResourceAccessService access) {
        this.expenseRepository = expenseRepository;
        this.auditService = auditService;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public List<ExpenseResponse> list(Long actorUserId, UserRole role, Long propertyId) {
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

        Map<Long, String> propertyNames = accessibleProperties.stream()
            .collect(Collectors.toMap(Property::getId, Property::getPropertyName));

        return expenseRepository.findByPropertyIdInOrderByExpenseDateDescIdDesc(propertyIds).stream()
            .map(expense -> toResponse(expense, propertyNames.get(expense.getPropertyId())))
            .toList();
    }

    @Transactional(readOnly = true)
    public ExpenseResponse get(Long expenseId, Long actorUserId, UserRole role) {
        Expense expense = getAccessibleExpense(expenseId, actorUserId, role);
        Property property = getAccessibleProperty(expense.getPropertyId(), actorUserId, role);
        return toResponse(expense, property.getPropertyName());
    }

    @Transactional
    public ExpenseResponse create(CreateExpenseRequest request, Long actorUserId, UserRole role) {
        Property property = getAccessibleProperty(request.getPropertyId(), actorUserId, role);

        Expense expense = new Expense();
        expense.setPropertyId(property.getId());
        expense.setCategory(cleanCategory(request.getCategory()));
        expense.setAmountBdt(normalizeMoney(request.getAmountBdt()));
        expense.setExpenseDate(request.getExpenseDate());
        expense.setDescription(cleanDescription(request.getDescription()));
        expense.setCreatedBy(actorUserId);

        ReceiptPayload receipt = parseReceipt(request.getReceipt(), false);
        if (receipt != null) {
            expense.setReceiptBlob(receipt.bytes());
            expense.setReceiptMimeType(receipt.mimeType());
        }

        Expense saved = expenseRepository.save(expense);
        ExpenseResponse response = toResponse(saved, property.getPropertyName());
        auditService.log(actorUserId, "EXPENSE_CREATED", "EXPENSE", String.valueOf(saved.getId()), null, response);
        return response;
    }

    @Transactional
    public ExpenseResponse update(Long expenseId, UpdateExpenseRequest request, Long actorUserId, UserRole role) {
        Expense expense = getAccessibleExpense(expenseId, actorUserId, role);
        Property oldProperty = getAccessibleProperty(expense.getPropertyId(), actorUserId, role);
        ExpenseResponse oldState = toResponse(expense, oldProperty.getPropertyName());

        Property property = getAccessibleProperty(request.propertyId(), actorUserId, role);
        expense.setPropertyId(property.getId());
        expense.setCategory(cleanCategory(request.category()));
        expense.setAmountBdt(normalizeMoney(request.amountBdt()));
        expense.setExpenseDate(request.expenseDate());
        expense.setDescription(cleanDescription(request.description()));

        Expense saved = expenseRepository.save(expense);
        ExpenseResponse newState = toResponse(saved, property.getPropertyName());
        auditService.log(actorUserId, "EXPENSE_UPDATED", "EXPENSE", String.valueOf(saved.getId()), oldState, newState);
        return newState;
    }

    @Transactional
    public void delete(Long expenseId, Long actorUserId, UserRole role) {
        Expense expense = getAccessibleExpense(expenseId, actorUserId, role);
        Property property = getAccessibleProperty(expense.getPropertyId(), actorUserId, role);
        ExpenseResponse oldState = toResponse(expense, property.getPropertyName());
        expenseRepository.delete(expense);
        auditService.log(actorUserId, "EXPENSE_DELETED", "EXPENSE", String.valueOf(expense.getId()), oldState, null);
    }

    @Transactional(readOnly = true)
    public ReceiptFile getReceipt(Long expenseId, Long actorUserId, UserRole role) {
        Expense expense = getAccessibleExpense(expenseId, actorUserId, role);
        if (expense.getReceiptBlob() == null || expense.getReceiptBlob().length == 0 || expense.getReceiptMimeType() == null) {
            throw new AppException("EXPENSE_RECEIPT_NOT_FOUND", "Expense receipt not found", "expenseId");
        }
        return new ReceiptFile(expense.getReceiptBlob(), expense.getReceiptMimeType());
    }

    @Transactional
    public ExpenseResponse upsertReceipt(Long expenseId, MultipartFile receipt, Long actorUserId, UserRole role) {
        Expense expense = getAccessibleExpense(expenseId, actorUserId, role);
        Property property = getAccessibleProperty(expense.getPropertyId(), actorUserId, role);
        ExpenseResponse oldState = toResponse(expense, property.getPropertyName());

        ReceiptPayload parsed = parseReceipt(receipt, true);
        expense.setReceiptBlob(parsed.bytes());
        expense.setReceiptMimeType(parsed.mimeType());

        Expense saved = expenseRepository.save(expense);
        ExpenseResponse newState = toResponse(saved, property.getPropertyName());
        auditService.log(actorUserId, "EXPENSE_RECEIPT_UPSERTED", "EXPENSE", String.valueOf(saved.getId()), oldState, newState);
        return newState;
    }

    @Transactional
    public ExpenseResponse removeReceipt(Long expenseId, Long actorUserId, UserRole role) {
        Expense expense = getAccessibleExpense(expenseId, actorUserId, role);
        Property property = getAccessibleProperty(expense.getPropertyId(), actorUserId, role);
        ExpenseResponse oldState = toResponse(expense, property.getPropertyName());

        expense.setReceiptBlob(null);
        expense.setReceiptMimeType(null);

        Expense saved = expenseRepository.save(expense);
        ExpenseResponse newState = toResponse(saved, property.getPropertyName());
        auditService.log(actorUserId, "EXPENSE_RECEIPT_REMOVED", "EXPENSE", String.valueOf(saved.getId()), oldState, newState);
        return newState;
    }

    private List<Property> resolveAccessibleProperties(Long actorUserId, UserRole role) {
        return access.accessibleProperties(actorUserId, role);
    }

    private Expense getAccessibleExpense(Long expenseId, Long actorUserId, UserRole role) {
        Expense expense = expenseRepository.findById(expenseId)
            .orElseThrow(() -> new AppException("EXPENSE_NOT_FOUND", "Expense not found", "expenseId"));
        getAccessibleProperty(expense.getPropertyId(), actorUserId, role);
        return expense;
    }

    private Property getAccessibleProperty(Long propertyId, Long actorUserId, UserRole role) {
        return access.requireProperty(propertyId, actorUserId, role);
    }

    private String cleanCategory(String category) {
        String value = category == null ? "" : category.trim();
        if (value.isEmpty()) {
            throw new AppException("VALIDATION_ERROR", "Category is required", "category");
        }
        return value;
    }

    private String cleanDescription(String description) {
        if (description == null) {
            return null;
        }
        String value = description.trim();
        return value.isEmpty() ? null : value;
    }

    private BigDecimal normalizeMoney(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new AppException("VALIDATION_ERROR", "Amount must be greater than 0", "amountBdt");
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private ReceiptPayload parseReceipt(MultipartFile receipt, boolean required) {
        if (receipt == null || receipt.isEmpty()) {
            if (required) {
                throw new AppException("RECEIPT_REQUIRED", "Receipt file is required", "receipt");
            }
            return null;
        }

        if (receipt.getSize() > MAX_RECEIPT_BYTES) {
            throw new AppException("FILE_TOO_LARGE", "Receipt file must be at most 5MB", "receipt");
        }

        byte[] bytes = readBytes(receipt);
        String detectedMime = detectMimeType(bytes);
        if (detectedMime == null) {
            throw new AppException("INVALID_FILE_TYPE", "Receipt must be JPEG, PNG, or PDF", "receipt");
        }

        String providedMime = receipt.getContentType();
        if (providedMime != null && !providedMime.isBlank() && !mimeMatches(detectedMime, providedMime)) {
            throw new AppException("INVALID_FILE_TYPE", "Receipt MIME type does not match file content", "receipt");
        }

        return new ReceiptPayload(bytes, detectedMime);
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new AppException("FILE_READ_ERROR", "Failed to read uploaded file", "receipt");
        }
    }

    private String detectMimeType(byte[] bytes) {
        if (isPng(bytes)) {
            return "image/png";
        }
        if (isJpeg(bytes)) {
            return "image/jpeg";
        }
        if (isPdf(bytes)) {
            return "application/pdf";
        }
        return null;
    }

    private boolean isPng(byte[] bytes) {
        if (bytes.length < 8) {
            return false;
        }
        return (bytes[0] & 0xFF) == 0x89
            && (bytes[1] & 0xFF) == 0x50
            && (bytes[2] & 0xFF) == 0x4E
            && (bytes[3] & 0xFF) == 0x47
            && (bytes[4] & 0xFF) == 0x0D
            && (bytes[5] & 0xFF) == 0x0A
            && (bytes[6] & 0xFF) == 0x1A
            && (bytes[7] & 0xFF) == 0x0A;
    }

    private boolean isJpeg(byte[] bytes) {
        if (bytes.length < 3) {
            return false;
        }
        return (bytes[0] & 0xFF) == 0xFF
            && (bytes[1] & 0xFF) == 0xD8
            && (bytes[2] & 0xFF) == 0xFF;
    }

    private boolean isPdf(byte[] bytes) {
        if (bytes.length < 5) {
            return false;
        }
        return bytes[0] == '%'
            && bytes[1] == 'P'
            && bytes[2] == 'D'
            && bytes[3] == 'F'
            && bytes[4] == '-';
    }

    private boolean mimeMatches(String detected, String provided) {
        String normalized = provided.trim().toLowerCase();
        if (detected.equals(normalized)) {
            return true;
        }
        return "image/jpeg".equals(detected) && "image/jpg".equals(normalized);
    }

    private ExpenseResponse toResponse(Expense expense, String propertyName) {
        return new ExpenseResponse(
            expense.getId(),
            expense.getPropertyId(),
            propertyName,
            expense.getCategory(),
            expense.getAmountBdt(),
            expense.getExpenseDate(),
            expense.getDescription(),
            expense.getReceiptBlob() != null && expense.getReceiptBlob().length > 0,
            expense.getReceiptMimeType()
        );
    }

    public record ReceiptFile(byte[] bytes, String mimeType) {
    }

    private record ReceiptPayload(byte[] bytes, String mimeType) {
    }
}
