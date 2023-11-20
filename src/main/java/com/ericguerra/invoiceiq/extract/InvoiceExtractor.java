package com.ericguerra.invoiceiq.extract;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns OCR text of a supplier invoice into structured fields. OCR is noisy (O↔0, l↔1, stray
 * spaces in numbers), so every field carries a confidence and the result is cross-checked:
 * line items must sum to the subtotal, and subtotal + tax must equal the total.
 */
public final class InvoiceExtractor {

    public record LineItem(String description, BigDecimal qty, BigDecimal unitPrice, BigDecimal amount) {}

    public record Field<T>(T value, double confidence) {
        static <T> Field<T> missing() { return new Field<>(null, 0); }
        public boolean present() { return value != null; }
    }

    public record Result(Field<String> invoiceNumber, Field<String> vendor, Field<LocalDate> invoiceDate,
                         Field<LocalDate> dueDate, Field<String> poNumber, Field<BigDecimal> subtotal,
                         Field<BigDecimal> tax, Field<BigDecimal> total, String currency,
                         List<LineItem> lines, List<String> issues) {
        /** Auto-post only when every required field is confident and the arithmetic reconciles. */
        public boolean straightThrough() {
            return issues.isEmpty() && invoiceNumber.confidence() >= 0.8 && total.confidence() >= 0.8 && invoiceDate.present();
        }
    }

    private static final Pattern INVOICE_NO = Pattern.compile("(?im)^\\s*(?:invoice|inv)\\s*(?:no\\.?|number|#)\\s*[:#]?\\s*([A-Z0-9][A-Z0-9-]{2,24})");
    private static final Pattern PO_NO = Pattern.compile("(?im)\\b(?:P\\.?O\\.?|purchase order)\\s*(?:no\\.?|number|#)?\\s*[:#]?\\s*([A-Z0-9-]{4,20})");
    private static final Pattern DATE_LABEL = Pattern.compile("(?im)^\\s*(invoice date|date|due date|payment due)\\s*[:]?\\s*(.+)$");
    private static final Pattern MONEY = Pattern.compile("([$€£])?\\s*(-?[0-9OIl][0-9OIl,. ]*[.,][0-9OIl]{2})\\b");
    private static final Pattern TOTALS = Pattern.compile("(?im)^\\s*(sub\\s*-?total|tax|vat|sales tax|total due|amount due|total)\\b[^0-9$€£\\-]*(.*)$");
    private static final Pattern LINE = Pattern.compile("^\\s*(.+?)\\s{2,}(\\d+(?:\\.\\d+)?)\\s+([$€£]?[0-9OIl,.]+[.,]\\d{2})\\s+([$€£]?[0-9OIl,.]+[.,]\\d{2})\\s*$");
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("M/d/uuuu"), DateTimeFormatter.ofPattern("MM/dd/uuuu"), DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US), DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US),
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.US));

    private InvoiceExtractor() {}

    public static Result extract(String ocr) {
        List<String> issues = new ArrayList<>();
        String text = ocr.replace(' ', ' ');

        Field<String> invoiceNo = Field.missing();
        Matcher m = INVOICE_NO.matcher(text);
        if (m.find()) {
            String raw = m.group(1);
            String fixed = fixDigitsInNumericRuns(raw);
            invoiceNo = new Field<>(fixed, fixed.equals(raw) ? 0.95 : 0.8);
        } else issues.add("invoice number not found");

        Field<String> po = Field.missing();
        Matcher pm = PO_NO.matcher(text);
        if (pm.find()) po = new Field<>(fixDigitsInNumericRuns(pm.group(1)), 0.85);

        Field<LocalDate> invDate = Field.missing(), dueDate = Field.missing();
        Matcher dm = DATE_LABEL.matcher(text);
        while (dm.find()) {
            Optional<LocalDate> d = parseDate(dm.group(2));
            if (d.isEmpty()) continue;
            String label = dm.group(1).toLowerCase();
            if (label.contains("due")) { if (!dueDate.present()) dueDate = new Field<>(d.get(), 0.9); }
            else if (!invDate.present()) invDate = new Field<>(d.get(), label.contains("invoice") ? 0.95 : 0.75);
        }
        if (!invDate.present()) issues.add("invoice date not found");
        if (invDate.present() && dueDate.present() && dueDate.value().isBefore(invDate.value())) issues.add("due date before invoice date");

        String currency = text.contains("€") ? "EUR" : text.contains("£") ? "GBP" : "USD";

        Field<BigDecimal> subtotal = Field.missing(), tax = Field.missing(), total = Field.missing();
        Matcher tm = TOTALS.matcher(text);
        while (tm.find()) {
            Optional<BigDecimal> amt = lastMoney(tm.group(2));
            if (amt.isEmpty()) continue;
            String label = tm.group(1).toLowerCase().replaceAll("\\s|-", "");
            if (label.startsWith("sub")) subtotal = new Field<>(amt.get(), 0.9);
            else if (label.contains("tax") || label.equals("vat")) tax = new Field<>(amt.get(), 0.9);
            else total = new Field<>(amt.get(), label.contains("due") ? 0.95 : 0.9); // last "total" line wins
        }
        if (!total.present()) issues.add("total not found");

        List<LineItem> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            Matcher lm = LINE.matcher(line);
            if (!lm.matches() || TOTALS.matcher(line).find()) continue;
            Optional<BigDecimal> unit = money(lm.group(3)), amount = money(lm.group(4));
            if (unit.isEmpty() || amount.isEmpty()) continue;
            BigDecimal qty = new BigDecimal(lm.group(2));
            lines.add(new LineItem(lm.group(1).trim(), qty, unit.get(), amount.get()));
            if (qty.multiply(unit.get()).setScale(2, RoundingMode.HALF_UP).compareTo(amount.get()) != 0)
                issues.add("line '" + lm.group(1).trim() + "': " + qty + " × " + unit.get() + " ≠ " + amount.get());
        }

        // Cross-checks: these catch most OCR digit errors, because a misread digit rarely keeps the sums consistent.
        if (!lines.isEmpty() && subtotal.present()) {
            BigDecimal sum = lines.stream().map(LineItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(subtotal.value()) != 0) {
                issues.add("line items sum to " + sum + " but subtotal is " + subtotal.value());
                subtotal = new Field<>(subtotal.value(), 0.4);
            }
        }
        if (total.present()) {
            BigDecimal base = subtotal.present() ? subtotal.value() : null;
            if (base != null) {
                BigDecimal expected = base.add(tax.present() ? tax.value() : BigDecimal.ZERO);
                if (expected.compareTo(total.value()) != 0) {
                    issues.add("subtotal + tax = " + expected + " but total is " + total.value());
                    total = new Field<>(total.value(), 0.4);
                }
            }
        }

        Field<String> vendor = vendor(text);
        return new Result(invoiceNo, vendor, invDate, dueDate, po, subtotal, tax, total, currency, lines, issues);
    }

    /** First non-label line near the top is usually the vendor's letterhead. */
    private static Field<String> vendor(String text) {
        for (String line : text.split("\\R")) {
            String l = line.trim();
            if (l.isEmpty() || l.toLowerCase().matches(".*\\b(invoice|bill to|ship to|date|page)\\b.*") || l.matches(".*\\d{3,}.*")) continue;
            return new Field<>(l, 0.6);
        }
        return Field.missing();
    }

    static Optional<LocalDate> parseDate(String raw) {
        String s = raw.trim().replaceAll("\\s{2,}.*$", "").replaceAll("[.]$", "");
        for (DateTimeFormatter f : DATE_FORMATS) {
            try { return Optional.of(LocalDate.parse(s, f)); } catch (DateTimeParseException ignored) { }
        }
        return Optional.empty();
    }

    static Optional<BigDecimal> lastMoney(String s) {
        Matcher m = MONEY.matcher(s);
        String last = null;
        while (m.find()) last = m.group(0);
        return last == null ? Optional.empty() : money(last);
    }

    /** "$1,234.50", "1.234,50" (EU), "l,2O4.5O" (OCR) → 1234.50 */
    static Optional<BigDecimal> money(String raw) {
        String s = raw.replaceAll("[$€£\\s]", "").replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1');
        boolean neg = s.startsWith("-");
        s = s.replace("-", "");
        if (!s.matches("[0-9.,]+")) return Optional.empty();
        int lastSep = Math.max(s.lastIndexOf('.'), s.lastIndexOf(','));
        if (lastSep < 0 || s.length() - lastSep - 1 != 2) return Optional.empty();
        String whole = s.substring(0, lastSep).replaceAll("[.,]", "");
        String cents = s.substring(lastSep + 1);
        if (whole.isEmpty()) whole = "0";
        BigDecimal v = new BigDecimal(whole + "." + cents);
        return Optional.of(neg ? v.negate() : v);
    }

    /** In mostly-digit tokens, O→0 and l/I→1 (letters inside "INV-2O23-0O15" are almost always zeros). */
    static String fixDigitsInNumericRuns(String token) {
        StringBuilder out = new StringBuilder();
        for (String part : token.split("(?<=-)|(?=-)")) {
            long digits = part.chars().filter(Character::isDigit).count();
            out.append(digits * 2 >= part.length() ? part.replace('O', '0').replace('I', '1').replace('l', '1') : part);
        }
        return out.toString();
    }
}
