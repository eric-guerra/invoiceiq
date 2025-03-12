# InvoiceIQ

Portfolio reference implementation of a 2023 project, built to show an accounts-payable intake pipeline. It turns emailed or scanned supplier invoices into structured data, auto-posts the clean ones, and routes the rest to a human review queue.

**Stack:** Java 17, Spring Boot 3, RabbitMQ (quorum queues, DLX), Redis, Tesseract 5 via Tess4J, PDFBox.

## Flow
```
documents.received ──▶ OCR worker ──▶ exchange "invoices"
   (quorum, DLX)          │             ├─ invoice.extracted → ERP posting (straight-through)
                          │             └─ invoice.review    → AP clerk UI
                          └─ unreadable after 3 retries → documents.received.parked
```
- **Text first, OCR second:** digital PDFs are read from their text layer (exact and fast). Scanned pages are OCR'd at 300 dpi grayscale, with `preserve_interword_spaces` so table columns survive.
- **Duplicate suppression:** the SHA-256 of the file content is checked with Redis `SETNX` and a 90-day TTL, which catches the same PDF emailed twice. The key is released if processing fails, so a retry can still happen.

## Extraction (`InvoiceExtractor`)
- **Fields:** invoice number, vendor, invoice and due dates (several formats), PO number, subtotal, tax, total, currency, and line items. Each field has a confidence score.
- **OCR repair:** letter/digit confusions inside numbers are fixed (`INV-2O23-O415` → `INV-2023-0415`, `$l,274.4O` → `1274.40`), and EU formats are handled (`€1.234,50`).
- **Cross-checks are what make straight-through posting safe:**
  - qty × unit price = amount on every line.
  - Line amounts sum to the subtotal.
  - Subtotal + tax = total.
  - The due date is not before the invoice date.
  
  A single misread digit almost never keeps all of these consistent. When any check fails, the invoice goes to review instead of being posted.

## Run and test
```bash
docker compose up -d
mvn test            # extractor: clean, OCR-noisy, misread digit → review, EU format, missing fields
mvn spring-boot:run # needs tesseract installed (apt install tesseract-ocr)
```
