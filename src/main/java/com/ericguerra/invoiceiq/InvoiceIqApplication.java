package com.ericguerra.invoiceiq;

import com.ericguerra.invoiceiq.extract.InvoiceExtractor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class InvoiceIqApplication {
    public static void main(String[] args) {
        SpringApplication.run(InvoiceIqApplication.class, args);
    }

    /** Debug endpoint for the review UI: re-run extraction on corrected OCR text. */
    @PostMapping(value = "/api/extract", consumes = "text/plain")
    public InvoiceExtractor.Result extract(@RequestBody String ocrText) {
        return InvoiceExtractor.extract(ocrText);
    }
}
