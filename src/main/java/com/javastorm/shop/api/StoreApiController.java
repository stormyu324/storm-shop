package com.javastorm.shop.api;

import com.javastorm.shop.api.ApiExceptionHandler.NotFoundException;
import com.javastorm.shop.api.StoreDtos.CartItem;
import com.javastorm.shop.api.StoreDtos.CategoryDto;
import com.javastorm.shop.api.StoreDtos.CountryDto;
import com.javastorm.shop.api.StoreDtos.OrderCreated;
import com.javastorm.shop.api.StoreDtos.OrderRequest;
import com.javastorm.shop.api.StoreDtos.ProductDetail;
import com.javastorm.shop.api.StoreDtos.ProductSummary;
import com.javastorm.shop.api.StoreDtos.Quote;
import com.javastorm.shop.api.StoreDtos.QuoteLine;
import com.javastorm.shop.api.StoreDtos.QuoteRequest;
import com.javastorm.shop.config.LocaleConfig;
import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.payment.PaymentFlow;
import com.javastorm.shop.payment.StripePaymentService;
import com.javastorm.shop.repository.CategoryRepository;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.service.Cart;
import com.javastorm.shop.service.CartView;
import com.javastorm.shop.service.Countries;
import com.javastorm.shop.service.OrderPlacer;
import com.javastorm.shop.service.OrderService;
import com.javastorm.shop.service.ProductService;
import com.javastorm.shop.service.SiteUrls;
import com.javastorm.shop.service.VariantService;
import com.javastorm.shop.web.form.CheckoutForm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 商城前台的公开接口：不需要登录，App / 小程序 / 前端页面都可以用。 */
@RestController
@RequestMapping("/api")
@Tag(name = "商城", description = "公开接口：商品、分类、运费试算、下单")
public class StoreApiController {

    private final ProductService productService;
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final VariantService variantService;
    private final OrderService orderService;
    private final OrderPlacer orderPlacer;
    private final StripePaymentService stripe;
    private final PaymentFlow paymentFlow;
    private final SiteUrls siteUrls;
    private final ApiMapper mapper;
    private final Validator validator;
    private final com.javastorm.shop.service.OrderSubmissionGuard submissions;

    public StoreApiController(ProductService productService, ProductRepository products, CategoryRepository categories,
                              VariantService variantService, OrderService orderService, OrderPlacer orderPlacer,
                              StripePaymentService stripe, PaymentFlow paymentFlow, SiteUrls siteUrls,
                              ApiMapper mapper, Validator validator, com.javastorm.shop.service.OrderSubmissionGuard submissions) {
        this.submissions = submissions;
        this.productService = productService;
        this.products = products;
        this.categories = categories;
        this.variantService = variantService;
        this.orderService = orderService;
        this.orderPlacer = orderPlacer;
        this.stripe = stripe;
        this.paymentFlow = paymentFlow;
        this.siteUrls = siteUrls;
        this.mapper = mapper;
        this.validator = validator;
    }

    static Currency currency(Currency requested) {
        return requested != null ? requested : LocaleConfig.currentCurrency();
    }

    @GetMapping("/categories")
    @Operation(summary = "分类列表")
    public List<CategoryDto> categories() {
        boolean en = LocaleConfig.isEnglish();
        return categories.findAllByOrderBySortOrderAscIdAsc().stream().map(c -> mapper.category(c, en)).toList();
    }

    @GetMapping("/countries")
    @Operation(summary = "国家 / 地区列表", description = "常用国家在前，名称按 lang 显示，用于收货地址的下拉框")
    public List<CountryDto> countries() {
        return Countries.list(LocaleContextHolder.getLocale()).stream()
                .map(c -> new CountryDto(c.code(), c.name())).toList();
    }

    @GetMapping("/products")
    @Operation(summary = "商品列表", description = "只包含在售商品；同一款号的多个规格只返回一个")
    public PageDto<ProductSummary> products(
            @Parameter(description = "搜索名称、英文标题、SKU") @RequestParam(required = false) String q,
            @RequestParam(required = false) Long category,
            @Parameter(description = "new 最新上架（默认）/ price_asc / price_desc") @RequestParam(defaultValue = "new") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) Currency currency) {
        Sort order = switch (sort) {
            case "price_asc" -> Sort.by("price").ascending();
            case "price_desc" -> Sort.by("price").descending();
            default -> Sort.by("createdAt").descending().and(Sort.by("id").descending());
        };
        String keyword = q == null || q.isBlank() ? null : q.trim();
        Page<Product> result = productService.searchStorefront(keyword, category,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 48), order));
        Currency cur = currency(currency);
        boolean en = LocaleConfig.isEnglish();
        var families = variantService.families(result.getContent(), cur);
        return PageDto.of(result, p -> mapper.summary(p, cur, en, families));
    }

    @GetMapping("/products/{id}")
    @Operation(summary = "商品详情", description = "包含图片、卖点（英文）和可选的颜色 / 尺码规格")
    public ProductDetail product(@PathVariable Long id, @RequestParam(required = false) Currency currency) {
        Product p = products.findById(id).filter(Product::isOnSale)
                .orElseThrow(() -> new NotFoundException("商品不存在或已下架"));
        return mapper.detail(p, currency(currency), LocaleConfig.isEnglish());
    }

    @PostMapping("/shipping/quote")
    @Operation(summary = "运费试算", description = "按商品、数量和寄往国家计算商品金额、运费和合计")
    public Quote quote(@Valid @RequestBody QuoteRequest req) {
        Currency cur = currency(req.currency());
        String country = req.country() == null || req.country().isBlank() ? null : req.country().trim().toUpperCase();
        if (country != null && !Countries.isValid(country)) {
            throw new ApiValidationException(Map.of("country", "国家代码无效"));
        }
        CartView view = orderService.view(cartOf(req.items()), cur, country);
        boolean en = LocaleConfig.isEnglish();
        return new Quote(view.lines().stream().map(l -> new QuoteLine(l.product().getId(), l.product().title(en),
                        l.quantity(), l.unitPrice(), l.getSubtotal(), l.isAvailable())).toList(),
                view.subtotal(), view.shipping(), view.total(), cur, country, view.deliverable(), view.freeRemaining());
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "下单", description = "扣减库存并创建订单。该币种开通了 Stripe 时订单为“待付款”，"
            + "返回 paymentUrl，把顾客跳转过去付款；30 分钟内未付款自动取消。")
    public OrderCreated placeOrder(@Valid @RequestBody OrderRequest req,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String key,
            jakarta.servlet.http.HttpServletRequest http) {
        if (key != null && (key.isBlank() || key.length() > 128)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "下单标识长度必须为 1 至 128");
        }
        Currency cur = currency(req.currency());
        String fingerprint = org.springframework.util.DigestUtils.md5DigestAsHex(
                (req.toString() + cur.name()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String requestKey = key == null ? "auto:" + fingerprint : "key:" + key;
        return submissions.submit(http.getSession().getId(), http.getRemoteAddr(), requestKey, fingerprint, () -> createOrder(req, cur));
    }

    private OrderCreated createOrder(OrderRequest req, Currency cur) {
        CheckoutForm form = new CheckoutForm();
        form.setCustomerName(req.customerName());
        form.setPhone(req.phone());
        form.setAddress(req.address());
        form.setNote(req.note());
        form.setCountry(req.country());
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<CheckoutForm> v : validator.validate(form)) {
            errors.putIfAbsent(v.getPropertyPath().toString(), v.getMessage());
        }
        if (!errors.containsKey("country") && !Countries.isValid(form.getCountry())) {
            errors.put("country", "国家代码无效");
        }
        if (!errors.isEmpty()) {
            throw new ApiValidationException(errors);
        }
        boolean online = stripe.isEnabledFor(cur);
        CustomerOrder order = orderPlacer.place(cartOf(req.items()), form, cur, online);
        String paymentUrl = online
                ? paymentFlow.startPayment(order, null, siteUrls.baseUrl(), cur == Currency.USD)
                : null;
        return new OrderCreated(order.getOrderNo(), order.getStatus().name(), order.getCurrency(),
                order.getItemsTotal(), order.getShippingFee(), order.getTotal(), paymentUrl);
    }

    private static Cart cartOf(List<CartItem> items) {
        Cart cart = new Cart();
        items.forEach(i -> cart.add(i.productId(), i.quantity()));
        return cart;
    }
}
