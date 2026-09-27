package dev.banking.common.infrastructure;

import tools.jackson.databind.json.JsonMapper;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.Map;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

/** Administrative modes run in separate ECS tasks, never in the HTTP server. */
public final class DatabaseCommands {
    static String required(String name) {
        var value=System.getenv(name);
        if(value==null||value.isBlank()) throw new IllegalArgumentException("Missing configuration: "+name);
        return value;
    }
    private static final String USAGE="Commands: migrate | rollback [positive-count, default 1] | clear-checksums (alias clearchecksum) | bootstrap";
    public static boolean execute(String[] args) throws Exception {
        if(args.length==0||args[0].startsWith("--")) return false;
        var command=args[0];
        if(command.equals("rollback")) {
            if(args.length>2) throw new IllegalArgumentException(USAGE);
            int count;
            try {count=args.length==2?Integer.parseInt(args[1]):1;}
            catch(NumberFormatException e) {throw new IllegalArgumentException(USAGE,e);}
            if(count<=0) throw new IllegalArgumentException(USAGE);
            changeSchema(command,count);
        } else {
            if(args.length!=1) throw new IllegalArgumentException(USAGE);
            switch(command) {
                case "migrate", "clear-checksums", "clearchecksum" -> changeSchema(command,0);
                case "bootstrap" -> bootstrap();
                default -> throw new IllegalArgumentException("Unknown command: "+command+". "+USAGE);
            }
        }
        return true;
    }
    private static void changeSchema(String command,int count) throws Exception {
        var url=required("DB_URL");var user=required("DB_USERNAME");var password=required("DB_PASSWORD");
        try(var connection=DriverManager.getConnection(url,user,password);
            var resources=new ClassLoaderResourceAccessor();
            var liquibase=new Liquibase("db/changelog/db.changelog-master.yaml",resources,
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            switch(command) {
                case "migrate" -> liquibase.update(new Contexts(),new LabelExpression());
                case "rollback" -> {
                    if(count>liquibase.getDatabase().getRanChangeSetList().size())
                        throw new IllegalArgumentException("Rollback count exceeds the number of applied changesets");
                    liquibase.rollback(count,new Contexts(),new LabelExpression());
                }
                case "clear-checksums", "clearchecksum" -> liquibase.clearCheckSums();
                default -> throw new IllegalArgumentException(USAGE);
            }
            System.out.println("Liquibase "+command+" completed; applied changesets: "+liquibase.getDatabase().getRanChangeSetList().size());
        }
        if(command.equals("migrate")&&Boolean.parseBoolean(System.getenv("SEED_SYNTHETIC"))) {
            try(var db=DriverManager.getConnection(url,user,password);var q=db.prepareStatement("INSERT INTO banking_accounts(id,owner_subject,balance,currency) VALUES ('00000000-0000-0000-0000-000000000001','alice',100.00,'SGD') ON CONFLICT DO NOTHING")) {q.executeUpdate();}
        }
    }
    public static void bootstrap() throws Exception {
        var json=JsonMapper.builder().build();
        try(var secrets=SecretsManagerClient.create()) {
            var master=json.readTree(secrets.getSecretValue(b->b.secretId(required("MASTER_SECRET_ARN"))).secretString());
            try(var db=DriverManager.getConnection(required("DB_URL"),master.get("username").asString(),master.get("password").asString())) {
                for(var role: new String[]{"banking_migrator","banking_app"}) {
                    var arn=required(role.equals("banking_app")?"APP_SECRET_ARN":"MIGRATION_SECRET_ARN");
                    String password;
                    try {password=json.readTree(secrets.getSecretValue(b->b.secretId(arn)).secretString()).get("password").asString();}
                    catch(ResourceNotFoundException e) {
                        var bytes=new byte[36];new SecureRandom().nextBytes(bytes);password=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                        var value=json.writeValueAsString(Map.of("username",role,"password",password));
                        secrets.putSecretValue(b->b.secretId(arn).secretString(value));
                    }
                    String literal;
                    try(var q=db.prepareStatement("SELECT quote_literal(?)")){q.setString(1,password);try(var r=q.executeQuery()){r.next();literal=r.getString(1);}}
                    boolean exists;
                    try(var q=db.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname=?")){q.setString(1,role);try(var r=q.executeQuery()){exists=r.next();}}
                    try(var q=db.createStatement()){q.execute((exists?"ALTER":"CREATE")+" ROLE "+role+" LOGIN PASSWORD "+literal);}
                }
                try(var q=db.createStatement()) {
                    q.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
                    q.execute("GRANT CONNECT ON DATABASE banking TO banking_app,banking_migrator");
                    q.execute("GRANT USAGE,CREATE ON SCHEMA public TO banking_migrator");
                    q.execute("GRANT USAGE ON SCHEMA public TO banking_app");
                    q.execute("GRANT banking_migrator TO "+master.get("username").asString().replaceAll("[^a-zA-Z0-9_]", ""));
                    q.execute("ALTER DEFAULT PRIVILEGES FOR ROLE banking_migrator IN SCHEMA public GRANT SELECT,INSERT,UPDATE ON TABLES TO banking_app");
                    q.execute("GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA public TO banking_app");
                }
            }
        }
        System.out.println("Database roles initialized");
    }
}
