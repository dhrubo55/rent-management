package com.renteasebd.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.renteasebd.common.AppException;
import com.renteasebd.domain.deposit.SecurityDepositTransaction;
import com.renteasebd.domain.invoice.InvoiceStatus;
import com.renteasebd.domain.property.Property;
import com.renteasebd.domain.property.PropertyUnit;
import com.renteasebd.domain.tenant.Tenant;
import com.renteasebd.domain.user.UserRole;
import com.renteasebd.invoice.InvoiceService;
import com.renteasebd.invoice.dto.InvoiceResponse;
import com.renteasebd.repository.PropertyUnitRepository;
import com.renteasebd.repository.SecurityDepositTransactionRepository;
import com.renteasebd.repository.TenantRepository;
import com.renteasebd.security.ResourceAccessService;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PdfService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM uuuu");
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm");
    private static final Color BORDER_COLOR = new Color(207, 218, 229);
    private static final Color TABLE_HEADER_BG = new Color(241, 246, 251);
    private static final Color STATUS_SUCCESS_BG = new Color(220, 247, 231);
    private static final Color STATUS_SUCCESS_TEXT = new Color(27, 110, 62);
    private static final Color STATUS_WARN_BG = new Color(255, 244, 221);
    private static final Color STATUS_WARN_TEXT = new Color(152, 102, 0);
    private static final Color STATUS_DANGER_BG = new Color(253, 228, 227);
    private static final Color STATUS_DANGER_TEXT = new Color(153, 43, 40);
    private static final Color STATUS_NEUTRAL_BG = new Color(234, 238, 243);
    private static final Color STATUS_NEUTRAL_TEXT = new Color(74, 85, 97);
    private static final Color TOTAL_BG = new Color(236, 245, 254);

    private final InvoiceService invoiceService;
    private final SecurityDepositTransactionRepository securityDepositTransactionRepository;
    private final TenantRepository tenantRepository;
    private final PropertyUnitRepository propertyUnitRepository;
    private final ObjectMapper objectMapper;
    private final ResourceAccessService access;

    public PdfService(
        InvoiceService invoiceService,
        SecurityDepositTransactionRepository securityDepositTransactionRepository,
        TenantRepository tenantRepository,
        PropertyUnitRepository propertyUnitRepository,
        ObjectMapper objectMapper,
        ResourceAccessService access
    ) {
        this.invoiceService = invoiceService;
        this.securityDepositTransactionRepository = securityDepositTransactionRepository;
        this.tenantRepository = tenantRepository;
        this.propertyUnitRepository = propertyUnitRepository;
        this.objectMapper = objectMapper;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public GeneratedPdf generateInvoicePdf(Long invoiceId, String requestedLang, Long actorUserId, UserRole role) {
        InvoiceResponse invoice = invoiceService.get(invoiceId, actorUserId, role);

        String lang = normalizeLang(requestedLang);
        return renderInvoicePdf(invoice, lang);
    }

    @Transactional(readOnly = true)
    public GeneratedPdf generateInvoicePdfPublic(Long invoiceId, String requestedLang) {
        InvoiceResponse invoice = invoiceService.getPublicDownloadable(invoiceId);
        String lang = normalizeLang(requestedLang);
        return renderInvoicePdf(invoice, lang);
    }

    @Transactional(readOnly = true)
    public GeneratedPdf generateDepositRefundReceiptPdf(Long transactionId, String requestedLang, Long actorUserId, UserRole role) {
        SecurityDepositTransaction txn = securityDepositTransactionRepository.findById(transactionId)
            .orElseThrow(() -> new AppException("TRANSACTION_NOT_FOUND", "Deposit transaction not found", "transactionId"));
        if (!"REFUND".equalsIgnoreCase(txn.getTxnType())) {
            throw new AppException("INVALID_TRANSACTION_TYPE", "Only REFUND transactions can generate refund receipt", "transactionId");
        }

        Tenant tenant = tenantRepository.findById(txn.getTenantId())
            .orElseThrow(() -> new AppException("TENANT_NOT_FOUND", "Tenant not found", "tenantId"));
        PropertyUnit unit = propertyUnitRepository.findById(tenant.getPropertyUnitId())
            .orElseThrow(() -> new AppException("UNIT_NOT_FOUND", "Unit not found", "propertyUnitId"));
        Property property = access.requireProperty(unit.getPropertyId(), actorUserId, role);

        String lang = normalizeLang(requestedLang);
        return renderPdf(
            buildDepositLinesEn(txn, tenant, unit, property),
            buildDepositLinesBn(txn, tenant, unit, property),
            lang
        );
    }

    private GeneratedPdf renderPdf(List<String> englishLines, List<String> banglaLines, String requestedLang) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            FontResolution fontResolution = resolveFont(document, requestedLang);
            PDFont font = fontResolution.font();
            PDFont boldFont = fontResolution.boldFont();
            boolean fallback = "bn".equals(requestedLang) && fontResolution.fallbackToEnglish();
            List<String> lines = fallback || "en".equals(requestedLang) ? englishLines : banglaLines;

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                float margin = 50f;
                float y = page.getMediaBox().getHeight() - margin;

                for (int i = 0; i < lines.size(); i++) {
                    String line = sanitize(lines.get(i));
                    if (line.isBlank()) {
                        y -= 12f;
                        continue;
                    }

                    if (y < 70f) {
                        content.close();
                        page = new PDPage(PDRectangle.A4);
                        document.addPage(page);
                        y = page.getMediaBox().getHeight() - margin;
                    }

                    content.beginText();
                    content.setFont(i == 0 ? boldFont : font, i == 0 ? 15f : 11.5f);
                    content.newLineAtOffset(margin, y);
                    content.showText(line);
                    content.endText();
                    y -= (i == 0 ? 22f : 15f);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return new GeneratedPdf(out.toByteArray(), fallback);
        } catch (Exception ex) {
            throw new AppException("PDF_GENERATION_FAILED", "Failed to generate PDF", null);
        }
    }

    private GeneratedPdf renderInvoicePdf(InvoiceResponse invoice, String requestedLang) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            FontResolution fontResolution = resolveFont(document, requestedLang);
            PDFont font = fontResolution.font();
            PDFont boldFont = fontResolution.boldFont();
            boolean fallback = "bn".equals(requestedLang) && fontResolution.fallbackToEnglish();
            boolean useBangla = "bn".equals(requestedLang) && !fallback;
            InvoiceCopy copy = invoiceCopy(useBangla);

            List<UtilityLine> utilityLines = extractUtilityLines(invoice.utilityChargesJson());
            BigDecimal utilityTotal = utilityLines.stream()
                .map(UtilityLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            List<InvoiceLineItem> chargeLines = new ArrayList<>();
            chargeLines.add(new InvoiceLineItem(copy.baseRentLabel(), invoice.baseRentBdt()));
            for (UtilityLine utilityLine : utilityLines) {
                chargeLines.add(new InvoiceLineItem(copy.utilityPrefix() + " " + utilityLine.label(), utilityLine.amount()));
            }
            chargeLines.add(new InvoiceLineItem(copy.utilityTotalLabel(), utilityTotal));
            chargeLines.add(new InvoiceLineItem(copy.lateFeeLabel(), invoice.lateFeeBdt()));
            chargeLines.add(new InvoiceLineItem(copy.taxLabel(), invoice.taxBdt()));

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                float pageWidth = page.getMediaBox().getWidth();
                float pageHeight = page.getMediaBox().getHeight();
                float margin = 42f;
                float contentWidth = pageWidth - (margin * 2f);
                float cursorY = pageHeight - margin;

                drawText(content, boldFont, 20f, margin, cursorY, copy.invoiceTitle());
                drawText(content, font, 10f, margin, cursorY - 14f, "RentEase BD");
                drawText(content, font, 9f, margin, cursorY - 28f, copy.generatedOnLabel() + ": " + LocalDateTime.now().format(DATE_TIME_FMT));

                String statusText = copy.statusPrefix() + ": " + copy.statusLabel(invoice.status());
                float badgeWidth = Math.min(Math.max((font.getStringWidth(statusText) / 1000f * 8.8f) + 20f, 112f), 200f);
                drawStatusBadge(content, font, invoice.status(), statusText, margin + contentWidth - badgeWidth, cursorY - 10f, badgeWidth, 18f);
                cursorY -= 46f;

                content.setStrokingColor(BORDER_COLOR);
                content.setLineWidth(1f);
                content.moveTo(margin, cursorY);
                content.lineTo(margin + contentWidth, cursorY);
                content.stroke();
                cursorY -= 14f;

                float boxGap = 12f;
                float leftBoxWidth = (contentWidth * 0.56f) - (boxGap / 2f);
                float rightBoxWidth = contentWidth - leftBoxWidth - boxGap;
                float boxHeight = 108f;
                float leftX = margin;
                float rightX = margin + leftBoxWidth + boxGap;

                drawBox(content, leftX, cursorY - boxHeight, leftBoxWidth, boxHeight);
                drawBox(content, rightX, cursorY - boxHeight, rightBoxWidth, boxHeight);

                float textStartY = cursorY - 14f;
                drawText(content, boldFont, 10f, leftX + 10f, textStartY, copy.billToLabel());
                drawText(content, font, 10f, leftX + 10f, textStartY - 16f,
                    nullSafe(invoice.tenantName(), "Tenant #" + invoice.tenantId()));
                drawText(content, font, 9.5f, leftX + 10f, textStartY - 30f,
                    copy.unitLabel() + ": " + nullSafe(invoice.unitIdentifier(), "#" + invoice.propertyUnitId()));
                drawText(content, font, 9.5f, leftX + 10f, textStartY - 44f,
                    copy.propertyLabel() + ": " + nullSafe(invoice.propertyName(), "Property #" + invoice.propertyId()));
                drawText(content, font, 9.5f, leftX + 10f, textStartY - 58f,
                    copy.tenantIdLabel() + ": " + invoice.tenantId());

                drawText(content, boldFont, 10f, rightX + 10f, textStartY, copy.invoiceDetailsLabel());
                drawLabelValue(content, font, rightX + 10f, textStartY - 16f, copy.invoiceIdLabel(), String.valueOf(invoice.id()));
                drawLabelValue(content, font, rightX + 10f, textStartY - 30f, copy.issueDateLabel(),
                    LocalDateTime.now().toLocalDate().format(DATE_FMT));
                drawLabelValue(content, font, rightX + 10f, textStartY - 44f, copy.dueDateLabel(), invoice.dueDate().format(DATE_FMT));
                drawLabelValue(content, font, rightX + 10f, textStartY - 58f, copy.billingPeriodLabel(),
                    invoice.billingPeriodStart().format(DATE_FMT) + " - " + invoice.billingPeriodEnd().format(DATE_FMT));

                cursorY = cursorY - boxHeight - 18f;

                float tableHeaderHeight = 24f;
                float rowHeight = 20f;
                float amountColWidth = 130f;
                float descriptionColWidth = contentWidth - amountColWidth;

                content.setNonStrokingColor(TABLE_HEADER_BG);
                content.addRect(margin, cursorY - tableHeaderHeight, contentWidth, tableHeaderHeight);
                content.fill();
                drawBox(content, margin, cursorY - tableHeaderHeight, contentWidth, tableHeaderHeight);

                drawText(content, boldFont, 10f, margin + 10f, cursorY - 15f, copy.descriptionColumnLabel());
                drawRightAlignedText(content, boldFont, 10f, margin + contentWidth - 10f, cursorY - 15f, copy.amountColumnLabel());

                cursorY -= tableHeaderHeight;
                for (InvoiceLineItem lineItem : chargeLines) {
                    drawBox(content, margin, cursorY - rowHeight, contentWidth, rowHeight);
                    content.setStrokingColor(BORDER_COLOR);
                    content.moveTo(margin + descriptionColWidth, cursorY);
                    content.lineTo(margin + descriptionColWidth, cursorY - rowHeight);
                    content.stroke();
                    drawText(content, font, 9.8f, margin + 10f, cursorY - 13f, sanitize(lineItem.label()));
                    drawRightAlignedText(content, font, 9.8f, margin + contentWidth - 10f, cursorY - 13f,
                        formatMoney(lineItem.amount()) + " BDT");
                    cursorY -= rowHeight;
                }

                cursorY -= 14f;
                float totalsBoxWidth = 220f;
                float totalsX = margin + contentWidth - totalsBoxWidth;
                float totalsHeight = 62f;
                content.setNonStrokingColor(TOTAL_BG);
                content.addRect(totalsX, cursorY - totalsHeight, totalsBoxWidth, totalsHeight);
                content.fill();
                drawBox(content, totalsX, cursorY - totalsHeight, totalsBoxWidth, totalsHeight);

                drawText(content, font, 10f, totalsX + 10f, cursorY - 18f, copy.totalDueLabel());
                drawRightAlignedText(content, boldFont, 10.5f, totalsX + totalsBoxWidth - 10f, cursorY - 18f,
                    formatMoney(invoice.totalDueBdt()) + " BDT");
                drawText(content, font, 10f, totalsX + 10f, cursorY - 38f, copy.balanceDueLabel());
                drawRightAlignedText(content, boldFont, 10.5f, totalsX + totalsBoxWidth - 10f, cursorY - 38f,
                    formatMoney(invoice.balanceDueBdt()) + " BDT");

                cursorY -= (totalsHeight + 18f);
                drawText(content, font, 9f, margin, cursorY, copy.noteLabel());
                drawText(content, font, 9f, margin, cursorY - 13f, copy.footerHint());
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return new GeneratedPdf(out.toByteArray(), fallback);
        } catch (Exception ex) {
            throw new AppException("PDF_GENERATION_FAILED", "Failed to generate PDF", null);
        }
    }

    private FontResolution resolveFont(PDDocument document, String requestedLang) {
        if (!"bn".equals(requestedLang)) {
            return new FontResolution(PDType1Font.HELVETICA, PDType1Font.HELVETICA_BOLD, false);
        }
        try {
            ClassPathResource resource = new ClassPathResource("fonts/NotoSansBengali-Regular.ttf");
            if (!resource.exists()) {
                return new FontResolution(PDType1Font.HELVETICA, PDType1Font.HELVETICA_BOLD, true);
            }
            PDFont regular;
            try (InputStream in = resource.getInputStream()) {
                regular = PDType0Font.load(document, in);
            }
            // Reuse same Bengali font for bold style in MVP.
            return new FontResolution(regular, regular, false);
        } catch (Exception ex) {
            return new FontResolution(PDType1Font.HELVETICA, PDType1Font.HELVETICA_BOLD, true);
        }
    }

    private void drawText(PDPageContentStream content, PDFont font, float size, float x, float y, String value) throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.setNonStrokingColor(Color.BLACK);
        content.newLineAtOffset(x, y);
        content.showText(sanitize(value));
        content.endText();
    }

    private void drawRightAlignedText(
        PDPageContentStream content,
        PDFont font,
        float size,
        float rightX,
        float y,
        String value
    ) throws IOException {
        String text = sanitize(value);
        float width = font.getStringWidth(text) / 1000f * size;
        drawText(content, font, size, rightX - width, y, text);
    }

    private void drawLabelValue(PDPageContentStream content, PDFont font, float x, float y, String label, String value) throws IOException {
        drawText(content, font, 9.3f, x, y, label + ": " + sanitize(value));
    }

    private void drawBox(PDPageContentStream content, float x, float y, float width, float height) throws IOException {
        content.setStrokingColor(BORDER_COLOR);
        content.setLineWidth(1f);
        content.addRect(x, y, width, height);
        content.stroke();
    }

    private void drawStatusBadge(
        PDPageContentStream content,
        PDFont font,
        InvoiceStatus status,
        String statusText,
        float x,
        float yTop,
        float width,
        float height
    ) throws IOException {
        BadgeStyle badgeStyle = statusBadge(status);
        float y = yTop - height;
        content.setNonStrokingColor(badgeStyle.bgColor());
        content.addRect(x, y, width, height);
        content.fill();
        content.setStrokingColor(BORDER_COLOR);
        content.addRect(x, y, width, height);
        content.stroke();

        content.beginText();
        content.setFont(font, 8.8f);
        content.setNonStrokingColor(badgeStyle.textColor());
        float textWidth = font.getStringWidth(statusText) / 1000f * 8.8f;
        content.newLineAtOffset(x + ((width - textWidth) / 2f), y + 5f);
        content.showText(statusText);
        content.endText();
    }

    private BadgeStyle statusBadge(InvoiceStatus status) {
        return switch (status) {
            case PAID -> new BadgeStyle(STATUS_SUCCESS_BG, STATUS_SUCCESS_TEXT);
            case PARTIALLY_PAID, OVERDUE -> new BadgeStyle(STATUS_WARN_BG, STATUS_WARN_TEXT);
            case CANCELLED, VOID -> new BadgeStyle(STATUS_DANGER_BG, STATUS_DANGER_TEXT);
            default -> new BadgeStyle(STATUS_NEUTRAL_BG, STATUS_NEUTRAL_TEXT);
        };
    }

    private String formatMoney(BigDecimal value) {
        BigDecimal safe = value == null ? BigDecimal.ZERO : value;
        NumberFormat numberFormat = NumberFormat.getNumberInstance(Locale.US);
        numberFormat.setMinimumFractionDigits(2);
        numberFormat.setMaximumFractionDigits(2);
        return numberFormat.format(safe);
    }

    private InvoiceCopy invoiceCopy(boolean bangla) {
        if (bangla) {
            return new InvoiceCopy(
                true,
                "ভাড়া ইনভয়েস",
                "বিল প্রস্তুতের সময়",
                "বিল প্রাপকের তথ্য",
                "ইনভয়েসের তথ্য",
                "ইউনিট",
                "প্রপার্টি",
                "ভাড়াটিয়া আইডি",
                "ইনভয়েস আইডি",
                "ইস্যু তারিখ",
                "পরিশোধের শেষ তারিখ",
                "বিলিং সময়কাল",
                "বিবরণ",
                "পরিমাণ (BDT)",
                "মূল ভাড়া",
                "ইউটিলিটি:",
                "ইউটিলিটি মোট",
                "দেরি ফি",
                "ট্যাক্স",
                "মোট বকেয়া",
                "বর্তমান বকেয়া",
                "নোট",
                "এই ইনভয়েস RentEase BD সিস্টেম থেকে তৈরি করা হয়েছে।",
                "স্ট্যাটাস"
            );
        }

        return new InvoiceCopy(
            false,
            "Rent Invoice",
            "Generated On",
            "Bill To",
            "Invoice Details",
            "Unit",
            "Property",
            "Tenant ID",
            "Invoice ID",
            "Issue Date",
            "Due Date",
            "Billing Period",
            "Description",
            "Amount (BDT)",
            "Base Rent",
            "Utility:",
            "Utility Total",
            "Late Fee",
            "Tax",
            "Total Due",
            "Balance Due",
            "Note",
            "This invoice was generated by RentEase BD.",
            "Status"
        );
    }

    private List<String> buildDepositLinesEn(
        SecurityDepositTransaction txn,
        Tenant tenant,
        PropertyUnit unit,
        Property property
    ) {
        List<String> lines = new ArrayList<>();
        lines.add("Security Deposit Refund Receipt");
        lines.add("Transaction ID: " + txn.getId());
        lines.add("Tenant: " + tenant.getFullName());
        lines.add("Property: " + property.getPropertyName());
        lines.add("Unit: " + unit.getUnitIdentifier());
        lines.add("Transaction Date: " + txn.getTransactionDate().format(DATE_FMT));
        lines.add("Amount Refunded (BDT): " + txn.getAmountBdt());
        lines.add("Reason: " + nullSafe(txn.getReason(), "-"));
        return lines;
    }

    private List<String> buildDepositLinesBn(
        SecurityDepositTransaction txn,
        Tenant tenant,
        PropertyUnit unit,
        Property property
    ) {
        List<String> lines = new ArrayList<>();
        lines.add("সিকিউরিটি ডিপোজিট রিফান্ড রশিদ");
        lines.add("ট্রানজেকশন আইডি: " + txn.getId());
        lines.add("ভাড়াটিয়া: " + tenant.getFullName());
        lines.add("প্রপার্টি: " + property.getPropertyName());
        lines.add("ইউনিট: " + unit.getUnitIdentifier());
        lines.add("তারিখ: " + txn.getTransactionDate().format(DATE_FMT));
        lines.add("রিফান্ড পরিমাণ (BDT): " + txn.getAmountBdt());
        lines.add("কারণ: " + nullSafe(txn.getReason(), "-"));
        return lines;
    }

    private String normalizeLang(String lang) {
        String normalized = lang == null ? "en" : lang.trim().toLowerCase();
        if (!"en".equals(normalized) && !"bn".equals(normalized)) {
            throw new AppException("INVALID_LANGUAGE", "Language must be en or bn", "lang");
        }
        return normalized;
    }

    private List<UtilityLine> extractUtilityLines(String utilityJson) {
        List<UtilityLine> lines = new ArrayList<>();
        if (utilityJson == null || utilityJson.isBlank()) {
            return lines;
        }

        try {
            JsonNode root = objectMapper.readTree(utilityJson);
            JsonNode items = root.path("utilityCharges");
            if (!items.isArray()) {
                return lines;
            }
            for (JsonNode node : items) {
                String label = node.path("label").asText("Utility");
                BigDecimal amount = new BigDecimal(node.path("amountBdt").asText("0"));
                lines.add(new UtilityLine(label, amount));
            }
            return lines;
        } catch (Exception ex) {
            return lines;
        }
    }

    private String sanitize(String text) {
        return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ');
    }

    private String nullSafe(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    public record GeneratedPdf(byte[] bytes, boolean fallbackToEnglish) {
    }

    private record FontResolution(PDFont font, PDFont boldFont, boolean fallbackToEnglish) {
    }

    private record UtilityLine(String label, BigDecimal amount) {
    }

    private record InvoiceLineItem(String label, BigDecimal amount) {
    }

    private record BadgeStyle(Color bgColor, Color textColor) {
    }

    private record InvoiceCopy(
        boolean bangla,
        String invoiceTitle,
        String generatedOnLabel,
        String billToLabel,
        String invoiceDetailsLabel,
        String unitLabel,
        String propertyLabel,
        String tenantIdLabel,
        String invoiceIdLabel,
        String issueDateLabel,
        String dueDateLabel,
        String billingPeriodLabel,
        String descriptionColumnLabel,
        String amountColumnLabel,
        String baseRentLabel,
        String utilityPrefix,
        String utilityTotalLabel,
        String lateFeeLabel,
        String taxLabel,
        String totalDueLabel,
        String balanceDueLabel,
        String noteLabel,
        String footerHint,
        String statusPrefix
    ) {
        String statusLabel(InvoiceStatus status) {
            if (!bangla) {
                return status.name().replace('_', ' ');
            }
            return switch (status) {
                case DRAFT -> "খসড়া";
                case SENT -> "পাঠানো হয়েছে";
                case PAID -> "পরিশোধিত";
                case PARTIALLY_PAID -> "আংশিক পরিশোধিত";
                case OVERDUE -> "বকেয়া";
                case CANCELLED -> "বাতিল";
                case VOID -> "অকার্যকর";
            };
        }
    }
}
