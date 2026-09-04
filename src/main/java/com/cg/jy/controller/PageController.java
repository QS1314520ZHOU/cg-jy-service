package com.cg.jy.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 表单页面路由。
 * 将无扩展名的访问地址转发到对应静态页面。
 */
@Controller
public class PageController {

    /** 呼吸机辅助呼吸视图 */
    @GetMapping("/form/ctrlVentView")
    public String ctrlVentView() {
        return "forward:/form/ctrlVentView.html";
    }
}
