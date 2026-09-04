package com.cg.jy.service;

import com.cg.jy.config.SmartCareDataSource;
import com.cg.jy.dto.IdentificationRequest;
import com.cg.jy.dto.IdentificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 患者风险识别服务
 *
 * 数据流程：
 * 1. 请求传入 pidList（patient.mrn 住院号）
 * 2. 查询 SmartCare.patient 表，用 mrn 对应获取 patient.id 和 patient.name
 * 3. 用 patient.id 去查 SmartCare.bedside 表（pid = patient.id）
 * 4. bedside 结构：{ code: "param_xxx", strVal: "高风险", valid: true }
 *
 * 数据库表：
 * - SmartCare.patient（患者表）
 * - SmartCare.bedside（床旁评估表）
 */
@Service
public class IdentificationService {

    private static final Logger logger = LoggerFactory.getLogger(IdentificationService.class);

    private final MongoTemplate smartCareMongo;

    /** 风险类型编码 -> bedside.code 的映射 */
    private static final Map<String, String> RISK_FIELD_MAP = new HashMap<>();

    /** 标准化风险等级 */
    private static final String HIGH_RISK = "高风险";
    private static final String MEDIUM_RISK = "中风险";
    private static final String LOW_RISK = "低风险";

    static {
        RISK_FIELD_MAP.put("TS2-PGD-DDFX", "param_score_patientFallDangerFactorV2");
        RISK_FIELD_MAP.put("TS2-PGD-Braden", "param_yaChuang_score");
        RISK_FIELD_MAP.put("DGHTLX", "param_score_unPlannedCGZYY");
    }

    @Autowired
    public IdentificationService(SmartCareDataSource smartCareDataSource) {
        this.smartCareMongo = smartCareDataSource.template();
    }

    /**
     * 执行风险识别
     *
     * @param request 包含 riskTypeList 和 pidList（mrn 住院号列表）的请求
     * @return 识别结果列表
     */
    public List<IdentificationResult> identify(IdentificationRequest request) {
        List<String> riskTypeList = request.getRiskTypeList();
        List<String> pidList = request.getPidList();

        // 参数校验
        if (riskTypeList == null || riskTypeList.isEmpty()) {
            throw new IllegalArgumentException("riskTypeList 不能为空");
        }
        if (pidList == null || pidList.isEmpty()) {
            throw new IllegalArgumentException("pidList 不能为空");
        }
        for (String riskType : riskTypeList) {
            if (!RISK_FIELD_MAP.containsKey(riskType)) {
                throw new IllegalArgumentException("不支持的风险类型: " + riskType
                        + "，支持的类型: " + RISK_FIELD_MAP.keySet());
            }
        }

        List<IdentificationResult> results = new ArrayList<>();

        // 1. 用 mrn 查询 patient 表，获取 patient.id 和 patient.name
        Map<String, Map> mrnToPatient = batchQueryPatients(pidList);
        if (mrnToPatient.isEmpty()) {
            logger.info("未查询到患者，mrnList={}", pidList);
            for (String mrn : pidList) {
                for (String riskType : riskTypeList) {
                    IdentificationResult r = new IdentificationResult();
                    r.setFxbl(riskType);
                    r.setZyh(mrn);
                    r.setName("");
                    r.setFxdj("");
                    results.add(r);
                }
            }
            return results;
        }

        // 2. 收集所有 patient.id，用于查询 bedside
        List<String> patientIds = new ArrayList<>();
        for (Map patient : mrnToPatient.values()) {
            String id = String.valueOf(patient.get("_id"));
            if (id != null && !id.isEmpty() && !"null".equals(id)) {
                patientIds.add(id);
            }
        }

        if (patientIds.isEmpty()) {
            logger.info("无有效的 patient.id");
            for (String mrn : pidList) {
                for (String riskType : riskTypeList) {
                    IdentificationResult r = new IdentificationResult();
                    r.setFxbl(riskType);
                    r.setZyh(mrn);
                    r.setName("");
                    r.setFxdj("");
                    results.add(r);
                }
            }
            return results;
        }

        // 3. 用 patient.id 查询 bedside 表（pid = patient.id）
        Map<String, Map> latestRecords = batchQueryBedsideRecords(patientIds, riskTypeList);

        // 4. 组装结果
        for (String mrn : pidList) {
            Map patient = mrnToPatient.get(mrn);
            String patientId = patient != null ? String.valueOf(patient.get("_id")) : "";
            String patientName = patient != null ? fmtName(patient.get("name")) : "";

            for (String riskType : riskTypeList) {
                String fieldName = RISK_FIELD_MAP.get(riskType);
                String key = patientId + "|" + fieldName;
                Map record = latestRecords.get(key);

                String fxdj;
                if (record == null) {
                    fxdj = "";
                } else {
                    String rawValue = extractBedsideStrVal(record, fieldName);
                    fxdj = normalizeRiskLevel(riskType, rawValue);
                }

                IdentificationResult result = new IdentificationResult();
                result.setFxbl(riskType);
                result.setZyh(mrn);
                result.setName(patientName);
                result.setFxdj(fxdj);
                results.add(result);
            }
        }

        return results;
    }

    /**
     * 批量查询 patient 表（用 mrn 住院号）
     *
     * @param mrnList 住院号列表
     * @return mrn -> patient 的映射
     */
    private Map<String, Map> batchQueryPatients(List<String> mrnList) {
        Map<String, Map> mrnToPatient = new LinkedHashMap<>();
        int batchSize = 500;

        for (int i = 0; i < mrnList.size(); i += batchSize) {
            int end = Math.min(i + batchSize, mrnList.size());
            List<String> batch = mrnList.subList(i, end);

            Query query = new Query(Criteria.where("mrn").in(batch));
            query.fields()
                    .include("_id")
                    .include("mrn")
                    .include("name");

            List<Map> patients = smartCareMongo.find(query, Map.class, "patient");
            for (Map p : patients) {
                String mrn = String.valueOf(p.get("mrn"));
                if (mrn != null && !mrn.isEmpty()) {
                    mrnToPatient.put(mrn, p);
                }
            }
        }

        logger.info("批量查询患者完成，mrnList.size={}, 命中={}", mrnList.size(), mrnToPatient.size());
        return mrnToPatient;
    }

    /**
     * 批量查询 bedside 表
     *
     * 数据结构：code、strVal、valid 都是顶层字段
     * { pid: "xxx", code: "param_xxx", strVal: "高风险", valid: true, time: ... }
     *
     * @param patientIds   patient._id 列表
     * @param riskTypeList 风险类型列表
     * @return key="patientId|code" -> 最新床旁评估记录
     */
    private Map<String, Map> batchQueryBedsideRecords(List<String> patientIds, List<String> riskTypeList) {
        List<String> fieldNames = new ArrayList<>();
        for (String riskType : riskTypeList) {
            fieldNames.add(RISK_FIELD_MAP.get(riskType));
        }

        List<Map> allRecords = new ArrayList<>();
        int batchSize = 500;

        for (int i = 0; i < patientIds.size(); i += batchSize) {
            int end = Math.min(i + batchSize, patientIds.size());
            List<String> batch = patientIds.subList(i, end);

            // code、strVal、valid 都是顶层字段
            Criteria criteria = new Criteria().andOperator(
                    Criteria.where("pid").in(batch),
                    Criteria.where("code").in(fieldNames),
                    Criteria.where("valid").is(true)
            );

            Query query = new Query(criteria);
            query.fields()
                    .include("pid")
                    .include("code")
                    .include("strVal")
                    .include("time");

            List<Map> records = smartCareMongo.find(query, Map.class, "bedside");
            allRecords.addAll(records);
        }

        logger.info("批量查询护理记录完成，共 {} 条", allRecords.size());

        if (allRecords.isEmpty()) {
            return new HashMap<>();
        }

        // 按 time 降序排序，取每个 pid|code 的最新记录
        allRecords.sort((a, b) -> {
            Date timeA = extractRecordTime(a);
            Date timeB = extractRecordTime(b);
            if (timeA == null && timeB == null) return 0;
            if (timeA == null) return 1;
            if (timeB == null) return -1;
            return timeB.compareTo(timeA);
        });

        LinkedHashMap<String, Map> latestMap = new LinkedHashMap<>();
        for (Map record : allRecords) {
            String pid = String.valueOf(record.get("pid"));
            String code = String.valueOf(record.get("code"));
            if (pid == null || pid.isEmpty() || code == null || code.isEmpty()) {
                continue;
            }
            String key = pid + "|" + code;
            latestMap.putIfAbsent(key, record);
        }

        return latestMap;
    }

    /**
     * 从记录中提取 strVal（风险等级原始值）
     * code、strVal 都是顶层字段
     */
    private String extractBedsideStrVal(Map record, String fieldName) {
        String code = String.valueOf(record.get("code"));
        if (fieldName.equals(code)) {
            Object strVal = record.get("strVal");
            return strVal != null ? String.valueOf(strVal).trim() : "";
        }
        return "";
    }

    /**
     * 标准化风险等级
     */
    String normalizeRiskLevel(String riskType, String rawValue) {
        if (rawValue == null || rawValue.isEmpty()) {
            return "";
        }
        switch (riskType) {
            case "TS2-PGD-DDFX":
                return normalizeFallRisk(rawValue);
            case "TS2-PGD-Braden":
                return normalizeBradenRisk(rawValue);
            case "DGHTLX":
                return normalizeUnplannedExtubationRisk(rawValue);
            default:
                return rawValue;
        }
    }

    private String normalizeFallRisk(String rawValue) {
        if (rawValue.contains("高风险")) return HIGH_RISK;
        if (rawValue.contains("中风险")) return MEDIUM_RISK;
        if (rawValue.contains("低风险")) return LOW_RISK;
        return rawValue;
    }

    private String normalizeBradenRisk(String rawValue) {
        if (rawValue.contains("极高度危险")) return "极高度危险";
        if (rawValue.contains("高度危险")) return "高度危险";
        if (rawValue.contains("中度危险")) return "中度危险";
        if (rawValue.contains("轻度危险")) return "轻度危险";
        return rawValue;
    }

    private String normalizeUnplannedExtubationRisk(String rawValue) {
        if (rawValue.contains("高险") || rawValue.contains("高危")) return HIGH_RISK;
        if (rawValue.contains("中险") || rawValue.contains("中危")) return MEDIUM_RISK;
        if (rawValue.contains("低险") || rawValue.contains("低危")) return LOW_RISK;
        return rawValue;
    }

    private Date extractRecordTime(Map record) {
        Object timeObj = record.get("time");
        if (timeObj == null) return null;
        if (timeObj instanceof Date) return (Date) timeObj;
        String str = String.valueOf(timeObj).trim();
        if (str.isEmpty()) return null;
        try {
            if (str.contains("T") || str.endsWith("Z")) {
                return Date.from(java.time.Instant.parse(str));
            }
        } catch (Exception e) { }
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return sdf.parse(str);
        } catch (Exception e) {
            return null;
        }
    }

    private String fmtName(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? "" : s;
    }
}
