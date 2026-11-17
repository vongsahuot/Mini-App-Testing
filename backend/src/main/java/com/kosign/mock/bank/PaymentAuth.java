package com.kosign.mock.bank;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.time.Instant;

/** Auth method chosen at init (3:25). An OTP is bound to the paymentRef and the amount. */
@Entity
@Table(name = "payment_auth")
public class PaymentAuth {
    public static final String PIN = "PIN", OTP = "OTP";

    @Id public String paymentRef;
    public String customerId;
    public String method;
    public String otp;
    @Column(precision = 14, scale = 2) public BigDecimal amount;
    public String currency;
    public String account;
    public Instant expiresAt;

    public interface Repo extends JpaRepository<PaymentAuth, String> {}
}
