package com.hackathon.shippingverifier;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Fictional fixtures only: no inbox, database, credentials, or external AI calls. */
@RestController
public class PublicDemoController {
    public record Demo(String source, String mode, String si, String bl, ShipmentComparison.Result result) {}
    @GetMapping("/api/demo")
    public ResponseEntity<?> demo(@RequestParam(name="example", defaultValue="match") String example) {
        if (!Set.of("match", "differences", "missing").contains(example))
            return ResponseEntity.badRequest().body(Map.of("error", "Choose a listed demo example."));
        String[] si = {"Aurora Paper Demo Ltd", "Comet Stationery Demo Ltd", "Comet Stationery Demo Ltd",
            "Port Aurora (DEAAA)", "Port Nova (DEBBB)", "5 x 40'HC", "100,000 KG"};
        String[] bl = si.clone();
        if (example.equals("differences")) { bl[4]="Port Comet (DEBBB)"; bl[5]="6 x 40'HC"; }
        String siText=document("SHIPPING INSTRUCTION", si);
        String blText=example.equals("missing") ? "" : document("DRAFT BILL OF LADING", bl);
        ShipmentComparison.Result result=example.equals("missing")
            ? new ShipmentComparison.Result("NEEDS_REVIEW", "A comparison needs both the SI and draft BL. The fictional draft BL is missing.", List.of(), List.of())
            : ShipmentComparison.compare(values(si,siText),values(bl,blText));
        return ResponseEntity.ok(new Demo("SYNTHETIC_DEMO", "Preset fields checked by the application's Java rules. No Gemini request or shipment approval.", siText,blText,result));
    }
    private static String document(String heading, String[] values) {
        StringBuilder text=new StringBuilder("FICTIONAL DEMO — names and location codes are invented.\n"+heading+"\n");
        for (int i=0;i<values.length;i++) text.append(ShipmentComparison.FIELDS.get(i)).append(": ").append(values[i]).append("\n");
        return text.toString();
    }
    private static Map<String,ShipmentComparison.Value> values(String[] fields,String source) {
        Map<String,ShipmentComparison.Value> result=new LinkedHashMap<>();
        for(int i=0;i<fields.length;i++) {
            String field=ShipmentComparison.FIELDS.get(i);
            result.put(field,ShipmentComparison.validate(fields[i],field+": "+fields[i],"PRESENT",source));
        }
        return result;
    }
}
