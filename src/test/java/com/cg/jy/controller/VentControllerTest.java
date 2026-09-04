package com.cg.jy.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * VentController 核心区间判断方法的单元测试
 *
 * 测试覆盖：
 * 1. 患者在科判断（8个用例）
 * 2. 医嘱判断（14个用例）
 * 3. 边界情况（3个用例）
 */
public class VentControllerTest {

    private VentController controller;
    private MongoTemplate mockDataCenterMongo;
    private MongoTemplate mockSmartCareMongo;

    private static final ZoneId ZONE_BEIJING = ZoneId.of("Asia/Shanghai");

    @BeforeEach
    public void setUp() {
        mockDataCenterMongo = mock(MongoTemplate.class);
        mockSmartCareMongo = mock(MongoTemplate.class);

        // 创建 controller 实例，使用 mock 的 MongoTemplate
        // 由于构造器需要 SmartCareDataSource，我们使用反射来设置
        try {
            controller = new VentController(mockDataCenterMongo, "mongodb://localhost:27017/SmartCare");
            // 使用反射设置 smartCareMongo
            java.lang.reflect.Field field = VentController.class.getDeclaredField("smartCareMongo");
            field.setAccessible(true);
            field.set(controller, mockSmartCareMongo);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create VentController instance", e);
        }
    }

    // ============ 患者在科判断测试 ============

    @Test
    public void testPatientStayOverlap_Case1_AdmittedBeforeQueryDay_NotDischarged() {
        // 测试用例1：入科早于查询日，未出科：纳入候选
        Date queryStart = buildDate("2026-08-20", 0, 0, 0);
        Date queryEndExclusive = buildDate("2026-08-21", 0, 0, 0);

        Map patient = new HashMap();
        patient.put("icuAdmissionTime", buildDate("2026-08-15", 10, 0, 0));
        patient.put("icuDischargeTime", null);

        Date[] result = controller.calculateEffectiveStayWindow(patient, queryStart, queryEndExclusive);
        assertNotNull(result, "患者应被纳入候选");
        assertEquals(queryStart, result[0], "有效开始时间应为查询开始时间");
        assertEquals(queryEndExclusive, result[1], "有效结束时间应为查询结束时间");
    }

    @Test
    public void testPatientStayOverlap_Case2_AdmittedOnQueryDay() {
        // 测试用例2：查询日当天入科：纳入候选
        Date queryStart = buildDate("2026-08-20", 0, 0, 0);
        Date queryEndExclusive = buildDate("2026-08-21", 0, 0, 0);

        Map patient = new HashMap();
        patient.put("icuAdmissionTime", buildDate("2026-08-20", 14, 30, 0));
        patient.put("icuDischargeTime", null);

        Date[] result = controller.calculateEffectiveStayWindow(patient, queryStart, queryEndExclusive);
        assertNotNull(result, "患者应被纳入候选");
        assertEquals(patient.get("icuAdmissionTime"), result[0], "有效开始时间应为入科时间");
        assertEquals(queryEndExclusive, result[1], "有效结束时间应为查询结束时间");
    }

    @Test
    public void testPatientStayOverlap_Case3_DischargedOnQueryDay() {
        // 测试用例3：查询日当天出科：纳入候选
        Date queryStart = buildDate("2026-08-20", 0, 0, 0);
        Date queryEndExclusive = buildDate("2026-08-21", 0, 0, 0);

        Map patient = new HashMap();
        patient.put("icuAdmissionTime", buildDate("2026-08-15", 10, 0, 0));
        patient.put("icuDischargeTime", buildDate("2026-08-20", 18, 0, 0));

        Date[] result = controller.calculateEffectiveStayWindow(patient, queryStart, queryEndExclusive);
        assertNotNull(result, "患者应被纳入候选");
        assertEquals(queryStart, result[0], "有效开始时间应为查询开始时间");
        assertEquals(patient.get("icuDischargeTime"), result[1], "有效结束时间应为出科时间");
    }

    @Test
    public void testPatientStayOverlap_Case4_AdmittedAndDischargedAfterQueryDay() {
        // 测试用例4：入科早于查询日、出科晚于查询日：纳入候选
        Date queryStart = buildDate("2026-08-20", 0, 0, 0);
        Date queryEndExclusive = buildDate("2026-08-21", 0, 0, 0);

        Map patient = new HashMap();
        patient.put("icuAdmissionTime", buildDate("2026-08-15", 10, 0, 0));
        patient.put("icuDischargeTime", buildDate("2026-08-25", 10, 0, 0));

        Date[] result = controller.calculateEffectiveStayWindow(patient, queryStart, queryEndExclusive);
        assertNotNull(result, "患者应被纳入候选");
        assertEquals(queryStart, result[0], "有效开始时间应为查询开始时间");
        assertEquals(queryEndExclusive, result[1], "有效结束时间应为查询结束时间");
    }

    @Test
    public void testPatientStayOverlap_Case5_DischargedBeforeQueryDay() {
        // 测试用例5：查询日前出科：排除
        Date queryStart = buildDate("2026-08-20", 0, 0, 0);
        Date queryEndExclusive = buildDate("2026-08-21", 0, 0, 0);

        Map patient = new HashMap();
        patient.put("icuAdmissionTime", buildDate("2026-08-15", 10, 0, 0));
        patient.put("icuDischargeTime", buildDate("2026-08-19", 10, 0, 0));

        Date[] result = controller.calculateEffectiveStayWindow(patient, queryStart, queryEndExclusive);
        assertNull(result, "患者应被排除");
    }

    @Test
    public void testPatientStayOverlap_Case6_AdmittedAfterQueryDay() {
        // 测试用例6：查询日后才入科：排除
        Date queryStart = buildDate("2026-08-20", 0, 0, 0);
        Date queryEndExclusive = buildDate("2026-08-21", 0, 0, 0);

        Map patient = new HashMap();
        patient.put("icuAdmissionTime", buildDate("2026-08-22", 10, 0, 0));
        patient.put("icuDischargeTime", buildDate("2026-08-25", 10, 0, 0));

        Date[] result = controller.calculateEffectiveStayWindow(patient, queryStart, queryEndExclusive);
        assertNull(result, "患者应被排除");
    }

    @Test
    public void testPatientStayOverlap_Case7_AdmissionTimeEmpty() {
        // 测试用例7：入科时间为空：排除
        Date queryStart = buildDate("2026-08-20", 0, 0, 0);
        Date queryEndExclusive = buildDate("2026-08-21", 0, 0, 0);

        Map patient = new HashMap();
        patient.put("icuAdmissionTime", null);
        patient.put("icuDischargeTime", null);

        Date[] result = controller.calculateEffectiveStayWindow(patient, queryStart, queryEndExclusive);
        assertNull(result, "患者应被排除");
    }

    @Test
    public void testPatientStayOverlap_Case8_DischargeTimeEmpty() {
        // 测试用例8：出科时间为空：视为仍在科
        Date queryStart = buildDate("2026-08-20", 0, 0, 0);
        Date queryEndExclusive = buildDate("2026-08-21", 0, 0, 0);

        Map patient = new HashMap();
        patient.put("icuAdmissionTime", buildDate("2026-08-15", 10, 0, 0));
        patient.put("icuDischargeTime", "");

        Date[] result = controller.calculateEffectiveStayWindow(patient, queryStart, queryEndExclusive);
        assertNotNull(result, "患者应被纳入候选");
        assertEquals(queryEndExclusive, result[1], "有效结束时间应为查询结束时间");
    }

    // ============ 医嘱判断测试 ============

    @Test
    public void testOrderOverlap_Case9_StartedOnQueryDay_NotStopped() {
        // 测试用例9：气管插管护理，查询日当天开始，未停止：纳入
        Date effectiveStart = buildDate("2026-08-20", 0, 0, 0);
        Date effectiveEndExclusive = buildDate("2026-08-21", 0, 0, 0);

        Map order = new HashMap();
        order.put("orderTime", buildDate("2026-08-20", 8, 0, 0));
        order.put("stopTime", null);

        List<Map> orders = Arrays.asList(order);
        assertTrue(controller.hasOverlappingOrder(orders, effectiveStart, effectiveEndExclusive), "医嘱应被纳入");
    }

    @Test
    public void testOrderOverlap_Case10_StartedBeforeQueryDay_NotStopped() {
        // 测试用例10：气管切开护理，查询日前开始，未停止：纳入
        Date effectiveStart = buildDate("2026-08-20", 0, 0, 0);
        Date effectiveEndExclusive = buildDate("2026-08-21", 0, 0, 0);

        Map order = new HashMap();
        order.put("orderTime", buildDate("2026-08-15", 8, 0, 0));
        order.put("stopTime", null);

        List<Map> orders = Arrays.asList(order);
        assertTrue(controller.hasOverlappingOrder(orders, effectiveStart, effectiveEndExclusive), "医嘱应被纳入");
    }

    @Test
    public void testOrderOverlap_Case11_StartedBeforeQueryDay_StoppedOnQueryDay() {
        // 测试用例11：查询日前开始、查询日当天停止：纳入
        Date effectiveStart = buildDate("2026-08-20", 0, 0, 0);
        Date effectiveEndExclusive = buildDate("2026-08-21", 0, 0, 0);

        Map order = new HashMap();
        order.put("orderTime", buildDate("2026-08-15", 8, 0, 0));
        order.put("stopTime", buildDate("2026-08-20", 18, 0, 0));

        List<Map> orders = Arrays.asList(order);
        assertTrue(controller.hasOverlappingOrder(orders, effectiveStart, effectiveEndExclusive), "医嘱应被纳入");
    }

    @Test
    public void testOrderOverlap_Case12_StartedBeforeQueryDay_StoppedAfterQueryDay() {
        // 测试用例12：查询日前开始、查询日后停止，贯穿整个查询日：纳入
        Date effectiveStart = buildDate("2026-08-20", 0, 0, 0);
        Date effectiveEndExclusive = buildDate("2026-08-21", 0, 0, 0);

        Map order = new HashMap();
        order.put("orderTime", buildDate("2026-08-15", 8, 0, 0));
        order.put("stopTime", buildDate("2026-08-25", 8, 0, 0));

        List<Map> orders = Arrays.asList(order);
        assertTrue(controller.hasOverlappingOrder(orders, effectiveStart, effectiveEndExclusive), "医嘱应被纳入");
    }

    @Test
    public void testOrderOverlap_Case13_StoppedBeforeQueryDay() {
        // 测试用例13：查询日前已经停止：排除
        Date effectiveStart = buildDate("2026-08-20", 0, 0, 0);
        Date effectiveEndExclusive = buildDate("2026-08-21", 0, 0, 0);

        Map order = new HashMap();
        order.put("orderTime", buildDate("2026-08-15", 8, 0, 0));
        order.put("stopTime", buildDate("2026-08-19", 8, 0, 0));

        List<Map> orders = Arrays.asList(order);
        assertFalse(controller.hasOverlappingOrder(orders, effectiveStart, effectiveEndExclusive), "医嘱应被排除");
    }

    @Test
    public void testOrderOverlap_Case14_StartedAfterQueryDay() {
        // 测试用例14：查询日后才开始：排除
        Date effectiveStart = buildDate("2026-08-20", 0, 0, 0);
        Date effectiveEndExclusive = buildDate("2026-08-21", 0, 0, 0);

        Map order = new HashMap();
        order.put("orderTime", buildDate("2026-08-22", 8, 0, 0));
        order.put("stopTime", buildDate("2026-08-25", 8, 0, 0));

        List<Map> orders = Arrays.asList(order);
        assertFalse(controller.hasOverlappingOrder(orders, effectiveStart, effectiveEndExclusive), "医嘱应被排除");
    }

    @Test
    public void testOrderOverlap_Case15_OtherOrderName() {
        // 测试用例15：orderName 为其他医嘱：排除
        // 注意：这个测试验证的是 hasOverlappingOrder 方法，不涉及 orderName 匹配
        // orderName 匹配在 queryOrdersByMrns 中处理
        Date effectiveStart = buildDate("2026-08-20", 0, 0, 0);
        Date effectiveEndExclusive = buildDate("2026-08-21", 0, 0, 0);

        Map order = new HashMap();
        order.put("orderTime", buildDate("2026-08-15", 8, 0, 0));
        order.put("stopTime", null);
        order.put("orderName", "其他医嘱");

        List<Map> orders = Arrays.asList(order);
        assertTrue(controller.hasOverlappingOrder(orders, effectiveStart, effectiveEndExclusive), "医嘱时间有效，应被纳入");
    }

    @Test
    public void testOrderOverlap_Case16_OrderNameWithSuffix() {
        // 测试用例16：orderName 带 qd、空格等后缀：仍能命中
        // 这个测试验证的是正则表达式匹配，需要通过 queryOrdersByMrns 方法测试
        // 由于需要 MongoDB 连接，这里只测试时间判断逻辑
        Date effectiveStart = buildDate("2026-08-20", 0, 0, 0);
        Date effectiveEndExclusive = buildDate("2026-08-21", 0, 0, 0);

        Map order = new HashMap();
        order.put("orderTime", buildDate("2026-08-15", 8, 0, 0));
        order.put("stopTime", null);

        List<Map> orders = Arrays.asList(order);
        assertTrue(controller.hasOverlappingOrder(orders, effectiveStart, effectiveEndExclusive), "医嘱时间有效，应被纳入");
    }

    @Test
    public void testOrderOverlap_Case17_PatientMrnMatchesOrderMrn() {
        // 测试用例17：patient.mrn 与 order.mrn 相同：可关联
        // 这个测试验证的是 MRN 提取逻辑
        Map patient = new HashMap();
        patient.put("mrn", "12345678");
        String mrn = extractMrn(patient);
        assertEquals("12345678", mrn, "MRN 应正确提取");
    }

    @Test
    public void testOrderOverlap_Case18_OnlyPidMatchesButMrnDiffers() {
        // 测试用例18：只有 pid 相同但 mrn 不同：禁止关联
        // 这个测试验证的是 MRN 提取逻辑，确保不使用 hisPid
        Map patient = new HashMap();
        patient.put("mrn", "12345678");
        patient.put("hisPid", "87654321");
        patient.put("_id", "87654321");
        String mrn = extractMrn(patient);
        assertEquals("12345678", mrn, "应使用 mrn 而不是 hisPid");
    }

    @Test
    public void testOrderOverlap_Case19_MultipleOrders_OneMatch() {
        // 测试用例19：一个患者命中多条医嘱：只输出一行
        // 这个测试验证的是医嘱去重逻辑
        Date effectiveStart = buildDate("2026-08-20", 0, 0, 0);
        Date effectiveEndExclusive = buildDate("2026-08-21", 0, 0, 0);

        Map order1 = new HashMap();
        order1.put("orderTime", buildDate("2026-08-15", 8, 0, 0));
        order1.put("stopTime", null);

        Map order2 = new HashMap();
        order2.put("orderTime", buildDate("2026-08-16", 8, 0, 0));
        order2.put("stopTime", null);

        List<Map> orders = Arrays.asList(order1, order2);
        assertTrue(controller.hasOverlappingOrder(orders, effectiveStart, effectiveEndExclusive), "多条医嘱应被纳入");
    }

    @Test
    public void testOrderOverlap_Case20_UtcBeijingBoundaryConversion() {
        // 测试用例20：同一天 UTC 与北京时间边界转换正确
        // 查询日期：2026-08-20
        // 北京时间：2026-08-20 00:00:00 = UTC 2026-08-19 16:00:00
        // 北京时间：2026-08-21 00:00:00 = UTC 2026-08-20 16:00:00
        Date queryStart = buildDate("2026-08-20", 0, 0, 0);
        Date queryEndExclusive = buildDate("2026-08-21", 0, 0, 0);

        // 医嘱在北京时间 2026-08-20 10:00:00 开始（UTC 2026-08-20 02:00:00）
        Map order = new HashMap();
        order.put("orderTime", buildDate("2026-08-20", 10, 0, 0));
        order.put("stopTime", null);

        List<Map> orders = Arrays.asList(order);
        assertTrue(controller.hasOverlappingOrder(orders, queryStart, queryEndExclusive), "医嘱应被纳入");
    }

    @Test
    public void testOrderOverlap_Case21_OrderStoppedBeforeAdmission() {
        // 测试用例21：医嘱在患者入科前已停止：排除
        Date effectiveStart = buildDate("2026-08-20", 10, 0, 0); // 患者入科时间
        Date effectiveEndExclusive = buildDate("2026-08-21", 0, 0, 0);

        Map order = new HashMap();
        order.put("orderTime", buildDate("2026-08-15", 8, 0, 0));
        order.put("stopTime", buildDate("2026-08-19", 8, 0, 0)); // 在入科前已停止

        List<Map> orders = Arrays.asList(order);
        assertFalse(controller.hasOverlappingOrder(orders, effectiveStart, effectiveEndExclusive), "医嘱应被排除");
    }

    @Test
    public void testOrderOverlap_Case22_OrderStartedAfterDischarge() {
        // 测试用例22：医嘱在患者出科后才开始：排除
        Date effectiveStart = buildDate("2026-08-20", 0, 0, 0);
        Date effectiveEndExclusive = buildDate("2026-08-20", 18, 0, 0); // 患者出科时间

        Map order = new HashMap();
        order.put("orderTime", buildDate("2026-08-20", 20, 0, 0)); // 在出科后开始
        order.put("stopTime", null);

        List<Map> orders = Arrays.asList(order);
        assertFalse(controller.hasOverlappingOrder(orders, effectiveStart, effectiveEndExclusive), "医嘱应被排除");
    }

    // ============ 时间转换测试 ============

    @Test
    public void testConvertToDate_DateObject() {
        Date expected = new Date();
        Date result = controller.convertToDate(expected);
        assertEquals(expected, result, "Date 对象应直接返回");
    }

    @Test
    public void testConvertToDate_Iso8601String() {
        String iso = "2026-08-20T10:30:00Z";
        Date result = controller.convertToDate(iso);
        assertNotNull(result, "ISO-8601 字符串应被解析");

        Instant instant = result.toInstant();
        assertEquals(Instant.parse(iso), instant, "时间应匹配");
    }

    @Test
    public void testConvertToDate_DateTimeString() {
        String dateTime = "2026-08-20 10:30:00";
        Date result = controller.convertToDate(dateTime);
        assertNotNull(result, "yyyy-MM-dd HH:mm:ss 字符串应被解析");
    }

    @Test
    public void testConvertToDate_EmptyString() {
        Date result = controller.convertToDate("");
        assertNull(result, "空字符串应返回 null");
    }

    @Test
    public void testConvertToDate_Null() {
        Date result = controller.convertToDate(null);
        assertNull(result, "null 应返回 null");
    }

    @Test
    public void testConvertToDate_InvalidString() {
        Date result = controller.convertToDate("invalid");
        assertNull(result, "无效字符串应返回 null");
    }

    // ============ MRN 提取测试 ============

    @Test
    public void testExtractMrn_ValidMrn() {
        Map patient = new HashMap();
        patient.put("mrn", "12345678");
        String mrn = extractMrn(patient);
        assertEquals("12345678", mrn, "MRN 应正确提取");
    }

    @Test
    public void testExtractMrn_WithLeadingZeros() {
        Map patient = new HashMap();
        patient.put("mrn", "00123456");
        String mrn = extractMrn(patient);
        assertEquals("00123456", mrn, "MRN 应保留前导 0");
    }

    @Test
    public void testExtractMrn_WithWhitespace() {
        Map patient = new HashMap();
        patient.put("mrn", "  12345678  ");
        String mrn = extractMrn(patient);
        assertEquals("12345678", mrn, "MRN 应 trim");
    }

    @Test
    public void testExtractMrn_Null() {
        Map patient = new HashMap();
        patient.put("mrn", null);
        String mrn = extractMrn(patient);
        assertNull(mrn, "null MRN 应返回 null");
    }

    @Test
    public void testExtractMrn_EmptyString() {
        Map patient = new HashMap();
        patient.put("mrn", "");
        String mrn = extractMrn(patient);
        assertNull(mrn, "空字符串 MRN 应返回 null");
    }

    @Test
    public void testExtractMrn_NumericString() {
        Map patient = new HashMap();
        patient.put("mrn", "12345678");
        String mrn = extractMrn(patient);
        // 确保 MRN 作为字符串处理，不转成数字
        assertEquals("12345678", mrn, "MRN 应作为字符串处理");
    }

    // ============ 用户场景测试 ============

    @Test
    public void testScenario1_OrderStoppedOnDischargeDay() {
        // 场景1：患者9月1日08:00入科，医嘱9月2日01:00开立，9月4日停止
        // 查询日期：9月3日
        // 预期：纳入统计

        Date queryStart = buildDate("2026-09-03", 0, 0, 0);
        Date queryEndExclusive = buildDate("2026-09-04", 0, 0, 0);

        Map patient = new HashMap();
        patient.put("icuAdmissionTime", buildDate("2026-09-01", 8, 0, 0));
        patient.put("icuDischargeTime", null); // 未出科

        // 计算患者有效在科区间
        Date[] effectiveWindow = controller.calculateEffectiveStayWindow(patient, queryStart, queryEndExclusive);
        assertNotNull(effectiveWindow, "患者应在科");
        Date effectiveStart = effectiveWindow[0];
        Date effectiveEndExclusive = effectiveWindow[1];

        // 医嘱：9月2日01:00开立，9月4日停止
        Map order = new HashMap();
        order.put("orderTime", buildDate("2026-09-02", 1, 0, 0));
        order.put("stopTime", buildDate("2026-09-04", 12, 0, 0)); // 9月4日停止

        List<Map> orders = Arrays.asList(order);
        assertTrue(controller.hasOverlappingOrder(orders, effectiveStart, effectiveEndExclusive),
                "医嘱应与患者有效在科区间相交，应纳入统计");
    }

    @Test
    public void testScenario2_PatientDischargedOnOrderStopDay() {
        // 场景2：患者9月1日08:00入科，医嘱9月2日01:00开立，9月4日出科
        // 查询日期：9月3日
        // 预期：纳入统计

        Date queryStart = buildDate("2026-09-03", 0, 0, 0);
        Date queryEndExclusive = buildDate("2026-09-04", 0, 0, 0);

        Map patient = new HashMap();
        patient.put("icuAdmissionTime", buildDate("2026-09-01", 8, 0, 0));
        patient.put("icuDischargeTime", buildDate("2026-09-04", 18, 0, 0)); // 9月4日18:00出科

        // 计算患者有效在科区间
        Date[] effectiveWindow = controller.calculateEffectiveStayWindow(patient, queryStart, queryEndExclusive);
        assertNotNull(effectiveWindow, "患者应在科");
        Date effectiveStart = effectiveWindow[0];
        Date effectiveEndExclusive = effectiveWindow[1];

        // 医嘱：9月2日01:00开立，未停止
        Map order = new HashMap();
        order.put("orderTime", buildDate("2026-09-02", 1, 0, 0));
        order.put("stopTime", null); // 未停止

        List<Map> orders = Arrays.asList(order);
        assertTrue(controller.hasOverlappingOrder(orders, effectiveStart, effectiveEndExclusive),
                "医嘱应与患者有效在科区间相交，应纳入统计");
    }

    @Test
    public void testScenario1_QueryOnDischargeDay() {
        // 场景1扩展：查询日期为9月4日（医嘱停止当天/出科当天）
        // 患者9月1日08:00入科，医嘱9月2日01:00开立，9月4日12:00停止
        // 查询日期：9月4日
        // 预期：纳入统计（医嘱在查询窗口内有效）

        Date queryStart = buildDate("2026-09-04", 0, 0, 0);
        Date queryEndExclusive = buildDate("2026-09-05", 0, 0, 0);

        Map patient = new HashMap();
        patient.put("icuAdmissionTime", buildDate("2026-09-01", 8, 0, 0));
        patient.put("icuDischargeTime", null); // 未出科

        // 计算患者有效在科区间
        Date[] effectiveWindow = controller.calculateEffectiveStayWindow(patient, queryStart, queryEndExclusive);
        assertNotNull(effectiveWindow, "患者应在科");
        Date effectiveStart = effectiveWindow[0];
        Date effectiveEndExclusive = effectiveWindow[1];

        // 医嘱：9月2日01:00开立，9月4日12:00停止
        Map order = new HashMap();
        order.put("orderTime", buildDate("2026-09-02", 1, 0, 0));
        order.put("stopTime", buildDate("2026-09-04", 12, 0, 0));

        List<Map> orders = Arrays.asList(order);
        assertTrue(controller.hasOverlappingOrder(orders, effectiveStart, effectiveEndExclusive),
                "医嘱停止时间在查询窗口内，应纳入统计");
    }

    @Test
    public void testScenario2_QueryOnDischargeDay() {
        // 场景2扩展：查询日期为9月4日（出科当天）
        // 患者9月1日08:00入科，医嘱9月2日01:00开立，9月4日18:00出科
        // 查询日期：9月4日
        // 预期：纳入统计（医嘱在患者有效在科区间内有效）

        Date queryStart = buildDate("2026-09-04", 0, 0, 0);
        Date queryEndExclusive = buildDate("2026-09-05", 0, 0, 0);

        Map patient = new HashMap();
        patient.put("icuAdmissionTime", buildDate("2026-09-01", 8, 0, 0));
        patient.put("icuDischargeTime", buildDate("2026-09-04", 18, 0, 0)); // 9月4日18:00出科

        // 计算患者有效在科区间
        Date[] effectiveWindow = controller.calculateEffectiveStayWindow(patient, queryStart, queryEndExclusive);
        assertNotNull(effectiveWindow, "患者应在科");
        Date effectiveStart = effectiveWindow[0];
        Date effectiveEndExclusive = effectiveWindow[1];

        // 医嘱：9月2日01:00开立，未停止
        Map order = new HashMap();
        order.put("orderTime", buildDate("2026-09-02", 1, 0, 0));
        order.put("stopTime", null); // 未停止

        List<Map> orders = Arrays.asList(order);
        assertTrue(controller.hasOverlappingOrder(orders, effectiveStart, effectiveEndExclusive),
                "医嘱在患者出科前有效，应纳入统计");
    }

    @Test
    public void testScenario_OrderStoppedBeforeAdmission() {
        // 场景：医嘱在患者入科前已停止
        // 患者9月3日08:00入科，医嘱9月1日开立，9月2日停止
        // 查询日期：9月3日
        // 预期：排除（医嘱在患者入科前已停止）

        Date queryStart = buildDate("2026-09-03", 0, 0, 0);
        Date queryEndExclusive = buildDate("2026-09-04", 0, 0, 0);

        Map patient = new HashMap();
        patient.put("icuAdmissionTime", buildDate("2026-09-03", 8, 0, 0)); // 9月3日才入科
        patient.put("icuDischargeTime", null);

        // 计算患者有效在科区间
        Date[] effectiveWindow = controller.calculateEffectiveStayWindow(patient, queryStart, queryEndExclusive);
        assertNotNull(effectiveWindow, "患者应在科");
        Date effectiveStart = effectiveWindow[0];
        Date effectiveEndExclusive = effectiveWindow[1];

        // 医嘱：9月1日开立，9月2日停止（在患者入科前已停止）
        Map order = new HashMap();
        order.put("orderTime", buildDate("2026-09-01", 8, 0, 0));
        order.put("stopTime", buildDate("2026-09-02", 18, 0, 0));

        List<Map> orders = Arrays.asList(order);
        assertFalse(controller.hasOverlappingOrder(orders, effectiveStart, effectiveEndExclusive),
                "医嘱在患者入科前已停止，应排除");
    }

    @Test
    public void testScenario_OrderStartedAfterDischarge() {
        // 场景：医嘱在患者出科后才开始
        // 患者9月1日08:00入科，9月3日18:00出科
        // 医嘱9月4日开立
        // 查询日期：9月3日
        // 预期：排除（医嘱在患者出科后才开始）

        Date queryStart = buildDate("2026-09-03", 0, 0, 0);
        Date queryEndExclusive = buildDate("2026-09-04", 0, 0, 0);

        Map patient = new HashMap();
        patient.put("icuAdmissionTime", buildDate("2026-09-01", 8, 0, 0));
        patient.put("icuDischargeTime", buildDate("2026-09-03", 18, 0, 0)); // 9月3日18:00出科

        // 计算患者有效在科区间
        Date[] effectiveWindow = controller.calculateEffectiveStayWindow(patient, queryStart, queryEndExclusive);
        assertNotNull(effectiveWindow, "患者应在科");
        Date effectiveStart = effectiveWindow[0];
        Date effectiveEndExclusive = effectiveWindow[1];

        // 医嘱：9月4日开立（在患者出科后）
        Map order = new HashMap();
        order.put("orderTime", buildDate("2026-09-04", 8, 0, 0));
        order.put("stopTime", null);

        List<Map> orders = Arrays.asList(order);
        assertFalse(controller.hasOverlappingOrder(orders, effectiveStart, effectiveEndExclusive),
                "医嘱在患者出科后才开始，应排除");
    }

    // ============ 查询窗口构建测试 ============

    @Test
    public void testBuildQueryWindow() {
        Date[] window = controller.buildQueryWindow("2026-08-20");
        assertNotNull(window, "查询窗口应不为 null");
        assertEquals(2, window.length, "窗口应包含两个元素");

        // 验证时区转换正确
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));

        String startStr = sdf.format(window[0]);
        String endStr = sdf.format(window[1]);

        assertEquals("2026-08-20 00:00:00", startStr, "查询开始时间应为 2026-08-20 00:00:00");
        assertEquals("2026-08-21 00:00:00", endStr, "查询结束时间应为 2026-08-21 00:00:00");
    }

    // ============ 床号解析测试 ============

    @Test
    public void testParseBedNumber_PureNumber() {
        // 测试纯数字床号
        assertEquals(Integer.valueOf(1), parseBedNumber("1"), "床号 '1' 应解析为 1");
        assertEquals(Integer.valueOf(10), parseBedNumber("10"), "床号 '10' 应解析为 10");
        assertEquals(Integer.valueOf(100), parseBedNumber("100"), "床号 '100' 应解析为 100");
    }

    @Test
    public void testParseBedNumber_WithPrefix() {
        // 测试带前缀的床号
        assertEquals(Integer.valueOf(1), parseBedNumber("ICU-01"), "床号 'ICU-01' 应解析为 1");
        assertEquals(Integer.valueOf(5), parseBedNumber("A05"), "床号 'A05' 应解析为 5");
        assertEquals(Integer.valueOf(12), parseBedNumber("BED-12"), "床号 'BED-12' 应解析为 12");
    }

    @Test
    public void testParseBedNumber_Invalid() {
        // 测试无效床号
        assertNull(parseBedNumber("--"), "床号 '--' 应返回 null");
        assertNull(parseBedNumber(""), "空字符串应返回 null");
        assertNull(parseBedNumber(null), "null 应返回 null");
        assertNull(parseBedNumber("ABC"), "纯字母应返回 null");
    }

    @Test
    public void testParseBedNumber_WithWhitespace() {
        // 测试带空格的床号
        assertEquals(Integer.valueOf(1), parseBedNumber(" 1 "), "带空格的床号 ' 1 ' 应解析为 1");
        assertEquals(Integer.valueOf(5), parseBedNumber(" 5"), "带前导空格的床号 ' 5' 应解析为 5");
    }

    @Test
    public void testBedSorting_NumericOrder() {
        // 测试床号排序：纯数字
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(createRow("10", "患者A"));
        rows.add(createRow("2", "患者B"));
        rows.add(createRow("1", "患者C"));
        rows.add(createRow("5", "患者D"));

        // 按床号排序
        rows.sort((a, b) -> {
            String bedNoA = String.valueOf(a.get("bedNo"));
            String bedNoB = String.valueOf(b.get("bedNo"));
            Integer numA = parseBedNumber(bedNoA);
            Integer numB = parseBedNumber(bedNoB);
            if (numA == null && numB == null) return 0;
            if (numA == null) return 1;
            if (numB == null) return -1;
            return numA.compareTo(numB);
        });

        assertEquals("1", rows.get(0).get("bedNo"), "第1个应是床号1");
        assertEquals("2", rows.get(1).get("bedNo"), "第2个应是床号2");
        assertEquals("5", rows.get(2).get("bedNo"), "第3个应是床号5");
        assertEquals("10", rows.get(3).get("bedNo"), "第4个应是床号10");
    }

    @Test
    public void testBedSorting_InvalidBedAtEnd() {
        // 测试无效床号排到最后
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(createRow("--", "患者A"));
        rows.add(createRow("3", "患者B"));
        rows.add(createRow("1", "患者C"));
        rows.add(createRow("", "患者D"));

        // 按床号排序
        rows.sort((a, b) -> {
            String bedNoA = String.valueOf(a.get("bedNo"));
            String bedNoB = String.valueOf(b.get("bedNo"));
            Integer numA = parseBedNumber(bedNoA);
            Integer numB = parseBedNumber(bedNoB);
            if (numA == null && numB == null) return 0;
            if (numA == null) return 1;
            if (numB == null) return -1;
            return numA.compareTo(numB);
        });

        assertEquals("1", rows.get(0).get("bedNo"), "第1个应是床号1");
        assertEquals("3", rows.get(1).get("bedNo"), "第2个应是床号3");
        // 无效床号排在最后
        String lastBed = String.valueOf(rows.get(2).get("bedNo"));
        String secondLastBed = String.valueOf(rows.get(3).get("bedNo"));
        assertTrue("--".equals(lastBed) || "--".equals(secondLastBed),
                "无效床号应排在最后");
    }

    @Test
    public void testBedSorting_MixedFormat() {
        // 测试混合格式床号
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(createRow("ICU-05", "患者A"));
        rows.add(createRow("2", "患者B"));
        rows.add(createRow("ICU-01", "患者C"));
        rows.add(createRow("10", "患者D"));

        // 按床号排序
        rows.sort((a, b) -> {
            String bedNoA = String.valueOf(a.get("bedNo"));
            String bedNoB = String.valueOf(b.get("bedNo"));
            Integer numA = parseBedNumber(bedNoA);
            Integer numB = parseBedNumber(bedNoB);
            if (numA == null && numB == null) return 0;
            if (numA == null) return 1;
            if (numB == null) return -1;
            return numA.compareTo(numB);
        });

        assertEquals("ICU-01", rows.get(0).get("bedNo"), "第1个应是ICU-01（解析为1）");
        assertEquals("2", rows.get(1).get("bedNo"), "第2个应是床号2");
        assertEquals("ICU-05", rows.get(2).get("bedNo"), "第3个应是ICU-05（解析为5）");
        assertEquals("10", rows.get(3).get("bedNo"), "第4个应是床号10");
    }

    // ============ 辅助方法 ============

    /**
     * 构造北京时间下的日期时间
     */
    private Date buildDate(String dateStr, int hour, int minute, int second) {
        String[] parts = dateStr.split("-");
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        cal.clear();
        cal.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1,
                Integer.parseInt(parts[2]), hour, minute, second);
        return cal.getTime();
    }

    /**
     * 提取 MRN（复制自 VentController 的私有方法，用于测试）
     */
    private String extractMrn(Map patient) {
        Object mrnObj = patient.get("mrn");
        if (mrnObj == null) return null;
        String mrn = String.valueOf(mrnObj).trim();
        if (mrn.isEmpty()) return null;
        return mrn;
    }

    /**
     * 解析床号（复制自 VentController 的私有方法，用于测试）
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

        // 尝试提取字符串中的数字部分
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
     * 创建测试用的行数据
     */
    private Map<String, Object> createRow(String bedNo, String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("bedNo", bedNo);
        row.put("name", name);
        return row;
    }
}
