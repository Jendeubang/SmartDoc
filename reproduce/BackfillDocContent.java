import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Base64;

/**
 * Backfill script: read document content from MinIO and update MySQL.
 */
public class BackfillDocContent {
    public static void main(String[] args) throws Exception {
        String mysqlUrl = "jdbc:mysql://localhost:3306/doc_ai?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8mb4";
        String mysqlUser = "root";
        String mysqlPwd = "123456";

        String minioEndpoint = "http://localhost:9000";
        String minioAccessKey = "minioadmin";
        String minioSecretKey = "minioadmin";

        // Build MinIO auth
        String auth = Base64.getEncoder().encodeToString((minioAccessKey + ":" + minioSecretKey).getBytes());

        try (Connection conn = DriverManager.getConnection(mysqlUrl, mysqlUser, mysqlPwd)) {
            // Find documents with NULL content
            String sql = "SELECT id, title, bucket_name, object_name FROM doc_ai.document WHERE content IS NULL";
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    String title = rs.getString("title");
                    String bucket = rs.getString("bucket_name");
                    String objectName = rs.getString("object_name");

                    if (bucket == null || objectName == null) {
                        // Use default user-1 bucket
                        bucket = "user-1";
                        objectName = "document-content/" + id + ".txt";
                    }

                    System.out.println("Fetching: bucket=" + bucket + ", key=" + objectName);

                    // Download from MinIO via HTTP API
                    String minioUrl = minioEndpoint + "/" + bucket + "/" + URLEncoder.encode(objectName, "UTF-8").replace("+", "%20");
                    HttpURLConnection httpConn = (HttpURLConnection) new URL(minioUrl).openConnection();
                    httpConn.setRequestProperty("Authorization", "Basic " + auth);
                    httpConn.setRequestMethod("GET");

                    int responseCode = httpConn.getResponseCode();
                    if (responseCode == 200) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        try (InputStream in = httpConn.getInputStream()) {
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
                        }
                        String content = baos.toString("UTF-8");

                        // Update MySQL
                        try (PreparedStatement ps = conn.prepareStatement("UPDATE doc_ai.document SET content = ? WHERE id = ?")) {
                            ps.setString(1, content);
                            ps.setString(2, id);
                            int updated = ps.executeUpdate();
                            System.out.println("Updated document " + id + " (" + title + "): " + content.length() + " chars, rows=" + updated);
                        }
                    } else {
                        // Try reading from response body for error
                        try (InputStream err = httpConn.getErrorStream()) {
                            if (err != null) {
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                byte[] buf = new byte[4096];
                                int n;
                                while ((n = err.read(buf)) != -1) baos.write(buf, 0, n);
                                System.out.println("Failed to fetch " + objectName + ": HTTP " + responseCode + " - " + baos.toString("UTF-8"));
                            } else {
                                System.out.println("Failed to fetch " + objectName + ": HTTP " + responseCode);
                            }
                        }
                    }
                    httpConn.disconnect();
                }
            }
        }
        System.out.println("Backfill complete!");
    }
}
