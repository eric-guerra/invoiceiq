package com.ericguerra.invoiceiq.pipeline;

import com.ericguerra.invoiceiq.extract.InvoiceExtractor;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * documents.received → [OCR worker] → invoices.extracted (straight-through) | invoices.review (human queue)
 * Poison documents go to the DLX after Spring AMQP's retry interceptor gives up.
 */
@Configuration
@Profile("!test")
class Topology {
    static final String IN = "documents.received", OUT = "invoices", DLX = "documents.dlx";

    @Bean Queue inbound() { return QueueBuilder.durable(IN).deadLetterExchange(DLX).quorum().build(); }
    @Bean DirectExchange deadLetters() { return new DirectExchange(DLX); }
    @Bean Queue parked() { return QueueBuilder.durable(IN + ".parked").build(); }
    @Bean Binding parkedBinding() { return BindingBuilder.bind(parked()).to(deadLetters()).with(IN); }
    @Bean TopicExchange results() { return new TopicExchange(OUT); }
    @Bean Queue extracted() { return QueueBuilder.durable("invoices.extracted").build(); }
    @Bean Queue review() { return QueueBuilder.durable("invoices.review").build(); }
    @Bean Binding b1() { return BindingBuilder.bind(extracted()).to(results()).with("invoice.extracted"); }
    @Bean Binding b2() { return BindingBuilder.bind(review()).to(results()).with("invoice.review"); }
    @Bean MessageConverter json() { return new Jackson2JsonMessageConverter(); }
}

@Component
@Profile("!test")
class OcrWorker {
    private static final Logger log = LoggerFactory.getLogger(OcrWorker.class);
    record DocumentReceived(String documentId, String path, String supplierId) {}
    record Extracted(String documentId, String sha256, InvoiceExtractor.Result result, boolean straightThrough, String ocrSource) {}

    private final RabbitTemplate rabbit;
    private final StringRedisTemplate redis;
    private final String tessdata;

    OcrWorker(RabbitTemplate rabbit, StringRedisTemplate redis, @Value("${invoiceiq.tessdata:/usr/share/tesseract-ocr/5/tessdata}") String tessdata) {
        this.rabbit = rabbit;
        this.redis = redis;
        this.tessdata = tessdata;
    }

    @RabbitListener(queues = Topology.IN, concurrency = "2-4")
    void onDocument(DocumentReceived msg) throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of(msg.path()));
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

        // Suppliers email the same PDF twice all the time; dedupe on content for 90 days.
        Boolean first = redis.opsForValue().setIfAbsent("invoiceiq:seen:" + sha, msg.documentId(), Duration.ofDays(90));
        if (Boolean.FALSE.equals(first)) {
            log.info("duplicate document {} (same content as {})", msg.documentId(), redis.opsForValue().get("invoiceiq:seen:" + sha));
            return;
        }
        try {
            String[] source = new String[1];
            String text = textOf(bytes, msg.path(), source);
            InvoiceExtractor.Result r = InvoiceExtractor.extract(text);
            boolean stp = r.straightThrough();
            rabbit.convertAndSend(Topology.OUT, stp ? "invoice.extracted" : "invoice.review", new Extracted(msg.documentId(), sha, r, stp, source[0]));
            log.info("document {} → {} ({} issues)", msg.documentId(), stp ? "extracted" : "review", r.issues().size());
        } catch (IOException | TesseractException e) {
            redis.delete("invoiceiq:seen:" + sha); // allow a retry to process it
            throw new AmqpRejectAndDontRequeueException("unreadable document " + msg.documentId(), e);
        }
    }

    /** Digital PDFs have a text layer, so skip OCR (faster and exact); scans get 300 dpi OCR. */
    String textOf(byte[] bytes, String path, String[] source) throws IOException, TesseractException {
        Tesseract t = new Tesseract();
        t.setDatapath(tessdata);
        t.setLanguage("eng");
        t.setVariable("preserve_interword_spaces", "1"); // keeps column gaps, which the line-item parser relies on
        if (path.toLowerCase().endsWith(".pdf")) {
            try (PDDocument doc = PDDocument.load(bytes)) {
                String layer = new PDFTextStripper().getText(doc);
                if (layer.replaceAll("\\s", "").length() > 50) { source[0] = "pdf-text"; return layer; }
                StringBuilder sb = new StringBuilder();
                PDFRenderer renderer = new PDFRenderer(doc);
                for (int i = 0; i < Math.min(doc.getNumberOfPages(), 5); i++) {
                    BufferedImage img = renderer.renderImageWithDPI(i, 300, ImageType.GRAY);
                    sb.append(t.doOCR(img)).append('\n');
                }
                source[0] = "ocr";
                return sb.toString();
            }
        }
        source[0] = "ocr";
        return t.doOCR(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes)));
    }
}
