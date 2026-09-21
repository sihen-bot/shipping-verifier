package com.hackathon.shippingverifier;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** Protected by the cloud login and CSRF filter, like all non-health routes. */
@RestController @Profile("cloud")
public class DataAdminController {
    private final DurableData data;
    private final JsonMapper mapper;
    public DataAdminController(DurableData data, JsonMapper mapper) { this.data=data; this.mapper=mapper; }
    @GetMapping("/api/admin/storage")
    public ResponseEntity<?> status() {
        try { return ResponseEntity.ok().header("Cache-Control","no-store").body(data.status()); }
        catch(IOException e) { return failure(503,e.getMessage()); }
    }
    @PostMapping(value="/api/admin/import", consumes="multipart/form-data")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        if(file.isEmpty() || file.getSize()>30L*1024*1024) return failure(400,"Choose a data ZIP smaller than 30 MB.");
        try (InputStream input=file.getInputStream()) {
            Map<String,byte[]> files=validateZip(input,mapper);
            data.importFiles(files);
            return ResponseEntity.ok(Map.of("message","Import completed. Your data is saved in PostgreSQL.","files",files.size(),"storage",data.status()));
        } catch(IllegalStateException e) { return failure(409,e.getMessage()); }
        catch(IllegalArgumentException e) { return failure(400,e.getMessage()); }
        catch(IOException e) { return failure(503,"Import could not complete. Check storage status before retrying; if records exist, restart the service. Existing database records were not overwritten."); }
    }
    static Map<String,byte[]> validateZip(InputStream input,JsonMapper mapper)throws IOException {
        Map<String,byte[]> out=new LinkedHashMap<>(); long total=0; int entries=0;
        try(ZipInputStream zip=new ZipInputStream(input,StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                if(++entries>5000)throw new IllegalArgumentException("ZIP contains too many entries.");
                String key=entry.getName().replace('\\', '/');
                if(key.contains("\\") || key.startsWith("/") || Arrays.asList(key.split("/")).contains(".."))
                    throw new IllegalArgumentException("ZIP contains an unsafe path.");
                if(key.startsWith("data/"))key=key.substring(5);
                if(entry.isDirectory())continue;
                if(!DurableData.allowed(key))throw new IllegalArgumentException("ZIP contains unsupported files. Export only the application's data folders.");
                if(out.containsKey(key))throw new IllegalArgumentException("ZIP contains duplicate file paths.");
                byte[] bytes=zip.readNBytes(10*1024*1024+1);
                total+=bytes.length;
                if(bytes.length>10*1024*1024 || total>50L*1024*1024)
                    throw new IllegalArgumentException("Import limit: 10 MB per file, 50 MB expanded total.");
                if(key.endsWith(".json")) {
                    try {
                        var node=mapper.readTree(bytes);
                        if(node==null || !node.isObject())throw new IllegalArgumentException("Data JSON must contain an object.");
                        if(key.startsWith("inbox/") && !node.path("attachments").isArray())
                            throw new IllegalArgumentException("Each inbox email needs an attachments array.");
                    } catch(IllegalArgumentException e){throw e;}
                    catch(RuntimeException e){throw new IllegalArgumentException("ZIP contains invalid JSON.");}
                }
                out.put(key,bytes);
            }
        }
        if(out.keySet().stream().noneMatch(k->k.startsWith("inbox/")))throw new IllegalArgumentException("No inbox emails found in ZIP.");
        return out;
    }
    @GetMapping("/api/admin/backup")
    public void backup(HttpServletResponse response)throws IOException {
        response.setContentType("application/zip");
        response.setHeader("Cache-Control","no-store");
        response.setHeader("Content-Disposition","attachment; filename=shipping-data-backup.zip");
        data.backup(response.getOutputStream());
    }
    private ResponseEntity<?> failure(int status,String message){return ResponseEntity.status(status).body(Map.of("error",message));}
}
