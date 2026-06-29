package com.dissent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * Ministers + PM) when the table is empty.
 *
 * Three entities are marked "important" and pinned to the top in a fixed order: the
 * governing party, the main opposition party, then the PM. Everyone else (remaining parties,
 * ministers, MPs) follows and is ranked by dissent votes at query time.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private final JdbcTemplate jdbc;

    private final String govtParty;
    private final String oppositionParty;

    public DataSeeder(JdbcTemplate jdbc,
                      @Value("${app.govt-party:BJP}") String govtParty,
                      @Value("${app.opposition-party:INC}") String oppositionParty) {
        this.jdbc = jdbc;
        this.govtParty = govtParty;
        this.oppositionParty = oppositionParty;
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

        // Important block, fixed order: governing party (1), opposition party (2), PM (3).
        boolean govtSeen = parties.contains(govtParty);
        boolean oppSeen = parties.contains(oppositionParty);
        if (govtSeen) insert("PARTY", govtParty, null, null, true, ++order);
        else log.warn("Governing party '{}' not found in members.csv", govtParty);
        if (oppSeen) insert("PARTY", oppositionParty, null, null, true, ++order);
        else log.warn("Opposition party '{}' not found in members.csv", oppositionParty);
        for (String[] m : members) {
            if (m[0].equals("PM")) {
                insert("PM", m[1], blankToNull(m[2]), blankToNull(m[3]), true, ++order);
            }
        }

        // Remaining parties (alphabetical), excluding the two already pinned above.
        List<String> sortedParties = new ArrayList<>(parties);
        sortedParties.sort(String::compareToIgnoreCase);
        for (String p : sortedParties) {
            if (p.equals(govtParty) || p.equals(oppositionParty)) continue;
            insert("PARTY", p, null, null, false, ++order);
        }
        // Ministers then MPs (not important; ranked by votes at query time).
        for (String type : new String[]{"MINISTER", "MP"}) {
            for (String[] m : members) {
                if (m[0].equals(type)) {
                    insert(type, m[1], blankToNull(m[2]), blankToNull(m[3]), false, ++order);
                }
            }
        }
        log.info("Seeded {} entities (important: {} + {} + PM) from members.csv",
                order, govtSeen ? govtParty : "—", oppSeen ? oppositionParty : "—");

        seedBullets();
    }

    /** Test bullets so typeahead and the dashboard have something to show before any AI runs. */
    private void seedBullets() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM bullet", Integer.class);
        if (n != null && n > 0) return;
        String[] bullets = {
            "Failed to deliver on promises",
            "Ignored local issues",
            "Poor handling of the economy",
            "Mishandled public funds",
            "Out of touch with constituents",
            "Weak stance on jobs and unemployment",
            "Did nothing on healthcare",
            "Broke campaign pledges",
            "Unresponsive to complaints",
            "Rising prices and cost of living",
        };
        for (String b : bullets) {
            jdbc.update("INSERT INTO bullet (text) VALUES (?) ON CONFLICT (text) DO NOTHING", b);
        }
        log.info("Seeded {} starter bullets", bullets.length);
    }

    private void insert(String type, String name, String party, String detail,
                        boolean important, int order) {
        jdbc.update(
            "INSERT INTO entity (type, name, party_name, department, important, sort_order) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            type, name, party, detail, important, order);
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
