package com.javastorm.shop.service;

import com.javastorm.shop.domain.*;
import com.javastorm.shop.repository.OrderMailRepository;
import java.time.LocalDateTime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    if (email == null || email.isBlank() || mails.existsByEventKey(key)) return;
    OrderMail mail = new OrderMail();
    mail.eventKey = key;
    mail.recipient = email;
    mail.subject = subject;
    mail.body = body;
    mail.expiresAt = expires;
    mails.save(mail);
  }

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
        null);
  }

  @Scheduled(fixedDelay = 30000, initialDelay = 15000)
  public void deliver() {
    if (!available()) return;
    for (var candidate :
        mails.findTop20BySentAtIsNullAndNextAttemptBeforeAndAttemptsLessThanOrderByIdAsc(
            LocalDateTime.now(), 8)) {
      transactions.executeWithoutResult(
          status ->
              mails
                  .lock(candidate.id)
                  .ifPresent(
                      mail -> {
                        if (mail.sentAt != null
                            || mail.nextAttempt.isAfter(LocalDateTime.now())
                            || mail.attempts >= 8) return;
                        if (mail.expiresAt != null
                            && mail.expiresAt.isBefore(LocalDateTime.now())) {
                          mail.attempts = 8;
                          return;
                        }
                        try {
                          SimpleMailMessage message = new SimpleMailMessage();
                          message.setFrom(from);
                          message.setTo(mail.recipient);
                          message.setSubject(mail.subject);
                          message.setText(mail.body);
                          sender.getObject().send(message);
                          mail.sentAt = LocalDateTime.now();
                        } catch (org.springframework.mail.MailException e) {
                          mail.attempts++;
                          mail.nextAttempt =
                              LocalDateTime.now().plusMinutes(Math.min(60, 1L << mail.attempts));
                        }
                      }));
    }
  }
}
