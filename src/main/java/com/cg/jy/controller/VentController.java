package com.cg.jy.controller;

import com.cg.jy.config.SmartCareDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PreDestroy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * 呼吸机辅助呼吸视图
 *
 * 判定逻辑（只统计有创）：
 * bedside 表中 code = param_XiYangTuJing 且 strVal = "有创"，
 * 记录时间（北京时间）落在查询当天 0:00:00 ~ 23:59:59 之内，
 * 则认为该患者当天使用了有创呼吸机，进入统计结果；其余患者不展示。
 *
 * 时区说明：bedside.time 存储的是真实 UTC 时刻（如北京时间 13:36 = UTC 05:36），
 * 查询窗口统一用北京时间构造后转成绝对时刻，不依赖 JVM 默认时区。
 */
@RestController
@RequestMapping("/api/vent")
public class VentController {

    private static final Logger logger = LoggerFactory.getLogger(VentController.class);

    /** 判定为使用呼吸机的吸氧途径取值：仅"有创"计入统计 */
    private static final String[] VENT_VALUES = {"有创"};

    /** 北京时区 */
    private static final TimeZone TZ_BEIJING = TimeZone.getTimeZone("Asia/Shanghai");

    /**
     * SmartCare 数据源（本视图专用）。
     * 不是 Spring bean，避免顶掉 Spring Boot 自动配置的主数据源 MongoTemplate（见 SmartCareDataSource 注释）。
     */
    private final SmartCareDataSource smartCareDataSource;
    private final MongoTemplate smartCareMongo;

    public VentController(@Value("${smartcare.mongodb.uri}") String smartCareUri) {
        this.smartCareDataSource = new SmartCareDataSource(smartCareUri);
        this.smartCareMongo = smartCareDataSource.template();
    }

    @PreDestroy
    public void closeDataSource() {
        smartCareDataSource.close();
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
            result.put("message", "查询科室失败: " + e.getMessage());
            result.put("data", new ArrayList<>());
        }
        return result;
    }

    // ============ 患者查询 ============

    /**
     * 查询患者列表（固定统计当天使用了有创呼吸机的患者）
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
        } catch (Exception e) {
            logger.error("呼吸机视图查询失败", e);
            result.put("code", 500);
            result.put("message", "查询失败: " + e.getMessage());
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
        } catch (Exception e) {
            logger.error("导出失败", e);
            return org.springframework.http.ResponseEntity.status(500)
                    .body(("导出失败: " + e.getMessage()).getBytes());
        }
    }

    // ============ 核心查询逻辑 ============

    private List<Map<String, Object>> doQuery(String deptCode, String date) {
        // 1. 查询该科室患者
        Query patQuery = new Query(Criteria.where("deptCode").is(deptCode))
                .with(Sort.by(Sort.Direction.ASC, "icuAdmissionTime"));
        List<Map> patients = smartCareMongo.find(patQuery, Map.class, "patient");
        if (patients.isEmpty()) {
            return new ArrayList<>();
        }

        // pid(字符串) -> patient
        Map<String, Map> pidMap = new LinkedHashMap<>();
        for (Map p : patients) {
            pidMap.put(String.valueOf(p.get("_id")), p);
        }

        // 2. 计算呼吸机记录的查询窗口：查询当天 0:00:00 ~ 23:59:59（北京时间）
        Date lo = beijingDateTime(date, 0, 0, 0);
        Date hi = beijingDateTime(date, 23, 59, 59);

        // 3. 一次查出该日所有呼吸机相关记录（仅取需要的字段）
        Query ventQuery = new Query(new Criteria().andOperator(
                Criteria.where("pid").in(pidMap.keySet()),
                Criteria.where("code").is("param_XiYangTuJing"),
                Criteria.where("strVal").in(java.util.Arrays.asList(VENT_VALUES)),
                Criteria.where("time").gte(lo).lte(hi)));
        ventQuery.fields().include("pid").include("time").include("strVal");
        List<Map> ventRecords = smartCareMongo.find(ventQuery, Map.class, "bedside");

        // 4. 标记当天使用了有创呼吸机的患者（窗口已是整天，无需再按小时过滤）
        Set<String> usedPids = new HashSet<>();
        for (Map r : ventRecords) {
            usedPids.add(String.valueOf(r.get("pid")));
        }

        // 5. 组装结果：只输出当天使用了有创呼吸机的患者
        List<Map<String, Object>> rows = new ArrayList<>();
        int seq = 1;
        for (Map.Entry<String, Map> entry : pidMap.entrySet()) {
            if (!usedPids.contains(entry.getKey())) continue;

            Map p = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", seq++);
            row.put("bedNo", fmtText(p.get("hisBed")));
            row.put("name", p.get("name"));
            row.put("gender", toGender(p.get("gender")));
            row.put("age", calcAge(p.get("birthday")));
            row.put("mrn", fmtText(p.get("mrn") != null ? p.get("mrn") : p.get("hisPid")));
            row.put("icuAdmissionTime", fmtTime(p.get("icuAdmissionTime")));
            row.put("icuDischargeTime", fmtTime(p.get("icuDischargeTime")));
            row.put("diagnosis", formatDiagnosis(p.get("clinicalDiagnosis")));
            rows.add(row);
        }
        return rows;
    }

    /** 构造北京时间下的日期时间，返回对应的绝对时刻（不依赖 JVM 默认时区） */
    private Date beijingDateTime(String dateStr, int hour, int minute, int second) {
        String[] parts = dateStr.split("-");
        Calendar cal = Calendar.getInstance(TZ_BEIJING);
        cal.clear();
        cal.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1,
                Integer.parseInt(parts[2]), hour, minute, second);
        return cal.getTime();
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
     * 例："慢性阻塞性肺病伴有急性加重;慢性阻塞性肺病伴有急性加重|心力衰竭;..."
     *     -> "慢性阻塞性肺病伴有急性加重;慢性阻塞性肺病伴有急性加重"
     */
    private String formatDiagnosis(Object v) {
        if (v == null) return "--";
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return "--";
        int idx = s.indexOf('|');
        if (idx >= 0) {
            String head = s.substring(0, idx).trim();
            // "|" 之前为空（如以 | 开头）时，退化为整段展示
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
            f.setTimeZone(TZ_BEIJING);
            return f.format((Date) v);
        }
        return String.valueOf(v);
    }

    /** 根据出生日期计算年龄（北京时间）。库中 birthday 为 Date 类型，兼容字符串格式 */
    private Integer calcAge(Object birthday) {
        if (birthday == null) return null;
        try {
            Calendar b = Calendar.getInstance(TZ_BEIJING);
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
            Calendar now = Calendar.getInstance(TZ_BEIJING);
            int age = now.get(Calendar.YEAR) - b.get(Calendar.YEAR);
            if (now.get(Calendar.MONTH) < b.get(Calendar.MONTH)
                    || (now.get(Calendar.MONTH) == b.get(Calendar.MONTH)
                        && now.get(Calendar.DAY_OF_MONTH) < b.get(Calendar.DAY_OF_MONTH))) {
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
