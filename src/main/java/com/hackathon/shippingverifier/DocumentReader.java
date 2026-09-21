package com.hackathon.shippingverifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;

/** Local text extraction. No AI calls, formula execution or OCR. */
public class DocumentReader {
    private static final int MAX_CHARS = 100_000;
    public String read(Path file) throws IOException {
        if (Files.size(file) > 10_000_000) throw new ReadFailure("too_large", "File exceeds the 10 MB prototype limit.");
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String text;
        try {
            if (name.endsWith(".txt")) {
                if (Files.size(file) > 100_000) throw new ReadFailure("too_large", "Text exceeds the 100 KB limit.");
                text = Files.readString(file);
            } else if (name.endsWith(".pdf")) text = pdf(file);
            else if (name.endsWith(".docx")) text = word(file);
            else if (name.endsWith(".xlsx")) text = excel(file);
            else throw new ReadFailure("unsupported_format", "Supported formats: TXT, PDF, DOCX and XLSX.");
        } catch (ReadFailure expected) { throw expected; }
        catch (Exception unreadable) { throw new ReadFailure("unreadable", "File is damaged, protected, or could not be decoded."); }
        if (text.isBlank()) throw new ReadFailure("unreadable", "No readable text was found. Image-only documents need OCR or manual review.");
        if (text.length() > MAX_CHARS) throw new ReadFailure("too_large", "Extracted text exceeds 100,000 characters. Nothing was truncated or compared.");
        return text;
    }
    private String pdf(Path file) throws IOException {
        try (var doc = Loader.loadPDF(file.toFile())) {
            if (!doc.getCurrentAccessPermission().canExtractContent()) throw new ReadFailure("unreadable", "PDF does not permit text extraction.");
            if (doc.getNumberOfPages() > 40) throw new ReadFailure("too_large", "PDF exceeds 40 pages.");
            if (doc.getDocumentCatalog().getAcroForm() != null)
                throw new ReadFailure("needs_review", "PDF form fields need manual review in this version.");
            for (var page : doc.getPages()) {
                if (hasImage(page.getResources(), new java.util.HashSet<>()))
                    throw new ReadFailure("needs_review", "PDF includes images that this text reader cannot interpret. Review the original or use OCR.");
            }
            StringBuilder out = new StringBuilder();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                stripper.setStartPage(i); stripper.setEndPage(i);
                String page = stripper.getText(doc);
                if (page.isBlank()) throw new ReadFailure("ocr_required", "PDF page " + i + " has no readable text. OCR or manual review is required.");
                append(out, "[Page " + i + "]\n" + page + "\n");
            }
            return out.toString();
        }
    }
    private boolean hasImage(org.apache.pdfbox.pdmodel.PDResources resources,
            java.util.Set<org.apache.pdfbox.cos.COSDictionary> visited) throws IOException {
        if (resources == null || !visited.add(resources.getCOSObject())) return false;
        for (var name : resources.getXObjectNames()) {
            var object = resources.getXObject(name);
            if (object instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) return true;
            if (object instanceof org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form
                && hasImage(form.getResources(), visited)) return true;
        }
        return false;
    }
    private String word(Path file) throws IOException {
        try (var in = Files.newInputStream(file); var doc = new XWPFDocument(in);
             var extractor = new XWPFWordExtractor(doc)) {
            if (!doc.getAllPictures().isEmpty()) throw new ReadFailure("needs_review", "Word document includes images. Review them before comparison; this reader cannot read image text.");
            return extractor.getText();
        }
    }
    private String excel(Path file) throws IOException {
        try (var in = Files.newInputStream(file); var book = new XSSFWorkbook(in)) {
            if (book.getNumberOfSheets() > 20) throw new ReadFailure("too_large", "Workbook exceeds 20 sheets.");
            if (!book.getAllPictures().isEmpty()) throw new ReadFailure("needs_review", "Workbook includes images requiring manual review.");
            DataFormatter format = new DataFormatter(Locale.US);
            StringBuilder out = new StringBuilder();
            int cells = 0;
            int values = 0;
            for (int s = 0; s < book.getNumberOfSheets(); s++) {
                if (book.isSheetHidden(s) || book.isSheetVeryHidden(s))
                    throw new ReadFailure("needs_review", "Workbook includes hidden sheets. Confirm the intended document before comparison.");
                Sheet sheet = book.getSheetAt(s);
                append(out, "[Sheet: " + sheet.getSheetName() + "]\n");
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        if (++cells > 20_000) throw new ReadFailure("too_large", "Workbook exceeds 20,000 cells.");
                        if (cell.getCellType() == CellType.FORMULA || cell.getCellType() == CellType.ERROR)
                            throw new ReadFailure("needs_review", "Workbook contains a formula or error at " + sheet.getSheetName() + "!" + cell.getAddress() + ". Confirm its value manually.");
                        String value = format.formatCellValue(cell);
                        if (value.isBlank()) continue;
                        values++;
                        if (row.getZeroHeight() || sheet.isColumnHidden(cell.getColumnIndex()))
                            throw new ReadFailure("needs_review", "Workbook has hidden values requiring manual review.");
                        append(out, cell.getAddress() + ": " + value + "\n");
                    }
                    append(out, "\n");
                }
            }
            // Sheet names alone are not document content.
            if (values == 0) throw new ReadFailure("unreadable", "Workbook contains no cells.");
            return out.toString();
        }
    }
    private void append(StringBuilder out, String value) throws ReadFailure {
        if (out.length() + value.length() > MAX_CHARS) throw new ReadFailure("too_large", "Extracted text exceeds 100,000 characters.");
        out.append(value);
    }
    public static class ReadFailure extends IOException {
        private static final long serialVersionUID = 1L;
        private final String reason;
        public ReadFailure(String reason, String message) { super(message); this.reason = reason; }
        public String reason() { return reason; }
    }
}
