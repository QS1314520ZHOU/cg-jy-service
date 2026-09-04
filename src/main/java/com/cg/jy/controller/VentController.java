package com.cg.jy.controller;

import com.cg.jy.config.SmartCareDataSource;
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
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 呼吸机辅助呼吸视图
 *
 * 患者来源：SmartCare.patient
 * 医嘱来源：DataCenter.VI_ICU_ZYYZ
 * 关联字段：只能使用 mrn
 * 目标医嘱：orderName 包含 "气管插管护理" 或 "气管切开护理"
 * 判定方式：患者在科区间与医嘱有效区间相交
 *
 * 时区说明：使用 Asia/Shanghai 构造查询窗口，转换为 UTC 存储的 Date 查询 MongoDB。
 */
@RestController
@RequestMapping("/api/vent")
public class VentController {

    private static final Logger logger = LoggerFactory.getLogger(VentController.class);

    /** 北京时区 */
    private static final ZoneId ZONE_BEIJING = ZoneId.of("Asia/Shanghai");

    /** 医嘱名称匹配正则：包含 "气管插管护理" 或 "气管切开护理" */
    private static final Pattern ORDER_NAME_PATTERN = Pattern.compile(
            ".*(" + Pattern.quote("气管插管护理") + "|" + Pattern.quote("气管切开护理") + ").*"
    );

    /** SmartCare 数据源（Spring 管理的单例 Bean） */
    private final MongoTemplate smartCareMongo;

    /** DataCenter 主数据源（Spring Boot 自动配置） */
    private final MongoTemplate dataCenterMongo;

    /**
     * 构造器注入
     *
     * @param dataCenterMongo    Spring Boot 自动配置的主数据源 MongoTemplate（DataCenter）
     * @param smartCareDataSource SmartCare 数据源（Spring 管理的单例 Bean）
     */
    @Autowired
    public VentController(MongoTemplate dataCenterMongo,
                          SmartCareDataSource smartCareDataSource) {
        this.dataCenterMongo = dataCenterMongo;
        this.smartCareMongo = smartCareDataSource.template();
    }

    // ============ 科室下拉 ============

    /**
     * 科室列表（department 表，当前仅一条，预留多条）
     */
    @GetMapping("/departments")
    public Map<String, Object> getDepartments() {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Map> depts = smartCareMongo.find(new Query(), Map.class, "department");
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map d : depts) {
                Map<String, Object> item = new HashMap<>();
                item.put("code", d.get("code"));
                item.put("name", d.get("name"));
                item.put("shortName", d.get("shortName"));
                list.add(item);
            }
            result.put("code", 200);
            result.put("data", list);
        } catch (Exception e) {
            logger.error("查询科室失败", e);
            result.put("code", 500);
            result.put("message", "查询科室失败");
            result.put("data", new ArrayList<>());
        }
        return result;
    }

    // ============ 患者查询 ============

    /**
     * 查询患者列表（统计当天使用了气管插管护理或气管切开护理医嘱的患者）
     *
     * @param deptCode 科室编码
     * @param date     查询日期 yyyy-MM-dd（只查这一天）
     */
    @GetMapping("/query")
    public Map<String, Object> query(@RequestParam String deptCode,
                                     @RequestParam String date) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Map<String, Object>> rows = doQuery(deptCode, date);
            result.put("code", 200);
            result.put("data", rows);
        } catch (IllegalArgumentException e) {
            logger.warn("参数校验失败: {}", e.getMessage());
            result.put("code", 400);
            result.put("message", e.getMessage());
            result.put("data", new ArrayList<>());
        } catch (Exception e) {
            logger.error("呼吸机视图查询失败", e);
            result.put("code", 500);
            result.put("message", "查询失败，请稍后重试");
            result.put("data", new ArrayList<>());
        }
        return result;
    }

    /**
     * 导出 Excel（xlsx）
     */
    @GetMapping("/export")
    public org.springframework.http.ResponseEntity<byte[]> export(@RequestParam String deptCode,
                                                                  @RequestParam String date) {
        try {
            List<Map<String, Object>> rows = doQuery(deptCode, date);
            byte[] bytes = buildExcel(rows);

            String fileName = "呼吸机辅助呼吸_" + date + ".xlsx";
            String encoded = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20");

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
            headers.set("Content-Type",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            return new org.springframework.http.ResponseEntity<>(bytes, headers,
                    org.springframework.http.HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            logger.warn("参数校验失败: {}", e.getMessage());
            return org.springframework.http.ResponseEntity.status(400)
                    .body(e.getMessage().getBytes());
        } catch (Exception e) {
            logger.error("导出失败", e);
            return org.springframework.http.ResponseEntity.status(500)
                    .body("导出失败，请稍后重试".getBytes());
        }
    }

    // ============ 核心查询逻辑 ============

    /**
     * 核心查询逻辑
     *
     * 流程：
     * 1. 校验 deptCode 和 date
     * 2. 使用 Asia/Shanghai 生成 queryStart/queryEndExclusive
     * 3. 从 SmartCare.patient 查询 deptCode 对应患者
     * 4. 根据 icuAdmissionTime/icuDischargeTime 筛选查询区间内在过科的患者
     * 5. 排除没有有效 mrn 的患者
     * 6. 收集所有候选 MRN
     * 7. 使用 dataCenterMongo 批量查询 VI_ICU_ZYYZ
     * 8. 按 mrn 对医嘱分组
     * 9. 对每个患者计算 effectiveStart/effectiveEndExclusive
     * 10. 检查该 MRN 是否至少存在一条与患者有效在科区间相交的目标医嘱
     * 11. 命中则输出患者；不命中则排除
     * 12. 同一患者命中多条医嘱也只输出一次
     * 13. 保持现有排序：按 icuAdmissionTime 升序
     * 14. 重新生成连续 seq
     *
     * @param deptCode 科室编码
     * @param date     查询日期 yyyy-MM-dd
     * @return 患者列表
     */
    private List<Map<String, Object>> doQuery(String deptCode, String date) {
        // 1. 校验参数
        if (deptCode == null || deptCode.trim().isEmpty()) {
            throw new IllegalArgumentException("科室编码不能为空");
        }
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException("日期格式必须为 yyyy-MM-dd");
        }

        // 2. 使用 Asia/Shanghai 生成查询窗口（左闭右开区间）
        Date[] queryWindow = buildQueryWindow(date);
        Date queryStart = queryWindow[0];
        Date queryEndExclusive = queryWindow[1];

        // 3. 从 SmartCare.patient 查询 deptCode 对应患者
        Query patQuery = new Query(Criteria.where("deptCode").is(deptCode));
        List<Map> patients = smartCareMongo.find(patQuery, Map.class, "patient");
        if (patients.isEmpty()) {
            logger.info("科室 {} 无患者记录", deptCode);
            return new ArrayList<>();
        }

        // 4. 根据 icuAdmissionTime/icuDischargeTime 筛选查询区间内在过科的患者
        List<Map> candidatePatients = filterPatientsByStayOverlap(patients, queryStart, queryEndExclusive);
        if (candidatePatients.isEmpty()) {
            logger.info("科室 {} 在查询日期 {} 无在科患者", deptCode, date);
            return new ArrayList<>();
        }

        // 5. 排除没有有效 mrn 的患者，收集所有候选 MRN
        Set<String> candidateMrns = new HashSet<>();
        Map<String, Map> mrnToPatient = new LinkedHashMap<>();
        for (Map p : candidatePatients) {
            String mrn = extractMrn(p);
            if (mrn != null && !mrn.isEmpty()) {
                candidateMrns.add(mrn);
                mrnToPatient.put(mrn, p);
            } else {
                logger.warn("患者 MRN 为空或无效，跳过");
            }
        }

        if (candidateMrns.isEmpty()) {
            logger.info("无有效 MRN 的候选患者");
            return new ArrayList<>();
        }

        // 6. 使用 dataCenterMongo 批量查询 VI_ICU_ZYYZ
        List<Map> orders = queryOrdersByMrns(candidateMrns, queryStart, queryEndExclusive);
        if (orders.isEmpty()) {
            logger.info("无匹配的目标医嘱");
            return new ArrayList<>();
        }

        // 7. 按 mrn 对医嘱分组
        Map<String, List<Map>> ordersByMrn = orders.stream()
                .collect(Collectors.groupingBy(o -> String.valueOf(o.get("mrn"))));

        // 8. 对每个患者计算 effectiveStart/effectiveEndExclusive 并检查医嘱匹配
        Set<String> matchedMrns = new HashSet<>();
        for (Map.Entry<String, List<Map>> entry : ordersByMrn.entrySet()) {
            String mrn = entry.getKey();
            List<Map> patientOrders = entry.getValue();
            Map patient = mrnToPatient.get(mrn);
            if (patient == null) continue;

            // 计算患者有效在科区间
            Date[] effectiveWindow = calculateEffectiveStayWindow(patient, queryStart, queryEndExclusive);
            if (effectiveWindow == null) {
                // effectiveStart >= effectiveEndExclusive，患者不在有效统计区间内
                continue;
            }
            Date effectiveStart = effectiveWindow[0];
            Date effectiveEndExclusive = effectiveWindow[1];

            // 检查是否有医嘱与患者有效在科区间相交
            if (hasOverlappingOrder(patientOrders, effectiveStart, effectiveEndExclusive)) {
                matchedMrns.add(mrn);
            }
        }

        if (matchedMrns.isEmpty()) {
            logger.info("无匹配的患者");
            return new ArrayList<>();
        }

        // 9. 组装结果：只输出匹配的患者
        List<Map<String, Object>> rows = new ArrayList<>();
        int seq = 1;
        for (Map.Entry<String, Map> entry : mrnToPatient.entrySet()) {
            if (!matchedMrns.contains(entry.getKey())) continue;

            Map p = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", seq++);
            row.put("bedNo", fmtText(p.get("hisBed")));
            row.put("name", p.get("name"));
            row.put("gender", toGender(p.get("gender")));
            row.put("age", calcAge(p.get("birthday")));
            row.put("mrn", fmtText(p.get("mrn")));
            row.put("icuAdmissionTime", fmtTime(p.get("icuAdmissionTime")));
            row.put("icuDischargeTime", fmtTime(p.get("icuDischargeTime")));
            row.put("diagnosis", formatDiagnosis(p.get("clinicalDiagnosis")));
            rows.add(row);
        }

        // 10. 按床号升序排序（床号是String类型，需要转换为数字排序，转换失败的放到最后）
        rows.sort((a, b) -> {
            String bedNoA = String.valueOf(a.get("bedNo"));
            String bedNoB = String.valueOf(b.get("bedNo"));
            Integer numA = parseBedNumber(bedNoA);
            Integer numB = parseBedNumber(bedNoB);

            // 转换失败的放到最后
            if (numA == null && numB == null) return 0;
            if (numA == null) return 1;
            if (numB == null) return -1;

            return numA.compareTo(numB);
        });

        // 11. 重新生成连续 seq
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).put("seq", i + 1);
        }

        return rows;
    }

    /**
     * 构造查询窗口（左闭右开区间）
     *
     * @param date 查询日期 yyyy-MM-dd
     * @return [queryStart, queryEndExclusive]
     */
    Date[] buildQueryWindow(String date) {
        LocalDate localDate = LocalDate.parse(date);
        ZonedDateTime start = localDate.atStartOfDay(ZONE_BEIJING);
        ZonedDateTime end = localDate.plusDays(1).atStartOfDay(ZONE_BEIJING);
        return new Date[]{Date.from(start.toInstant()), Date.from(end.toInstant())};
    }

    /**
     * 根据 icuAdmissionTime/icuDischargeTime 筛选查询区间内在过科的患者
     *
     * 患者在查询范围内在过科的条件为：
     * icuAdmissionTime < queryEndExclusive
     * 并且
     * icuDischargeTime 为空，或者 icuDischargeTime > queryStart
     *
     * @param patients          患者列表
     * @param queryStart        查询开始时间
     * @param queryEndExclusive 查询结束时间（不包含）
     * @return 符合条件的患者列表
     */
    private List<Map> filterPatientsByStayOverlap(List<Map> patients, Date queryStart, Date queryEndExclusive) {
        List<Map> result = new ArrayList<>();
        for (Map p : patients) {
            Object admissionObj = p.get("icuAdmissionTime");
            if (admissionObj == null) {
                logger.warn("患者入科时间为空，跳过");
                continue;
            }

            Date admissionTime = convertToDate(admissionObj);
            if (admissionTime == null) {
                logger.warn("无法解析患者入科时间，跳过");
                continue;
            }

            // 检查入科时间是否在查询结束时间之前
            if (!admissionTime.before(queryEndExclusive)) {
                // 查询日后才入科，排除
                continue;
            }

            // 检查出科时间
            Object dischargeObj = p.get("icuDischargeTime");
            if (dischargeObj == null || dischargeObj.toString().trim().isEmpty()) {
                // 出科时间为空，视为尚未出科，纳入
                result.add(p);
            } else {
                Date dischargeTime = convertToDate(dischargeObj);
                if (dischargeTime == null) {
                    // 无法解析出科时间，视为尚未出科，纳入
                    result.add(p);
                } else {
                    // 出科时间在查询开始时间之后，纳入
                    if (dischargeTime.after(queryStart)) {
                        result.add(p);
                    }
                    // 否则查询日前已出科，排除
                }
            }
        }
        return result;
    }

    /**
     * 提取患者 MRN
     *
     * @param patient 患者信息
     * @return MRN 字符串，如果无效则返回 null
     */
    private String extractMrn(Map patient) {
        Object mrnObj = patient.get("mrn");
        if (mrnObj == null) return null;
        String mrn = String.valueOf(mrnObj).trim();
        if (mrn.isEmpty()) return null;
        // 禁止转成数字，保留前导 0
        return mrn;
    }

    /**
     * 解析床号字符串为数字
     *
     * 床号可能是：
     * - 纯数字："1"、"2"、"10"
     * - 带前缀："ICU-01"、"A01"
     * - 非数字："--"、空字符串
     *
     * @param bedNo 床号字符串
     * @return 解析后的数字，如果无法解析则返回 null
     */
    private Integer parseBedNumber(String bedNo) {
        if (bedNo == null || bedNo.trim().isEmpty() || "--".equals(bedNo)) {
            return null;
        }

        // 尝试直接解析为数字
        try {
            return Integer.parseInt(bedNo.trim());
        } catch (NumberFormatException e) {
            // 继续尝试提取数字部分
        }

        // 尝试提取字符串中的数字部分（如 "ICU-01" -> "01" -> 1）
        String digits = bedNo.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }

        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 批量查询 VI_ICU_ZYYZ 中的医嘱
     *
     * @param mrns              候选 MRN 集合
     * @param queryStart        查询开始时间
     * @param queryEndExclusive 查询结束时间（不包含）
     * @return 医嘱列表
     */
    private List<Map> queryOrdersByMrns(Set<String> mrns, Date queryStart, Date queryEndExclusive) {
        if (mrns.isEmpty()) return new ArrayList<>();

        // 分批查询，每批最多 500 个 MRN
        List<String> mrnList = new ArrayList<>(mrns);
        List<Map> allOrders = new ArrayList<>();
        int batchSize = 500;

        for (int i = 0; i < mrnList.size(); i += batchSize) {
            int end = Math.min(i + batchSize, mrnList.size());
            List<String> batch = mrnList.subList(i, end);

            // 医嘱名称匹配：包含 "气管插管护理" 或 "气管切开护理"
            Criteria nameCriteria = new Criteria().orOperator(
                    Criteria.where("orderName").regex(".*" + Pattern.quote("气管插管护理") + ".*"),
                    Criteria.where("orderName").regex(".*" + Pattern.quote("气管切开护理") + ".*")
            );

            // 医嘱时间范围：orderTime < queryEndExclusive
            Criteria orderTimeCriteria = Criteria.where("orderTime").lt(queryEndExclusive);

            // 停止时间判断：stopTime 不存在/null/空字符串，或者 stopTime >= queryStart
            Criteria stopCriteria = new Criteria().orOperator(
                    Criteria.where("stopTime").exists(false),
                    Criteria.where("stopTime").is(null),
                    Criteria.where("stopTime").is(""),
                    Criteria.where("stopTime").gte(queryStart)
            );

            Criteria criteria = new Criteria().andOperator(
                    Criteria.where("mrn").in(batch),
                    nameCriteria,
                    orderTimeCriteria,
                    stopCriteria
            );

            Query orderQuery = new Query(criteria);
            orderQuery.fields()
                    .include("mrn")
                    .include("orderID")
                    .include("orderName")
                    .include("orderTime")
                    .include("stopTime")
                    .include("status");

            List<Map> orders = dataCenterMongo.find(orderQuery, Map.class, "VI_ICU_ZYYZ");
            allOrders.addAll(orders);
        }

        return allOrders;
    }

    /**
     * 计算患者有效在科区间
     *
     * effectiveStart = max(queryStart, icuAdmissionTime)
     * effectiveEndExclusive = icuDischargeTime 为空 ? queryEndExclusive : min(queryEndExclusive, icuDischargeTime)
     *
     * @param patient           患者信息
     * @param queryStart        查询开始时间
     * @param queryEndExclusive 查询结束时间（不包含）
     * @return [effectiveStart, effectiveEndExclusive]，如果 effectiveStart >= effectiveEndExclusive 则返回 null
     */
    Date[] calculateEffectiveStayWindow(Map patient, Date queryStart, Date queryEndExclusive) {
        Date admissionTime = convertToDate(patient.get("icuAdmissionTime"));
        if (admissionTime == null) return null;

        Date effectiveStart = admissionTime.after(queryStart) ? admissionTime : queryStart;

        Date effectiveEndExclusive;
        Object dischargeObj = patient.get("icuDischargeTime");
        if (dischargeObj == null || dischargeObj.toString().trim().isEmpty()) {
            effectiveEndExclusive = queryEndExclusive;
        } else {
            Date dischargeTime = convertToDate(dischargeObj);
            if (dischargeTime == null) {
                effectiveEndExclusive = queryEndExclusive;
            } else {
                effectiveEndExclusive = dischargeTime.before(queryEndExclusive) ? dischargeTime : queryEndExclusive;
            }
        }

        // 如果 effectiveStart >= effectiveEndExclusive，则患者不在有效统计区间内
        if (!effectiveStart.before(effectiveEndExclusive)) {
            return null;
        }

        return new Date[]{effectiveStart, effectiveEndExclusive};
    }

    /**
     * 检查医嘱是否与患者有效在科区间相交
     *
     * 相交条件：
     * orderTime < effectiveEndExclusive
     * 并且
     * stopTime 为空，或者 stopTime >= effectiveStart
     *
     * @param orders             医嘱列表
     * @param effectiveStart     有效开始时间
     * @param effectiveEndExclusive 有效结束时间（不包含）
     * @return 是否存在相交的医嘱
     */
    boolean hasOverlappingOrder(List<Map> orders, Date effectiveStart, Date effectiveEndExclusive) {
        for (Map order : orders) {
            Date orderTime = convertToDate(order.get("orderTime"));
            if (orderTime == null) continue;

            // 医嘱开始时间必须在有效结束时间之前
            if (!orderTime.before(effectiveEndExclusive)) {
                continue;
            }

            // 检查停止时间
            Object stopTimeObj = order.get("stopTime");
            if (stopTimeObj == null || stopTimeObj.toString().trim().isEmpty()) {
                // 未停止的医嘱，只要 orderTime < effectiveEndExclusive 就相交
                return true;
            } else {
                Date stopTime = convertToDate(stopTimeObj);
                if (stopTime == null) {
                    // 无法解析停止时间，视为未停止
                    return true;
                } else {
                    // 已停止的医嘱，检查 stopTime >= effectiveStart
                    if (!stopTime.before(effectiveStart)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 统一时间转换方法
     *
     * 支持：
     * - java.util.Date
     * - yyyy-MM-dd HH:mm:ss
     * - ISO-8601 字符串
     * - 带 Z 或时区偏移的字符串
     *
     * @param obj 时间对象
     * @return Date 对象，如果无法解析则返回 null
     */
    Date convertToDate(Object obj) {
        if (obj == null) return null;

        if (obj instanceof Date) {
            return (Date) obj;
        }

        String str = String.valueOf(obj).trim();
        if (str.isEmpty()) return null;

        try {
            // 尝试 ISO-8601 格式（带 Z 或时区偏移）
            if (str.contains("T") || str.endsWith("Z")) {
                Instant instant = Instant.parse(str);
                return Date.from(instant);
            }
        } catch (Exception e) {
            // 继续尝试其他格式
        }

        try {
            // 尝试 yyyy-MM-dd HH:mm:ss 格式
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            return sdf.parse(str);
        } catch (Exception e) {
            // 继续尝试其他格式
        }

        try {
            // 尝试 yyyy-MM-dd 格式
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            return sdf.parse(str);
        } catch (Exception e) {
            logger.warn("无法解析时间: {}", str);
            return null;
        }
    }

    /** 性别转换 Male/Female -> 男/女 */
    private String toGender(Object gender) {
        if (gender == null) return "--";
        String g = String.valueOf(gender);
        if ("Male".equalsIgnoreCase(g) || "男".equals(g)) return "男";
        if ("Female".equalsIgnoreCase(g) || "女".equals(g)) return "女";
        return g;
    }

    /**
     * 诊断展示：若诊断文本中含 "|"，只取 "|" 之前的内容；不含 "|" 则整段展示。
     */
    private String formatDiagnosis(Object v) {
        if (v == null) return "--";
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return "--";
        int idx = s.indexOf('|');
        if (idx >= 0) {
            String head = s.substring(0, idx).trim();
            return head.isEmpty() ? s : head;
        }
        return s;
    }

    /** 普通文本格式化：空值统一显示 -- */
    private String fmtText(Object v) {
        if (v == null) return "--";
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? "--" : s;
    }

    /** 时间字段格式化：数据库里多为 "yyyy-MM-dd HH:mm:ss" 字符串，若是 Date 则转北京时间 */
    private String fmtTime(Object v) {
        if (v == null) return "--";
        if (v instanceof Date) {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            return f.format((Date) v);
        }
        return String.valueOf(v);
    }

    /** 根据出生日期计算年龄（北京时间）。库中 birthday 为 Date 类型，兼容字符串格式 */
    private Integer calcAge(Object birthday) {
        if (birthday == null) return null;
        try {
            java.util.Calendar b = java.util.Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
            if (birthday instanceof Date) {
                b.setTime((Date) birthday);
            } else {
                String s = String.valueOf(birthday);
                if (s.length() < 10) return null;
                b.clear();
                b.set(Integer.parseInt(s.substring(0, 4)),
                        Integer.parseInt(s.substring(5, 7)) - 1,
                        Integer.parseInt(s.substring(8, 10)));
            }
            java.util.Calendar now = java.util.Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
            int age = now.get(java.util.Calendar.YEAR) - b.get(java.util.Calendar.YEAR);
            if (now.get(java.util.Calendar.MONTH) < b.get(java.util.Calendar.MONTH)
                    || (now.get(java.util.Calendar.MONTH) == b.get(java.util.Calendar.MONTH)
                    && now.get(java.util.Calendar.DAY_OF_MONTH) < b.get(java.util.Calendar.DAY_OF_MONTH))) {
                age--;
            }
            return age < 0 ? 0 : age;
        } catch (Exception e) {
            return null;
        }
    }

    // ============ Excel 导出 ============

    private byte[] buildExcel(List<Map<String, Object>> rows) throws Exception {
        String[] headers = {"序号", "床号", "姓名", "性别", "年龄", "住院号", "入科时间", "出科时间", "诊断"};
        String[] keys = {"seq", "bedNo", "name", "gender", "age", "mrn",
                "icuAdmissionTime", "icuDischargeTime", "diagnosis"};

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {

            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("呼吸机辅助呼吸");

            // 表头样式：蓝底白字
            org.apache.poi.ss.usermodel.CellStyle headStyle = wb.createCellStyle();
            headStyle.setFillForegroundColor(
                    new org.apache.poi.xssf.usermodel.XSSFColor(new byte[]{(byte) 0x4F, (byte) 0x6D, (byte) 0xBE}, null));
            headStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            headStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
            headStyle.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
            org.apache.poi.ss.usermodel.Font headFont = wb.createFont();
            headFont.setBold(true);
            headFont.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());
            headStyle.setFont(headFont);

            // 表头行
            org.apache.poi.ss.usermodel.Row headRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                org.apache.poi.ss.usermodel.Cell c = headRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headStyle);
                sheet.setColumnWidth(i, i == headers.length - 1 ? 40 * 256 : 16 * 256);
            }

            // 数据行
            SimpleDateFormat outFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            for (int r = 0; r < rows.size(); r++) {
                Map<String, Object> data = rows.get(r);
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r + 1);
                for (int i = 0; i < keys.length; i++) {
                    Object v = data.get(keys[i]);
                    String text = (v == null) ? "" : String.valueOf(v);
                    // 时间字段格式化（原始可能是 Date 或字符串）
                    if (v instanceof Date) {
                        text = outFmt.format((Date) v);
                    } else if (v == null) {
                        text = "";
                    }
                    row.createCell(i).setCellValue(text);
                }
            }

            wb.write(out);
            return out.toByteArray();
        }
    }
}
