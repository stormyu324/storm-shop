package com.javastorm.shop.api.admin;

import com.javastorm.shop.api.AdminDtos.AdminProduct;
import com.javastorm.shop.api.AdminDtos.BatchRequest;
import com.javastorm.shop.api.AdminDtos.BatchResult;
import com.javastorm.shop.api.AdminDtos.ImageUpload;
import com.javastorm.shop.api.AdminDtos.ProductRequest;
import com.javastorm.shop.api.ApiExceptionHandler.NotFoundException;
import com.javastorm.shop.api.ApiMapper;
import com.javastorm.shop.api.ApiValidationException;
import com.javastorm.shop.api.PageDto;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.domain.ProductStatus;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.service.ImageStorageService;
import com.javastorm.shop.service.ProductImportService;
import com.javastorm.shop.service.ProductImportService.ImportResult;
import com.javastorm.shop.service.ProductService;
import com.javastorm.shop.web.form.ProductForm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@Tag(name = "后台 - 商品")
public class AdminProductApi {

    private final ProductService productService;
    private final ProductRepository products;
    private final ProductImportService importService;
    private final ImageStorageService images;
    private final ApiMapper mapper;
    private final Validator validator;

    public AdminProductApi(ProductService productService, ProductRepository products, ProductImportService importService,
                           ImageStorageService images, ApiMapper mapper, Validator validator) {
        this.productService = productService;
        this.products = products;
        this.importService = importService;
        this.images = images;
        this.mapper = mapper;
        this.validator = validator;
    }

    @GetMapping("/products")
    @Operation(summary = "商品列表（含草稿和下架）", description = "按 ID 倒序；q 搜索名称、英文标题、SKU")
    public PageDto<AdminProduct> list(@RequestParam(required = false) String q,
                                      @RequestParam(required = false) ProductStatus status,
                                      @RequestParam(required = false) Long category,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return PageDto.of(productService.search(q, status, category,
                        PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("id").descending())),
                mapper::adminProduct);
    }

    @GetMapping("/products/{id}")
    @Operation(summary = "商品详情")
    public AdminProduct get(@PathVariable Long id) {
        return mapper.adminProduct(find(id));
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "新建商品", description = "图片先用 POST /api/admin/images 上传，再把返回的地址放进 imageUrls")
    public AdminProduct create(@RequestBody ProductRequest req) {
        return mapper.adminProduct(productService.save(validated(req.toForm(null)), List.of()));
    }

    @PutMapping("/products/{id}")
    @Operation(summary = "修改商品", description = "传入读取时的 version；期间商品被改过（例如有新订单扣了库存）会返回 422，需要重新读取后再改")
    public AdminProduct update(@PathVariable Long id, @RequestBody ProductRequest req) {
        find(id);
        if (req.version() == null) {
            throw new ApiValidationException(Map.of("version", "修改商品时必须传 version"));
        }
        return mapper.adminProduct(productService.save(validated(req.toForm(id)), List.of()));
    }

    @DeleteMapping("/products/{id}")
    @Operation(summary = "删除商品")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        find(id);
        productService.delete(List.of(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/products/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "复制商品", description = "复制为草稿，常用于同款不同颜色 / 尺码")
    public AdminProduct duplicate(@PathVariable Long id) {
        find(id);
        return mapper.adminProduct(productService.duplicate(id));
    }

    @PostMapping("/products/batch")
    @Operation(summary = "批量上架 / 下架 / 删除")
    public BatchResult batch(@Valid @RequestBody BatchRequest req) {
        if ("DELETE".equalsIgnoreCase(req.action())) {
            return new BatchResult(productService.delete(req.ids()));
        }
        ProductStatus status;
        try {
            status = ProductStatus.valueOf(req.action().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiValidationException(Map.of("action", "只能是 ON_SALE、OFF_SHELF、DRAFT 或 DELETE"));
        }
        return new BatchResult(productService.changeStatus(req.ids(), status));
    }

    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传商品图片", description = "可一次上传多张（字段名 files），返回图片地址；jpg / png / gif / webp，单张最大 10MB")
    public ImageUpload uploadImages(@RequestPart("files") List<MultipartFile> files) {
        return new ImageUpload(images.storeAll(files));
    }

    @PostMapping(value = "/products/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "CSV 批量导入", description = "列与后台“批量导入”模板相同；SKU 相同则更新，不同则新建")
    public ImportResult importCsv(@RequestPart("file") MultipartFile file,
                                  @RequestParam(defaultValue = "ON_SALE") ProductStatus defaultStatus) throws IOException {
        if (file.isEmpty()) {
            throw new ApiValidationException(Map.of("file", "请选择 CSV 文件"));
        }
        return importService.importCsv(file.getBytes(), defaultStatus);
    }

    @GetMapping("/products/import/template.csv")
    @Operation(summary = "下载 CSV 导入模板", description = "带 BOM 的 UTF-8，Excel 打开不会乱码")
    public ResponseEntity<byte[]> importTemplate() {
        return Csv.download("商品导入模板.csv", importService.template());
    }

    private Product find(Long id) {
        return products.findById(id).orElseThrow(() -> new NotFoundException("商品不存在"));
    }

    private ProductForm validated(ProductForm form) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<ProductForm> v : validator.validate(form)) {
            errors.putIfAbsent(v.getPropertyPath().toString(), v.getMessage());
        }
        if (!errors.isEmpty()) {
            throw new ApiValidationException(errors);
        }
        return form;
    }
}
