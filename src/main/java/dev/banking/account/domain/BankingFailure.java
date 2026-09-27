package dev.banking.account.domain;
public final class BankingFailure extends RuntimeException {
    private final int status;
    public BankingFailure(int status, String code) { super(code); this.status = status; }
    public int status() { return status; }
    public String code() { return getMessage(); }
}
