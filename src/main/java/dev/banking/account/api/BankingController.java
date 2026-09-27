package dev.banking.account.api;
import dev.banking.account.application.BankingService;
import dev.banking.account.domain.Balance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class BankingController {
    record Money(@NotNull @DecimalMin("0.01") @Digits(integer=17,fraction=2) BigDecimal amount) {}
    private final BankingService service;
    BankingController(BankingService service) {this.service=service;}
    @GetMapping("/accounts/{id}/balance") Balance balance(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt) {return service.balance(id,jwt.getSubject());}
    @PostMapping("/accounts/{id}/deposits") Balance deposit(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Money money,
            @RequestHeader("Idempotency-Key") @Pattern(regexp="[A-Za-z0-9._:-]{1,128}") String key) {return service.mutate(id,jwt.getSubject(),"deposit",money.amount(),key);}
    @PostMapping("/accounts/{id}/withdrawals") Balance withdraw(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Money money,
            @RequestHeader("Idempotency-Key") @Pattern(regexp="[A-Za-z0-9._:-]{1,128}") String key) {return service.mutate(id,jwt.getSubject(),"withdrawal",money.amount(),key);}
}
