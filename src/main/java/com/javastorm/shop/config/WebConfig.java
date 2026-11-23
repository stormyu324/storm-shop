package com.javastorm.shop.config;

import com.javastorm.shop.service.ImageStorageService;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.time.Duration;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 管理后台（Vue 单页应用）打包后的位置，源码在项目根目录的 admin-ui/。 */
    static final String ADMIN = "classpath:/static/admin/";

    private final ImageStorageService images;

    public WebConfig(ImageStorageService images) {
        this.images = images;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 上传的商品图片
        registry.addResourceHandler(ImageStorageService.URL_PREFIX + "**")
                .addResourceLocations(images.getRoot().toUri().toString() + "/");

        // 管理后台：带哈希的静态文件长期缓存
        registry.addResourceHandler("/admin/assets/**")
                .addResourceLocations(ADMIN + "assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic());
        // 其余路径（/admin/products/12 之类的前端路由）都返回 index.html，由 Vue Router 处理
        registry.addResourceHandler("/admin/**")
                .addResourceLocations(ADMIN)
                .setCacheControl(CacheControl.noCache())
                .resourceChain(false)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        Resource file = location.createRelative(path);
                        if (!path.isEmpty() && file.exists() && file.isReadable()) {
                            return file;
                        }
                        Resource index = new ClassPathResource("static/admin/index.html");
                        return index.exists() ? index : null;
                    }
                });
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/admin", "/admin/");
        // 静态资源处理器不接受空路径，首页单独转到 index.html
        registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
        // 试用期间的 /console 地址
        registry.addRedirectViewController("/console", "/admin/");
        registry.addRedirectViewController("/console/**", "/admin/");
    }
}
