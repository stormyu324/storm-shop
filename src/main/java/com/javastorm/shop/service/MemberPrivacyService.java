package com.javastorm.shop.service;

import com.javastorm.shop.domain.AfterSale;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.MemberAccount;
import com.javastorm.shop.domain.OrderStatus;
import com.javastorm.shop.repository.AfterSaleRepository;
import com.javastorm.shop.repository.MemberAddressRepository;
import com.javastorm.shop.repository.MemberChallengeRepository;
import com.javastorm.shop.repository.MemberFavoriteRepository;
import com.javastorm.shop.repository.MemberRepository;
import com.javastorm.shop.repository.OrderMailRepository;
import com.javastorm.shop.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会员个人信息的查阅、导出和删除（《个人信息保护法》第 44–47 条、GDPR 第 15 / 17 / 20 条）。
 * <ul>
 *   <li>导出：账户、收货地址、收藏、订单（含收货信息、商品、物流、售后）汇总成一份 JSON；</li>
 *   <li>注销：删除账户、地址、收藏、验证码和邮件记录；订单因记账和售后需要保留，但抹去姓名、电话、地址、邮箱等个人信息。
 *       还有未完成的订单或售后时不能注销（商家需要联系顾客完成履约）。</li>
 * </ul>
 */
@Service
public class MemberPrivacyService {

    private static final Logger log = LoggerFactory.getLogger(MemberPrivacyService.class);
    static final String ANONYMIZED = "已注销用户";
    private static final Set<OrderStatus> UNFINISHED = Set.of(
            OrderStatus.PENDING_PAYMENT, OrderStatus.PLACED, OrderStatus.PAID, OrderStatus.SHIPPED);

    private final MemberRepository members;
    private final MemberAddressRepository addresses;
    private final MemberFavoriteRepository favorites;
    private final MemberChallengeRepository challenges;
    private final OrderRepository orders;
    private final AfterSaleRepository afterSales;
    private final OrderMailRepository mails;
    private final OrderMailService mail;

    public MemberPrivacyService(MemberRepository members, MemberAddressRepository addresses,
                                MemberFavoriteRepository favorites, MemberChallengeRepository challenges,
                                OrderRepository orders, AfterSaleRepository afterSales, OrderMailRepository mails,
                                OrderMailService mail) {
        this.members = members;
        this.addresses = addresses;
        this.favorites = favorites;
        this.challenges = challenges;
        this.orders = orders;
        this.afterSales = afterSales;
        this.mails = mails;
        this.mail = mail;
    }

    /** 导出这位会员在本站的全部个人信息。 */
    @Transactional(readOnly = true)
    public Map<String, Object> export(Long memberId) {
        MemberAccount m = members.findById(memberId).orElseThrow(() -> new BusinessException("账户不存在"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exportedAt", LocalDateTime.now().toString());
        out.put("account", Map.of("email", m.getEmail(), "createdAt", String.valueOf(m.getCreatedAt())));
        out.put("addresses", addresses.findByMemberId(memberId).stream().map(a -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", a.getName());
            row.put("phone", a.getPhone());
            row.put("country", a.getCountry());
            row.put("address", a.getAddress());
            row.put("default", a.isPreferred());
            row.put("active", a.isActive());
            return row;
        }).toList());
        out.put("favoriteProductIds", favorites.findByMemberId(memberId).stream()
                .filter(f -> f.isActive()).map(f -> f.getProductId()).toList());
        out.put("orders", orders.findAllByEmailIgnoreCaseOrderByIdAsc(m.getEmail()).stream().map(this::order).toList());
        return out;
    }

    private Map<String, Object> order(CustomerOrder o) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("orderNo", o.getOrderNo());
        row.put("createdAt", String.valueOf(o.getCreatedAt()));
        row.put("status", o.getStatus().name());
        row.put("currency", o.getCurrency() == null ? null : o.getCurrency().name());
        row.put("total", o.getTotal());
        row.put("refundedAmount", o.getRefundedAmount());
        row.put("customerName", o.getCustomerName());
        row.put("phone", o.getPhone());
        row.put("country", o.getCountry());
        row.put("address", o.getAddress());
        row.put("email", o.getEmail());
        row.put("note", o.getNote());
        row.put("items", o.getItems().stream().map(i -> Map.of(
                "product", i.getProductName(), "unitPrice", i.getUnitPrice(), "quantity", i.getQuantity())).toList());
        row.put("carrier", o.getCarrier());
        row.put("trackingNumber", o.getTrackingNumber());
        row.put("afterSales", afterSales.findByOrderIdOrderByIdDesc(o.getId()).stream().map(a -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("reason", a.getReason());
            c.put("state", a.getState());
            c.put("refundAmount", a.getRefundAmount());
            c.put("createdAt", String.valueOf(a.getCreatedAt()));
            return c;
        }).toList());
        return row;
    }

    /** 注销账户。返回被抹去个人信息的订单数。 */
    @Transactional
    public int delete(Long memberId) {
        MemberAccount m = members.findById(memberId).orElseThrow(() -> new BusinessException("账户不存在"));
        String email = m.getEmail();
        List<CustomerOrder> mine = orders.findAllByEmailIgnoreCaseOrderByIdAsc(email);
        for (CustomerOrder o : mine) {
            if (UNFINISHED.contains(o.getStatus())) {
                throw new BusinessException("订单 " + o.getOrderNo() + " 还没有完成，完成或取消后才能注销账户");
            }
            if (afterSales.findByOrderIdOrderByIdDesc(o.getId()).stream().anyMatch(AfterSale::isOpen)) {
                throw new BusinessException("订单 " + o.getOrderNo() + " 还有处理中的售后，处理完后才能注销账户");
            }
        }
        for (CustomerOrder o : mine) {
            o.setCustomerName(ANONYMIZED);
            o.setPhone("-");
            o.setAddress("-");
            o.setNote(null);
            o.setEmail(null);
        }
        addresses.deleteByMemberId(memberId);
        favorites.deleteByMemberId(memberId);
        challenges.deleteByEmailIgnoreCase(email);
        mails.deleteByRecipientIgnoreCase(email);
        members.delete(m);
        mail.enqueue("MEMBER_DELETED:" + memberId + ":" + System.currentTimeMillis(), email,
                "Storm 账户已注销 / Account deleted",
                "您的会员账户和个人信息已删除；历史订单只保留金额和商品用于记账，姓名、电话、地址、邮箱已抹去。\n"
                        + "Your account and personal data have been deleted.",
                LocalDateTime.now().plusDays(3));
        log.info("会员 {} 已注销，{} 个历史订单已抹去个人信息", memberId, mine.size());
        return mine.size();
    }
}
