package dev.banking.common.infrastructure;

import dev.banking.BankingApplication;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DatabaseCommandsTest {
    String schema;
    String url;

    @BeforeEach void createSchema()throws Exception {
        schema="migration_test_"+UUID.randomUUID().toString().replace("-", "");
        var base=System.getenv("BANK_TEST_DB_URL");
        url=base+(base.contains("?")?"&":"?")+"currentSchema="+schema;
        try(var db=DriverManager.getConnection(base,"banking_test",System.getenv("BANK_TEST_DB_PASSWORD"));
            var statement=db.createStatement()) {statement.execute("CREATE SCHEMA "+schema);}
    }
    @AfterEach void dropSchema()throws Exception {
        try(var db=DriverManager.getConnection(url,"banking_test",System.getenv("BANK_TEST_DB_PASSWORD"));
            var statement=db.createStatement()) {statement.execute("DROP SCHEMA "+schema+" CASCADE");}
    }
    String query(String sql)throws Exception {
        try(var db=DriverManager.getConnection(url,"banking_test",System.getenv("BANK_TEST_DB_PASSWORD"));
            var statement=db.createStatement();var rows=statement.executeQuery(sql)) {
            rows.next();return rows.getString(1);
        }
    }
    void run(boolean success,String... args)throws Exception {
        var command=new ArrayList<>(List.of(System.getProperty("java.home")+"/bin/java","-cp",
            System.getProperty("java.class.path"),BankingApplication.class.getName()));
        command.addAll(List.of(args));
        var builder=new ProcessBuilder(command);
        builder.environment().put("DB_URL",url);
        builder.environment().put("DB_USERNAME","banking_test");
        builder.environment().put("DB_PASSWORD",System.getenv("BANK_TEST_DB_PASSWORD"));
        builder.environment().put("SEED_SYNTHETIC","true");
        var output=Files.createTempFile("database-command-", ".log");
        try {
            var process=builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try {
                assertThat(process.waitFor(30,TimeUnit.SECONDS)).as("command exits without starting the server").isTrue();
                assertThat(process.exitValue()==0).withFailMessage(Files.readString(output)).isEqualTo(success);
            } finally {process.destroyForcibly();}
        } finally {Files.deleteIfExists(output);}
    }
    @Test void rollbackRemovesSchemaAndMigrationCanReapply()throws Exception {
        run(true,"migrate");
        assertThat(query("SELECT count(*) FROM banking_accounts")).isEqualTo("1");
        run(true,"rollback","1");
        assertThat(query("SELECT to_regclass('banking_accounts')")).isNull();
        assertThat(query("SELECT to_regclass('banking_operations')")).isNull();
        assertThat(query("SELECT count(*) FROM databasechangelog")).isEqualTo("0");
        run(true,"migrate");
        assertThat(query("SELECT balance FROM banking_accounts")).isEqualTo("100.00");
        run(true,"rollback");
        assertThat(query("SELECT to_regclass('banking_accounts')")).isNull();
    }
    @Test void clearingChecksumsPreservesDataAndNextMigrationRecalculatesThem()throws Exception {
        run(true,"migrate");
        try(var db=DriverManager.getConnection(url,"banking_test",System.getenv("BANK_TEST_DB_PASSWORD"));
            var statement=db.createStatement()) {statement.execute("UPDATE banking_accounts SET balance=123.45");}
        for(var command:List.of("clear-checksums","clearchecksum")) {
            assertThat(query("SELECT md5sum FROM databasechangelog")).isNotBlank();
            run(true,command);
            assertThat(query("SELECT md5sum FROM databasechangelog")).isNull();
            assertThat(query("SELECT balance FROM banking_accounts")).isEqualTo("123.45");
            run(true,"migrate");
            assertThat(query("SELECT count(*) FROM databasechangelog")).isEqualTo("1");
            assertThat(query("SELECT balance FROM banking_accounts")).isEqualTo("123.45");
        }
    }
    @Test void invalidCommandsCannotModifyAnAppliedSchema()throws Exception {
        run(true,"migrate");
        for(var count:List.of("0","-1","2","abc","2147483648")) run(false,"rollback",count);
        run(false,"rollback","1","extra");
        run(false,"clear-checksums","extra");
        run(false,"migrate","extra");
        run(false,"rollbak");
        assertThat(query("SELECT count(*) FROM databasechangelog")).isEqualTo("1");
        assertThat(query("SELECT balance FROM banking_accounts")).isEqualTo("100.00");
    }
}
