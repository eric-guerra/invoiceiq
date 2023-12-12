package com.ericguerra.invoiceiq;

import com.ericguerra.invoiceiq.extract.InvoiceExtractor;
import com.ericguerra.invoiceiq.extract.InvoiceExtractor.Result;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceExtractorTest {
    static String sample(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/samples", name));
    }

    @Test
    void cleanInvoiceGoesStraightThrough() throws Exception {
        Result r = InvoiceExtractor.extract(sample("acme-clean.txt"));
        assertThat(r.invoiceNumber().value()).isEqualTo("INV-2023-0415");
        assertThat(r.vendor().value()).isEqualTo("Acme Packaging Supply Co.");
        assertThat(r.invoiceDate().value()).isEqualTo(LocalDate.of(2023, 4, 3));
        assertThat(r.dueDate().value()).isEqualTo(LocalDate.of(2023, 5, 3));
        assertThat(r.poNumber().value()).isEqualTo("PO-77812");
        assertThat(r.lines()).hasSize(3);
        assertThat(r.subtotal().value()).isEqualByComparingTo("1180.00");
        assertThat(r.tax().value()).isEqualByComparingTo("94.40");
        assertThat(r.total().value()).isEqualByComparingTo("1274.40");
        assertThat(r.issues()).isEmpty();
        assertThat(r.straightThrough()).isTrue();
    }

    @Test
    void ocrNoiseInNumbersIsRepaired() throws Exception {
        Result r = InvoiceExtractor.extract(sample("acme-noisy.txt"));
        assertThat(r.invoiceNumber().value()).isEqualTo("INV-2023-0415");
        assertThat(r.total().value()).isEqualByComparingTo("1274.40");
        assertThat(r.issues()).isEmpty();
    }

    @Test
    void misreadDigitIsCaughtByCrossChecksAndRoutedToReview() throws Exception {
        // OCR read 250.00 as 750.00 on one line: the sums no longer reconcile.
        String bad = sample("acme-clean.txt").replace("$25.00   $250.00", "$25.00   $750.00");
        Result r = InvoiceExtractor.extract(bad);
        assertThat(r.issues()).anyMatch(i -> i.contains("≠")).anyMatch(i -> i.contains("line items sum to"));
        assertThat(r.straightThrough()).isFalse();
    }

    @Test
    void euroFormatting() {
        assertThat(InvoiceExtractor.extract("Invoice No: RE-88231\nInvoice date: 2023-03-14\nTotal: €1.234,50\n").total().value())
                .isEqualByComparingTo(new BigDecimal("1234.50"));
    }

    @Test
    void missingFieldsAreReported() {
        Result r = InvoiceExtractor.extract("Thank you for your business\n");
        assertThat(r.issues()).contains("invoice number not found", "invoice date not found", "total not found");
        assertThat(r.straightThrough()).isFalse();
    }
}
