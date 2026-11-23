package com.javastorm.shop.web.admin;

import com.javastorm.shop.service.BusinessException;
import com.javastorm.shop.service.CategoryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/categories")
public class AdminCategoryController {

    private final CategoryService categoryService;

    public AdminCategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("categories", categoryService.listWithCounts());
        return "admin/categories";
    }

    @PostMapping
    public String create(@RequestParam String name, @RequestParam(required = false) String nameEn,
                         @RequestParam(defaultValue = "0") int sortOrder, RedirectAttributes flash) {
        try {
            String n = categoryService.create(name, nameEn, sortOrder).getName();
            flash.addFlashAttribute("message", "已添加分类「" + n + "」");
        } catch (BusinessException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/categories";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @RequestParam String name,
                         @RequestParam(required = false) String nameEn, @RequestParam int sortOrder,
                         RedirectAttributes flash) {
        try {
            categoryService.update(id, name, nameEn, sortOrder);
            flash.addFlashAttribute("message", "已保存");
        } catch (BusinessException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/categories";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        try {
            flash.addFlashAttribute("message", "已删除分类「" + categoryService.delete(id) + "」");
        } catch (BusinessException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/categories";
    }
}
