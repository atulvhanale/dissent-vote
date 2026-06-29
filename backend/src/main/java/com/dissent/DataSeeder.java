package com.dissent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Seeds the entity table from members.csv (real 18th Lok Sabha MPs + Union Council of
 * Ministers + PM) when the table is empty. Order of insertion (= sort_order) is:
 * distinct parties, then the PM, then ministers, then MPs. Vote ranking happens at query time.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private final JdbcTemplate jdbc;

    public DataSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) throws Exception {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM entity", Integer.class);
        if (count != null && count > 0) {
            log.info("entity table already has {} rows — skipping CSV seed", count);
            return;
        }

        List<String[]> members = readCsv();           // [type, name, party, detail]
        Set<String> parties = new LinkedHashSet<>();   // preserve first-seen order
        for (String[] m : members) {
            String party = m[2].trim();
            if (!party.isEmpty()) parties.add(party);
        }

        int order = 0;
        // 1) Parties (alphabetical for a stable, readable list)
        List<String> sortedParties = new ArrayList<>(parties);
        sortedParties.sort(String::compareToIgnoreCase);
        for (String p : sortedParties) {
            insert("PARTY", p, null, null, ++order);
        }
        // 2) PM, 3) Ministers, 4) MPs — in that block order
        for (String type : new String[]{"PM", "MINISTER", "MP"}) {
            for (String[] m : members) {
                if (m[0].equals(type)) {
                    insert(type, m[1], blankToNull(m[2]), blankToNull(m[3]), ++order);
                }
            }
        }
        log.info("Seeded {} entities ({} parties + PM/ministers/MPs) from members.csv",
                order, sortedParties.size());
    }

    private void insert(String type, String name, String party, String detail, int order) {
        jdbc.update(
            "INSERT INTO entity (type, name, party_name, department, sort_order) VALUES (?, ?, ?, ?, ?)",
            type, name, party, detail, order);
    }

    private static String blankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    /** Minimal CSV reader: handles quoted fields and embedded commas. */
    private List<String[]> readCsv() throws Exception {
        List<String[]> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new ClassPathResource("members.csv").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = r.readLine()) != null) {
                if (header) { header = false; continue; }
                if (line.isBlank()) continue;
                String[] f = parseLine(line);
                if (f.length >= 4) out.add(new String[]{f[0], f[1], f[2], f[3]});
            }
        }
        return out;
    }

    private static String[] parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else inQuotes = false;
                } else cur.append(c);
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(cur.toString()); cur.setLength(0);
            } else cur.append(c);
        }
        fields.add(cur.toString());
        return fields.toArray(new String[0]);
    }
}
