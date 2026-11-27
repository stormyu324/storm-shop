package com.javastorm.shop.service;

import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.OrderMail;
import com.javastorm.shop.repository.OrderMailRepository;
import jakarta.annotation.PreDestroy;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 订单通知与验证码发件箱：采用“短事务领取 → 事务外并发发信 → 短事务写回结果”三段式架构，
 * 避免 SMTP 网络 I/O 占用数据库连接池，支撑日均数千封邮件高峰；配合 ShedLock 防止多实例重复轮询。
 */
@Service
public class OrderMailService {

    private static final Logger log = LoggerFactory.getLogger(OrderMailService.class);
    private static final int MAX_ATTEMPTS = 8;
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(5);

    private final OrderMailRepository mails;
    private final ObjectProvider<JavaMailSender> sender;
    private final TransactionTemplate transactions;
    private final String from;
    private final ExecutorService workers;

    private record MailTask(Long id, String recipient, String subject, String body) {
    }

    public OrderMailService(
            OrderMailRepository mails,
            ObjectProvider<JavaMailSender> sender,
            PlatformTransactionManager manager,
            @Value("${shop.mail.from:}") String from,
            @Value("${shop.mail.concurrency:4}") int concurrency) {
        this.mails = mails;
        this.sender = sender;
        this.from = from;
        this.transactions = new TransactionTemplate(manager);
        int threads = Math.max(1, Math.min(concurrency, 32));
        this.workers = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "mail-delivery");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
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

    /** 订单通知；同一订单的同一事件只发一次。 */
    public void order(CustomerOrder order, String event, String detail) {
        notice(order, order.getId() + ":" + event, event, detail);
    }

    /** 同一事件可以多次发生的通知（如修改物流单号），revision 区分每一次。 */
    public void order(CustomerOrder order, String event, String revision, String detail) {
        notice(order, order.getId() + ":" + event + ":" + revision, event, detail);
    }

    private void notice(CustomerOrder order, String key, String event, String detail) {
        enqueue(
                key,
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

    @Scheduled(fixedDelayString = "${shop.mail.poll-delay-ms:10000}", initialDelay = 15000)
    @SchedulerLock(name = "order_mail_deliver", lockAtMostFor = "PT4M")
    public void deliver() {
        if (!available()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<OrderMail> candidates = mails
                .findTop100BySentAtIsNullAndNextAttemptBeforeAndAttemptsLessThanOrderByIdAsc(now, MAX_ATTEMPTS);
        if (candidates.isEmpty()) {
            return;
        }
        List<MailTask> claimed = new ArrayList<>();
        for (OrderMail candidate : candidates) {
            MailTask task = transactions.execute(status -> claim(candidate.getId()));
            if (task != null) {
                claimed.add(task);
            }
        }
        if (claimed.isEmpty()) {
            return;
        }
        List<CompletableFuture<Void>> futures = claimed.stream()
                .map(task -> CompletableFuture.runAsync(() -> dispatchAndRecord(task), workers))
                .toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }

    /** 短事务领取邮件任务并设置租约，防止崩溃后永久卡死或被重复发送。 */
    private MailTask claim(Long mailId) {
        OrderMail mail = mails.lock(mailId).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (mail == null || mail.getSentAt() != null || mail.getNextAttempt().isAfter(now)
                || mail.getAttempts() >= MAX_ATTEMPTS) {
            return null;
        }
        if (mail.getExpiresAt() != null && mail.getExpiresAt().isBefore(now)) {
            mail.setAttempts(MAX_ATTEMPTS);
            return null;
        }
        mail.setNextAttempt(now.plus(CLAIM_LEASE));
        return new MailTask(mail.getId(), mail.getRecipient(), mail.getSubject(), mail.getBody());
    }

    private void dispatchAndRecord(MailTask task) {
        boolean sent = false;
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(task.recipient());
            message.setSubject(task.subject());
            message.setText(task.body());
            sender.getObject().send(message);
            sent = true;
        } catch (MailException e) {
            log.warn("发送邮件失败 mailId={} recipient={}: {}", task.id(), task.recipient(), e.getMessage());
        }
        boolean success = sent;
        transactions.executeWithoutResult(status -> recordOutcome(task.id(), success));
    }

    private void recordOutcome(Long mailId, boolean success) {
        OrderMail mail = mails.lock(mailId).orElse(null);
        if (mail == null || mail.getSentAt() != null) {
            return;
        }
        if (success) {
            mail.setSentAt(LocalDateTime.now());
        } else {
            mail.setAttempts(mail.getAttempts() + 1);
            long backoff = Math.min(60, 1L << mail.getAttempts());
            mail.setNextAttempt(LocalDateTime.now().plusMinutes(backoff));
        }
    }
}
