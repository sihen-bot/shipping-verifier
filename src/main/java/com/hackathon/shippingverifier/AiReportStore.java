package com.hackathon.shippingverifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Immutable completed AI comparisons for reviewer handoff. */
public final class AiReportStore {
    public static final String RULES = "comparison-rules-v2";
    private AiReportStore() {}
    public static String hash(String text) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("Cannot hash source text",e);}
    }
    private static Path folder(String id) {
        if(!id.matches("email_\\d+"))throw new IllegalArgumentException("Invalid email ID");
        return Path.of("data","ai-reports",id);
    }
    public static void save(JsonMapper mapper,String id,String emailText,Map<String,Object> report)throws IOException {
        String reportId=UUID.randomUUID().toString();
        report.put("ai_report_id",reportId);report.put("saved_at",Instant.now().toString());
        report.put("rules_version",RULES);report.put("email_sha256",hash(emailText));
        Path dir=folder(id);Files.createDirectories(dir);
        Path temporary=Files.createTempFile(dir,"pending-",".tmp");
        try {
            Files.writeString(temporary,mapper.writeValueAsString(report));
            try {Files.move(temporary,dir.resolve(reportId+".json"),StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary,dir.resolve(reportId+".json"));}
        }finally{Files.deleteIfExists(temporary);}
    }
    public static JsonNode read(JsonMapper mapper,String id,String reportId)throws IOException {
        if(reportId==null || !reportId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("Invalid AI report ID");
        Path p=folder(id).resolve(reportId+".json");
        return Files.isRegularFile(p)?mapper.readTree(Files.readString(p)):null;
    }
    public static JsonNode latest(JsonMapper mapper,String id)throws IOException {
        Path dir=folder(id);if(!Files.isDirectory(dir))return null;
        JsonNode latest=null;
        try(var files=Files.list(dir)){
            for(Path p:files.filter(f->f.getFileName().toString().matches("[0-9a-f-]+\\.json")).toList()){
                JsonNode candidate=mapper.readTree(Files.readString(p));
                if(!candidate.path("saved_at").isString())continue;
                if(latest==null || candidate.path("saved_at").stringValue().compareTo(latest.path("saved_at").stringValue())>0)latest=candidate;
            }
        }
        return latest;
    }
}
