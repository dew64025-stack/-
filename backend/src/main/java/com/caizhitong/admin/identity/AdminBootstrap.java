package com.caizhitong.admin.identity;
import com.caizhitong.common.*;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
/** Run from a controlled host using an explicitly privileged bootstrap database account. */
public final class AdminBootstrap {
    private AdminBootstrap(){}
    public static void main(String[] args){try{if(args.length!=4)throw new IllegalArgumentException();long operator=Long.parseLong(args[0]),user=Long.parseLong(args[1]),campus=Long.parseLong(args[2]);String role=args[3];String url=env("MYSQL_URL");if(!url.startsWith("jdbc:mysql:"))throw new IllegalArgumentException();DriverManagerDataSource ds=new DriverManagerDataSource(url,env("ADMIN_BOOTSTRAP_DB_USER"),env("ADMIN_BOOTSTRAP_DB_PASSWORD"));Clock clock=Clock.systemUTC();SensitiveDataCrypto crypto=new SensitiveDataCrypto(Base64.getDecoder().decode(env("APP_ENCRYPTION_KEY")),Base64.getDecoder().decode(env("APP_HMAC_KEY")));new AdminBootstrapService(new JdbcTemplate(ds),new DataSourceTransactionManager(ds),new SnowflakeIds(clock,Integer.parseInt(env("SNOWFLAKE_WORKER_ID"))),crypto,clock).initialize(new AdminProvisioning.Input(operator,user,campus,role,env("ADMIN_BOOTSTRAP_USERNAME"),env("ADMIN_BOOTSTRAP_PASSWORD"),env("ADMIN_BOOTSTRAP_MFA_SEED")));}catch(RuntimeException e){throw new IllegalStateException("Administrator initialization failed; verify explicit inputs and controlled database grants");}}
    private static String env(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Required bootstrap environment is missing");return value;}
}
