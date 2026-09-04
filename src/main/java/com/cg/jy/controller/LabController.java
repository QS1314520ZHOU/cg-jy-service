package com.cg.jy.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/lab")
public class LabController {

    private static final Logger logger = LoggerFactory.getLogger(LabController.class);

    @Autowired
    private MongoTemplate mongoTemplate;

    /**
     * 测试数据库连接
     */
    @GetMapping("/test")
    public Map<String, Object> testConnection() {
        Map<String, Object> result = new HashMap<>();
        try {
            // 检查数据库连接
            result.put("status", "connected");
            result.put("database", mongoTemplate.getDb().getName());

            // 获取集合列表
            java.util.Set<String> collections = mongoTemplate.getCollectionNames();
            result.put("collections", collections);

            // 查询 VI_ICU_EXAM 集合数量
            if (collections.contains("VI_ICU_EXAM")) {
                long count = mongoTemplate.count(new Query(), "VI_ICU_EXAM");
                result.put("VI_ICU_EXAM_count", count);
            }

            // 查询 VI_ICU_EXAM_ITEM 集合数量
            if (collections.contains("VI_ICU_EXAM_ITEM")) {
                long count = mongoTemplate.count(new Query(), "VI_ICU_EXAM_ITEM");
                result.put("VI_ICU_EXAM_ITEM_count", count);
            }

            logger.info("数据库测试连接成功, 数据库: {}", mongoTemplate.getDb().getName());
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
            logger.error("数据库连接测试失败", e);
        }
        return result;
    }

    /**
     * 查看 VI_ICU_EXAM 中的一条样本数据
     */
    @GetMapping("/sample")
    public Map<String, Object> getSampleData() {
        Map<String, Object> result = new HashMap<>();
        try {
            // 查询一条样本数据
            Query query = new Query().limit(1);
            List<Map> sample = mongoTemplate.find(query, Map.class, "VI_ICU_EXAM");
            result.put("status", "success");
            result.put("sample", sample);
            result.put("message", "成功获取样本数据");
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 获取检验数据
     * 根据 hisPid 查询 VI_ICU_EXAM 和 VI_ICU_EXAM_ITEM
     */
    @GetMapping("/items")
    public Map<String, Object> getLabItems(@RequestParam String hisPid) {
        Map<String, Object> result = new HashMap<>();

        try {
            // 查询检验大项
            Query examQuery = new Query(Criteria.where("hisPid").is(hisPid));
            examQuery.with(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "authTime"));
            List<Map> exams = mongoTemplate.find(examQuery, Map.class, "VI_ICU_EXAM");

            // 为每个检验大项查询对应的明细项
            List<Map<String, Object>> examList = new ArrayList<>();
            for (Map exam : exams) {
                Map<String, Object> examData = new HashMap<>(exam);

                // 根据 reportID 查询明细项
                String reportID = (String) exam.get("reportID");
                Query itemQuery = new Query(Criteria.where("reportID").is(reportID));
                List<Map> items = mongoTemplate.find(itemQuery, Map.class, "VI_ICU_EXAM_ITEM");

                examData.put("items", items);
                examList.add(examData);
            }

            result.put("code", 200);
            result.put("message", "success");
            result.put("data", examList);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "查询失败: " + e.getMessage());
            result.put("data", new ArrayList<>());
        }

        return result;
    }

    /**
     * 获取趋势数据
     */
    @GetMapping("/trend")
    public Map<String, Object> getTrendData(@RequestParam String hisPid,
                                             @RequestParam String itemName) {
        Map<String, Object> result = new HashMap<>();

        try {
            // 根据 hisPid 和 itemName 查询历史数据
            Query query = new Query(Criteria.where("hisPid").is(hisPid)
                .and("itemName").is(itemName));
            query.with(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.ASC, "authTime"));

            List<Map> items = mongoTemplate.find(query, Map.class, "VI_ICU_EXAM_ITEM");

            result.put("code", 200);
            result.put("message", "success");
            result.put("data", items);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "查询失败: " + e.getMessage());
            result.put("data", new ArrayList<>());
        }

        return result;
    }
}
