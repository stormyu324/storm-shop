package com.javastorm.shop.service;

import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.OrderMail;
import com.javastorm.shop.repository.OrderMailRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class OrderMailService {
    private final OrderMailRepository mails;
    private final ObjectProvider<JavaMailSender> sender;
    private final TransactionTemplate transactions;
    private final String from;

    public OrderMailService(
            OrderMailRepository mails,
            ObjectProvider<JavaMailSender> sender,
            PlatformTransactionManager manager,
            @Value("${shop.mail.from:}") String from) {
        this.mails = mails;
        this.sender = sender;
        this.from = from;
        transactions = new TransactionTemplate(manager);
    }

    public boolean available() {
        return sender.getIfAvailable() != null && !from.isBlank();
    }

    public void enqueue(
            String key, String email, String subject, String body, LocalDateTime expires) {
        if (email == null || email.isBlank() || mails.existsByEventKey(key)) {
            return;
        }
        OrderMail mail = new OrderMail();
        mail.setEventKey(key);
        mail.setRecipient(email);
        mail.setSubject(subject);
        mail.setBody(body);
        mail.setExpiresAt(expires);
        mails.save(mail);
    }

    /** 订单通知的有效期：邮件服务长时间没配置或故障时，过期的旧通知不再补发。 */
    static final Duration ORDER_NOTICE_TTL = Duration.ofDays(3);

    public void order(CustomerOrder order, String event, String detail) {
        enqueue(
                order.getId() + ":" + event,
                order.getEmail(),
                "Storm order " + order.getOrderNo() + " · " + event,
                "Order / 订单 "
                        + order.getOrderNo()
                        + "\n"
                        + detail
                        + "\n"
                        + "Total / 总额 "
                        + order.getCurrency()
                        + " "
                        + order.getTotal()
                        + "\n请回到商城“订单查询”页面查看详情。 / Visit Order lookup in the store.",
                LocalDateTime.now().plus(ORDER_NOTICE_TTL));
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 15000)
    public void deliver() {
        if (!available()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (OrderMail candidate
                : mails.findTop20BySentAtIsNullAndNextAttemptBeforeAndAttemptsLessThanOrderByIdAsc(now, 8)) {
            transactions.executeWithoutResult(status -> mails.lock(candidate.getId()).ifPresent(this::send));
        }
    }

    /** 发送一封已加锁的邮件；失败时按指数退避安排下次重试。 */
    private void send(OrderMail mail) {
        LocalDateTime now = LocalDateTime.now();
        if (mail.getSentAt() != null || mail.getNextAttempt().isAfter(now) || mail.getAttempts() >= 8) {
            return;
        }
        if (mail.getExpiresAt() != null && mail.getExpiresAt().isBefore(now)) {
            mail.setAttempts(8);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(mail.getRecipient());
            message.setSubject(mail.getSubject());
            message.setText(mail.getBody());
            sender.getObject().send(message);
            mail.setSentAt(LocalDateTime.now());
        } catch (MailException e) {
            mail.setAttempts(mail.getAttempts() + 1);
            long backoff = Math.min(60, 1L << mail.getAttempts());
            mail.setNextAttempt(LocalDateTime.now().plusMinutes(backoff));
        }
    }
}
