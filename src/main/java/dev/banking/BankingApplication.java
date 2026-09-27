package dev.banking;

import dev.banking.common.infrastructure.DatabaseCommands;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BankingApplication {
    public static void main(String[] args) throws Exception {
        if (DatabaseCommands.execute(args)) return;
        SpringApplication.run(BankingApplication.class, args);
    }
}
