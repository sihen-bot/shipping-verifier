package com.hackathon.shippingverifier;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Single-instance prototype: PostgreSQL is durable; data/ is a rebuildable working copy. */
@Component
public class DurableData implements InitializingBean {
    private final boolean cloud;
    private final String url;
    private final Properties credentials = new Properties();
    private final Path root = Path.of("data").toAbsolutePath().normalize();
    private boolean ready;

    public DurableData(Environment env) {
        cloud = env.matchesProfiles("cloud");
        if (!cloud) { url = null; return; }
        String host = env.getProperty("DB_HOST", "");
        String database = env.getProperty("DB_NAME", "");
        String user = env.getProperty("DB_USER", "");
        String password = env.getProperty("DB_PASSWORD", "");
        if (!host.matches("[a-zA-Z0-9.-]+") || !database.matches("[a-zA-Z0-9_-]+")
                || user.isBlank() || password.isBlank())
            throw new IllegalStateException("Cloud storage requires DB_HOST, DB_NAME, DB_USER and DB_PASSWORD.");
        url = "jdbc:postgresql://" + host + ":5432/" + database;
        credentials.setProperty("user", user); credentials.setProperty("password", password);
        credentials.setProperty("sslmode", "require");
        credentials.setProperty("connectTimeout", "15");
        credentials.setProperty("socketTimeout", "60");
    }
    public boolean cloud() { return cloud; }
    public synchronized boolean ready() { return ready; }
    private Connection connect() throws SQLException { return DriverManager.getConnection(url, credentials); }

    @Override public synchronized void afterPropertiesSet() throws IOException {
        Files.createDirectories(root.resolve("inbox"));
        Files.createDirectories(root.resolve("attachments"));
        if (cloud) {
            try (Connection c = connect(); Statement s = c.createStatement()) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS shipping_files (path TEXT PRIMARY KEY, content BYTEA NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)");
                c.setAutoCommit(false);
                s.setFetchSize(20);
                try (ResultSet rows = s.executeQuery("SELECT path, content FROM shipping_files ORDER BY path")) {
                    while (rows.next()) materialize(rows.getString(1), rows.getBytes(2));
                }
                c.commit();
            } catch (SQLException e) { throw new IOException("Database initialization failed. Check Render database settings and availability."); }
        }
        ready = true;
    }
    static boolean allowed(String key) {
        return key != null && (key.matches("inbox/email_\\d+\\.json")
            || key.matches("attachments/[A-Za-z0-9_][A-Za-z0-9_. ()-]*\\.(?i:txt|pdf|xlsx|docx)")
            || key.matches("ai-cache/[0-9a-f]{64}\\.txt")
            || key.matches("(?:reviews|ai-reports)/email_\\d+/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json"));
    }
    private Path resolve(String key) throws IOException {
        if (!allowed(key)) throw new IOException("Unsupported data path.");
        Path target = root.resolve(key).normalize();
        for (Path p = target; p != null && p.startsWith(root); p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IOException("Symbolic links are not allowed in managed data.");
        return target;
    }
    private void materialize(String key, byte[] bytes) throws IOException {
        Path target = resolve(key); Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "pending-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    public synchronized void write(Path target, byte[] bytes, boolean createOnly) throws IOException {
        if (!ready) throw new IOException("Data storage is not ready.");
        String key = root.relativize(target.toAbsolutePath().normalize()).toString().replace('\\', '/');
        Path destination = resolve(key);
        if (createOnly && Files.exists(destination)) throw new IOException("Record already exists.");
        if (cloud) {
            String sql = "INSERT INTO shipping_files(path, content) VALUES (?, ?)" + (createOnly ? "" :
                " ON CONFLICT(path) DO UPDATE SET content=EXCLUDED.content, updated_at=CURRENT_TIMESTAMP");
            try (Connection c = connect(); PreparedStatement p = c.prepareStatement(sql)) {
                p.setString(1, key); p.setBytes(2, bytes); p.executeUpdate();
            } catch (SQLException e) { throw new IOException("Database save failed; no successful save was reported."); }
        }
        try { materialize(key, bytes); }
        catch(IOException e) { ready = false; throw new IOException("Working copy unavailable. Restart the service to restore database records."); }
    }
    public synchronized Map<String, Object> status() throws IOException {
        if (!cloud) return Map.of("storage", "local", "ready", ready);
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "SELECT COUNT(*), COUNT(*) FILTER (WHERE path LIKE 'inbox/%'), COUNT(*) FILTER (WHERE path LIKE 'attachments/%') FROM shipping_files")) {
            r.next(); return Map.of("storage", "postgresql", "ready", ready, "files", r.getLong(1), "emails", r.getLong(2), "attachments", r.getLong(3));
        } catch (SQLException e) { throw new IOException("Database status unavailable."); }
    }
    public synchronized void importFiles(Map<String, byte[]> files) throws IOException {
        if (!cloud || !ready) throw new IOException("Cloud storage is not ready.");
        for (String key : files.keySet()) resolve(key);
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("LOCK TABLE shipping_files IN EXCLUSIVE MODE");
                try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM shipping_files")) {
                    r.next(); if (r.getLong(1) != 0) throw new IllegalStateException("Import is only allowed into an empty database. Existing data was not replaced.");
                }
                try (PreparedStatement p = c.prepareStatement("INSERT INTO shipping_files(path, content) VALUES (?, ?)")) {
                    for (var entry : files.entrySet()) {
                        p.setString(1, entry.getKey()); p.setBytes(2, entry.getValue()); p.executeUpdate();
                    }
                }
                c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw new IOException("Import failed; database transaction rolled back."); }
        try { for (var entry : files.entrySet()) materialize(entry.getKey(), entry.getValue()); }
        catch(IOException e) { ready = false; throw new IOException("Import is stored in the database. Restart the service to restore its working copy."); }
    }
    public synchronized void backup(OutputStream output) throws IOException {
        if (!cloud || !ready) throw new IOException("Cloud storage is not ready.");
        try (Connection c=connect(); Statement s=c.createStatement()) {
            c.setAutoCommit(false); s.setFetchSize(20);
            try (ResultSet rows=s.executeQuery("SELECT path, content FROM shipping_files ORDER BY path");
                 java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(output)) {
                while(rows.next()) {
                    String key=rows.getString(1); resolve(key);
                    zip.putNextEntry(new java.util.zip.ZipEntry(key));
                    zip.write(rows.getBytes(2)); zip.closeEntry();
                }
                zip.finish();
            }
            c.commit();
        } catch(SQLException e) { throw new IOException("Database backup failed. Discard an incomplete download."); }
    }

}
