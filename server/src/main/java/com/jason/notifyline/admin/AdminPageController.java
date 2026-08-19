package com.jason.notifyline.admin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 把 {@code /admin} 與 {@code /admin/} 導到實際的頁面。
 *
 * <p>Spring Boot 的靜態資源處理只在<strong>網站根目錄</strong>會自動找 index.html，
 * 子目錄不會。少了這個導向，登入成功後會落在一個 404 上 —— 而且因為登入本身
 * 是成功的，看起來會像是權限問題。
 */
@Controller
@ConditionalOnExpression("'${app.admin.username:}'.trim().length() > 0")
public class AdminPageController {

    @GetMapping({"/admin", "/admin/"})
    public String index() {
        return "redirect:/admin/index.html";
    }
}
