package com.javaee.user.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

@Component
public class UserProfileSchemaInitializer implements ApplicationRunner {
    private final DataSource dataSource;

    public UserProfileSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            addColumnIfMissing(connection, statement, "nickname", "ALTER TABLE user ADD COLUMN nickname VARCHAR(50) NULL AFTER phone");
            addColumnIfMissing(connection, statement, "signature", "ALTER TABLE user ADD COLUMN signature VARCHAR(160) NULL AFTER nickname");
            addColumnIfMissing(connection, statement, "avatar_file_id", "ALTER TABLE user ADD COLUMN avatar_file_id VARCHAR(128) NULL AFTER signature");
        }
    }

    private void addColumnIfMissing(Connection connection, Statement statement, String column, String sql) throws Exception {
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet columns = meta.getColumns(connection.getCatalog(), null, "user", column)) {
            if (!columns.next()) {
                statement.execute(sql);
            }
        }
    }
}