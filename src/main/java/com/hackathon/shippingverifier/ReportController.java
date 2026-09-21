package com.hackathon.shippingverifier;

import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/** Audit export only; never fabricates a complete evaluator submission. */
@RestController
public class ReportController {
    private final ReviewController reviews;
    private final JsonMapper mapper;
    public ReportController(ReviewController reviews, JsonMapper mapper) {this.reviews=reviews;this.mapper=mapper;}
    @GetMapping("/api/emails/{emailId}/report")
    public ResponseEntity<?> export(@PathVariable("emailId") String id) {
        if(!id.matches("email_\\d+"))return ResponseEntity.badRequest().body(Map.of("error","Invalid email ID."));
        var loaded=reviews.load(id);
        if(!loaded.getStatusCode().is2xxSuccessful())return loaded;
        var context=mapper.valueToTree(loaded.getBody());
        if(context.path("ai_report").isNull() && context.path("history").isEmpty())
            return ResponseEntity.status(404).body(Map.of("error","No saved AI comparison or human review exists for this email."));
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("format","shipping-verifier-audit-v1");report.put("email_id",id);
        report.put("exported_at",java.time.Instant.now().toString());
        report.put("purpose","Audit report; not the hackathon submission.json format.");
        report.put("latest_ai_comparison",context.path("ai_report"));
        report.put("ai_sources_match_now",context.path("ai_source_matches"));
        report.put("human_review_history",context.path("history"));
        report.put("approval_issued",false);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
            .header("Content-Disposition","attachment; filename=\""+id+"-audit.json\"")
            .header("Cache-Control","no-store")
            .body(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
