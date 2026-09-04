package com.cg.jy.controller;

import com.cg.jy.dto.IdentificationRequest;
import com.cg.jy.dto.IdentificationResult;
import com.cg.jy.service.IdentificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 患者风险识别接口
 *
 * POST /ICU/getIdentification
 *
 * 根据传入的患者ID列表和风险类型列表，查询SmartCare数据库中的风险评估结果，
 * 返回每个患者每个风险类型的最新风险等级。
 */
@RestController
@RequestMapping("/ICU")
public class IdentificationController {

    private static final Logger logger = LoggerFactory.getLogger(IdentificationController.class);

    private final IdentificationService identificationService;

    @Autowired
    public IdentificationController(IdentificationService identificationService) {
        this.identificationService = identificationService;
    }

    /**
     * 患者风险识别
     *
     * @param request 包含 riskTypeList 和 pidList 的请求体
     * @return 识别结果
     */
    @PostMapping("/getIdentification")
    public Map<String, Object> getIdentification(@RequestBody IdentificationRequest request) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<IdentificationResult> data = identificationService.identify(request);
            result.put("code", 200);
            result.put("data", data);
        } catch (IllegalArgumentException e) {
            logger.warn("参数校验失败: {}", e.getMessage());
            result.put("code", 400);
            result.put("message", e.getMessage());
            result.put("data", new ArrayList<>());
        } catch (Exception e) {
            logger.error("风险识别失败", e);
            result.put("code", 500);
            result.put("message", "风险识别失败，请稍后重试");
            result.put("data", new ArrayList<>());
        }
        return result;
    }
}
