package com.hackathon.shippingverifier;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Local prototype review log. Reviewer name is self-reported, not authenticated. */
@RestController
public class ReviewController {
    private final JsonMapper mapper;
    private final DocumentReader reader = new DocumentReader();
    private final DurableData data;
    public ReviewController(JsonMapper mapper, DurableData data) { this.mapper = mapper; this.data = data; }
    public record Entry(String value, String evidence) {}
    public record Submission(String reviewer, String note, int siIndex, int blIndex,
        String siHash, String blHash, Map<String,Entry> si, Map<String,Entry> bl, String aiReportId) {
        public Submission(String reviewer,String note,int siIndex,int blIndex,String siHash,String blHash,
                Map<String,Entry> si,Map<String,Entry> bl) {
            this(reviewer,note,siIndex,blIndex,siHash,blHash,si,bl,null);
        }
    }
    private record Source(int index, String path, String text, String hash, String error) {}

    @GetMapping("/api/emails/{emailId}/review")
    public ResponseEntity<?> load(@PathVariable("emailId") String id) {
        try {
            var sources=sources(id);
            List<JsonNode> history=new ArrayList<>();
            Path folder=Path.of("data","reviews",id);
            if(Files.isDirectory(folder)) try(var files=Files.list(folder)) {
                for(Path p:files.filter(f->f.getFileName().toString().matches("[0-9a-f-]+\\.json"))
                        .toList()) {
                    history.add(mapper.readTree(Files.readString(p)));
                }
            }
            history.sort(Comparator.comparing((JsonNode n)->n.path("saved_at").stringValue()).reversed());
            Map<String,Object> response=new LinkedHashMap<>();
            response.put("email_id",id);response.put("documents",sources);response.put("history",history);
            JsonNode ai=AiReportStore.latest(mapper,id);
            response.put("ai_report",ai);response.put("ai_source_matches",baselineMatches(id,ai,sources));
            return ResponseEntity.ok(response);
        } catch(ReviewFailure e){return failure(e.status,e.getMessage());}
        catch(Exception e){return failure(500,"Could not load review data.");}
    }
    @PostMapping("/api/emails/{emailId}/review")
    public ResponseEntity<?> save(@PathVariable("emailId") String id,@RequestBody Submission form) {
        try {
            if(form==null || blank(form.reviewer()) || form.reviewer().length()>100
                || blank(form.note()) || form.note().length()>2000)
                return failure(400,"Enter a reviewer name (up to 100 characters) and review note (up to 2,000).");
            var sources=sources(id);
            if(form.siIndex()==form.blIndex()) return failure(400,"Choose different SI and BL attachments.");
            Source si=source(sources,form.siIndex()), bl=source(sources,form.blIndex());
            if(si.error()!=null || bl.error()!=null) return failure(422,"Both documents need readable source text. OCR/manual transcription is not supported by this screen yet.");
            if(!si.hash().equals(form.siHash()) || !bl.hash().equals(form.blHash()))
                return failure(409,"Source text changed. Reload this review before saving.");
            JsonNode baseline=null;
            if(!blank(form.aiReportId())) {
                try { baseline=AiReportStore.read(mapper,id,form.aiReportId()); }
                catch(IllegalArgumentException e){return failure(400,"Invalid AI report reference.");}
                if(!baselineMatches(id,baseline,sources))return failure(409,"The selected AI report no longer matches this email, its documents or current rules. Load a new comparison or start a manual review.");
            }
            var siValues=validate(form.si(),si.text());var blValues=validate(form.bl(),bl.text());
            var result=ShipmentComparison.compare(siValues,blValues);
            String reviewId=UUID.randomUUID().toString();
            Map<String,Object> record=new LinkedHashMap<>();
            record.put("review_id",reviewId);record.put("email_id",id);record.put("saved_at",Instant.now().toString());
            record.put("source","HUMAN_REVIEW");record.put("reviewer",form.reviewer().trim());
            record.put("reviewer_identity_verified",false);record.put("note",form.note().trim());
            record.put("si_document",Map.of("index",si.index(),"path",si.path(),"text_sha256",si.hash()));
            record.put("bl_document",Map.of("index",bl.index(),"path",bl.path(),"text_sha256",bl.hash()));
            record.put("submitted",form);record.put("result",result);
            record.put("approved",false);
            record.put("ai_baseline",baseline);
            Path folder=Path.of("data","reviews",id);Files.createDirectories(folder);
            data.write(folder.resolve(reviewId+".json"),mapper.writeValueAsString(record).getBytes(StandardCharsets.UTF_8),true);
            return ResponseEntity.ok(record);
        } catch(ReviewFailure e){return failure(e.status,e.getMessage());}
        catch(Exception e){return failure(500,"Could not save the review. Check data storage availability.");}
    }
    private boolean baselineMatches(String id,JsonNode baseline,List<Source> sources)throws Exception {
        if(baseline==null || !AiReportStore.RULES.equals(baseline.path("rules_version").asString("")))return false;
        if(!id.equals(baseline.path("email_id").asString("")))return false;
        String current=Files.readString(Path.of("data","inbox",id+".json"));
        if(!AiReportStore.hash(current).equals(baseline.path("email_sha256").asString("")))return false;
        JsonNode docs=baseline.path("documents");
        if(!docs.isArray() || docs.size()!=2)return false;
        for(JsonNode doc:docs){
            if(!doc.path("index").isIntegralNumber())return false;
            int index=doc.path("index").intValue();
            Source source=sources.stream().filter(s->s.index()==index).findFirst().orElse(null);
            if(source==null || source.error()!=null || !source.hash().equals(doc.path("text_sha256").asString("")))return false;
        }
        return baseline.path("fields").isArray() && baseline.path("fields").size()==7;
    }
    private Map<String,ShipmentComparison.Value> validate(Map<String,Entry> entries,String text) {
        if(entries==null || !entries.keySet().equals(new HashSet<>(ShipmentComparison.FIELDS)))
            throw new ReviewFailure(400,"Provide exactly the seven shipment fields on each side.");
        Map<String,ShipmentComparison.Value> out=new LinkedHashMap<>();
        for(String field:ShipmentComparison.FIELDS){
            Entry e=entries.get(field);
            if(e==null || (e.value()!=null && e.value().length()>5000) || (e.evidence()!=null && e.evidence().length()>10000))
                throw new ReviewFailure(400,"A field value or quotation is too long or invalid.");
            out.put(field,ShipmentComparison.validate(e.value(),e.evidence(),blank(e.value())?"UNCERTAIN":"PRESENT",text));
        }
        return out;
    }
    private Source source(List<Source> sources,int index){return sources.stream().filter(s->s.index()==index).findFirst().orElseThrow(()->new ReviewFailure(400,"Invalid attachment selection."));}
    private List<Source> sources(String id)throws Exception {
        if(!id.matches("email_\\d+"))throw new ReviewFailure(400,"Invalid email ID.");
        Path data=Path.of("data").toAbsolutePath().normalize(), file=data.resolve("inbox").resolve(id+".json");
        if(!Files.isRegularFile(file))throw new ReviewFailure(404,"Email not found.");
        JsonNode refs=mapper.readTree(Files.readString(file)).path("attachments");
        if(!refs.isArray())return List.of();
        if(refs.size()>20)throw new ReviewFailure(422,"Too many attachments for this review screen.");
        List<Source> out=new ArrayList<>();Path root=data.resolve("attachments");
        for(int i=0;i<refs.size();i++){
            if(!refs.get(i).isString())throw new ReviewFailure(400,"Invalid attachment reference.");
            String listed=refs.get(i).stringValue();Path attachment=data.resolve(listed).normalize();
            if(!attachment.startsWith(root))throw new ReviewFailure(400,"Invalid attachment path.");
            if(!Files.isRegularFile(attachment)){out.add(new Source(i,listed,null,null,"File is missing."));continue;}
            if(!attachment.toRealPath().startsWith(root.toRealPath()))throw new ReviewFailure(400,"Invalid attachment path.");
            try{
                String text=reader.read(attachment);
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
                out.add(new Source(i,listed,text,hash,null));
            }catch(DocumentReader.ReadFailure e){out.add(new Source(i,listed,null,null,e.getMessage()));}
        }
        return out;
    }
    private boolean blank(String s){return s==null||s.isBlank();}
    private ResponseEntity<?> failure(int status,String text){return ResponseEntity.status(status).body(Map.of("error",text));}
    private static class ReviewFailure extends RuntimeException {
        private static final long serialVersionUID=1L;final int status;
        ReviewFailure(int status,String message){super(message);this.status=status;}
    }
}
