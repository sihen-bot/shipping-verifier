package com.hackathon.shippingverifier;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure Java comparison. AI supplies literal values and evidence, not verdicts. */
public final class ShipmentComparison {
    public static final List<String> FIELDS = List.of("shipper", "consignee", "notify_party",
        "port_of_loading", "port_of_discharge", "container_count", "gross_weight_kg");
    public record Value(String value, String evidence, String issue) {}
    public record Row(String field, String si, String bl, String si_evidence, String bl_evidence,
                      String status, String detail) {}
    public record Result(String status, String summary, List<Row> fields, List<String> defect_fields) {}

    private ShipmentComparison() {}

    public static String literal(String value) {
        return value == null ? "" : value.replaceAll("[\\s\\p{Z}]+", " ").trim();
    }

    public static Value validate(String value, String evidence, String state, String source) {
        if (!"PRESENT".equals(state) || value == null || value.isBlank())
            return new Value(value, evidence, "Value is missing or uncertain in the source.");
        String raw = literal(value);
        if (raw.matches("(?i)(?:n/?a|unknown|not (?:provided|available)|tbc|tba|null|-+)"))
            return new Value(value, evidence, "The source has a placeholder, not a usable value.");
        if (evidence == null || literal(evidence).isEmpty()
            || !literal(source).contains(literal(evidence)) || !literal(evidence).contains(raw))
            return new Value(value, evidence, "Extracted value could not be verified against quoted source text.");
        return new Value(value, evidence, null);
    }

    public static Result compare(Map<String, Value> si, Map<String, Value> bl) {
        List<Row> rows = new ArrayList<>();
        List<String> defects = new ArrayList<>();
        boolean review = false;
        for (String field : FIELDS) {
            Value a = si.getOrDefault(field, new Value(null, null, "Missing field."));
            Value b = bl.getOrDefault(field, new Value(null, null, "Missing field."));
            String state;
            String detail;
            if (a.issue() != null || b.issue() != null) {
                state = "NEEDS_REVIEW";
                detail = (a.issue() == null ? "" : "SI: " + a.issue() + " ")
                    + (b.issue() == null ? "" : "BL: " + b.issue());
            } else {
                try {
                    boolean match;
                    if (field.equals("container_count")) match = count(a.value()).compareTo(count(b.value())) == 0;
                    else if (field.equals("gross_weight_kg")) match = weightWithEvidence(a).compareTo(weightWithEvidence(b)) == 0;
                    else if (field.equals("shipper") || field.equals("consignee") || field.equals("notify_party"))
                        match = text(a.value().replace('|', ' ')).equals(text(b.value().replace('|', ' ')));
                    else match = text(a.value()).equals(text(b.value()));
                    state = match ? "MATCH" : "MISMATCH";
                    detail = match ? "Values agree after basic formatting normalization."
                        : "Values differ. Check the source quotations before finalizing the draft.";
                    if (!match && (field.equals("port_of_loading") || field.equals("port_of_discharge"))) {
                        String codeA = portCode(a.value()), codeB = portCode(b.value());
                        String nameA = text(a.value().replaceAll("\\([A-Za-z]{5}\\)", ""));
                        String nameB = text(b.value().replaceAll("\\([A-Za-z]{5}\\)", ""));
                        if (nameA.equals(nameB) && (codeA.isEmpty() || codeB.isEmpty() || codeA.equals(codeB))) {
                            state = "MATCH";
                            detail = "Port names agree; an optional matching location code is omitted on one side.";
                        } else if (!codeA.isEmpty() && codeA.equals(codeB)) {
                            state = "NEEDS_REVIEW";
                            detail = "The location code agrees but port wording differs. Confirm whether this is an alias.";
                        }
                    }
                } catch (IllegalArgumentException invalid) {
                    state = "NEEDS_REVIEW";
                    detail = invalid.getMessage();
                }
            }
            if (state.equals("NEEDS_REVIEW")) review = true;
            if (state.equals("MISMATCH")) defects.add(field);
            rows.add(new Row(field, a.value(), b.value(), a.evidence(), b.evidence(), state, detail.trim()));
        }
        String status = review ? "NEEDS_REVIEW" : defects.isEmpty() ? "OK" : "MISMATCH";
        String summary = review ? "Human review required. Do not approve this draft yet."
            : defects.isEmpty() ? "No mismatch detected." : defects.size() + " mismatched field(s) detected.";
        return new Result(status, summary, List.copyOf(rows), List.copyOf(defects));
    }

    private static String text(String value) {
        return literal(Normalizer.normalize(value, Normalizer.Form.NFKC)
            .toUpperCase(Locale.ROOT).replaceAll("[.,;]", " "));
    }

    private static String portCode(String value) {
        Matcher m = Pattern.compile("\\(([A-Z]{5})\\)").matcher(value.toUpperCase(Locale.ROOT));
        return m.find() ? m.group(1) : "";
    }

    static BigDecimal count(String raw) {
        String value = literal(raw).toUpperCase(Locale.ROOT);
        if (value.matches("\\d+(?:\\s*(?:CONTAINERS?|CNTRS?|CTRS?))?"))
            return positive(new BigDecimal(value.replaceAll("[^0-9]", "")), "container count");
        Pattern groups = Pattern.compile("(\\d+)\\s*[X×]\\s*(?:20|40|45)\\s*['’]?\\s*(?:HC|HQ|GP|DC|DV|RF|RH|OT|FR|FT|FCL)?");
        Matcher m = groups.matcher(value);
        BigDecimal sum = BigDecimal.ZERO;
        int last = 0;
        boolean found = false;
        while (m.find()) {
            if (!value.substring(last, m.start()).matches("[\\s+,;]*")) throw invalidCount();
            sum = sum.add(positive(new BigDecimal(m.group(1)), "container count"));
            last = m.end(); found = true;
        }
        if (!found || !value.substring(last).matches("[\\s+,;]*")) throw invalidCount();
        return positive(sum, "container count");
    }

    private static IllegalArgumentException invalidCount() {
        return new IllegalArgumentException("Container count format is ambiguous or unsupported. Confirm the count manually.");
    }

    // A bare numeric value is not inherently kilograms. Check its own quoted label.
    static BigDecimal weightWithEvidence(Value field) {
        String raw = literal(field.value()).toUpperCase(Locale.ROOT);
        if (raw.matches(".*[A-Z].*")) return weight(raw);
        String evidence = literal(field.evidence()).toUpperCase(Locale.ROOT);
        Matcher label = Pattern.compile(
            "(?:TOTAL[ ]+)?GROSS[ ]*(?:WEIGHT|WT)[ ]*[:(\\[]?[ ]*(KG|KGS|KILOGRAMS?|MT|METRIC TONS?|TONNES?)(?=[^A-Z]|$)"
        ).matcher(evidence);
        if (!label.find()) throw new IllegalArgumentException(
            "Gross weight has no confirmed unit in its own value or quoted label. Human review is required.");
        String unit = label.group(1);
        if (label.find()) throw new IllegalArgumentException(
            "Multiple gross-weight labels were quoted. Confirm the total and unit manually.");
        return weight(raw + " " + unit);
    }

    static boolean instructionHeading(String title) {
        return literal(title).toUpperCase(Locale.ROOT).matches(".*\\bINSTRUCTIONS?\\b.*");
    }

    static BigDecimal weight(String raw) {
        String value = literal(raw).toUpperCase(Locale.ROOT);
        Matcher m = Pattern.compile("^([0-9][0-9 ,.]*)\\s*(KG|KGS|KILOGRAMS?|MT|METRIC TONS?|TONNES?)?$").matcher(value);
        if (!m.matches()) throw invalidWeight();
        String number = m.group(1).trim();
        String unit = m.group(2);
        boolean tonnes = unit != null && (unit.equals("MT") || unit.startsWith("METRIC") || unit.startsWith("TONNE"));
        if (!number.matches("(?:\\d+|\\d{1,3}(?:,\\d{3})+|\\d{1,3}(?: \\d{3})+)(?:\\.\\d+)?")) throw invalidWeight();
        if (!tonnes && number.matches("\\d{1,3}\\.\\d{3}")) throw invalidWeight();
        BigDecimal result = new BigDecimal(number.replace(",", "").replace(" ", ""));
        if (tonnes) result = result.multiply(BigDecimal.valueOf(1000));
        return positive(result, "gross weight");
    }

    private static IllegalArgumentException invalidWeight() {
        return new IllegalArgumentException("Gross weight number format or unit is ambiguous or unsupported. Confirm kilograms manually.");
    }

    private static BigDecimal positive(BigDecimal value, String field) {
        if (value.signum() <= 0) throw new IllegalArgumentException("The " + field + " must be positive; review the source.");
        return value;
    }
}
