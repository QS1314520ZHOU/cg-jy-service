package com.cg.jy.service;

import com.cg.jy.config.SmartCareDataSource;
import com.cg.jy.dto.IdentificationRequest;
import com.cg.jy.dto.IdentificationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * IdentificationService 单元测试
 *
 * 数据流程：mrn → patient._id → nurse_record.bedside.pid
 */
@ExtendWith(MockitoExtension.class)
class IdentificationServiceTest {

    @Mock
    private MongoTemplate smartCareMongo;

    @Mock
    private SmartCareDataSource smartCareDataSource;

    private IdentificationService service;

    @BeforeEach
    void setUp() {
        when(smartCareDataSource.template()).thenReturn(smartCareMongo);
        service = new IdentificationService(smartCareDataSource);
    }

    // ============ 正常识别场景 ============

    @Test
    void testIdentify_FallRisk() {
        // 1. mock patient 查询
        Map patient = createPatient("patient_001", "0126090105", "张三");
        when(smartCareMongo.find(argThat(q -> true), eq(Map.class), eq("patient")))
                .thenReturn(Arrays.asList(patient));

        // 2. mock nurse_record 查询
        Map record = createNurseRecord("patient_001", "param_score_patientFallDangerFactorV2", "高风险", "2026-09-04 10:00:00");
        when(smartCareMongo.find(argThat(q -> true), eq(Map.class), eq("bedside")))
                .thenReturn(Arrays.asList(record));

        IdentificationRequest request = new IdentificationRequest();
        request.setRiskTypeList(Arrays.asList("TS2-PGD-DDFX"));
        request.setPidList(Arrays.asList("0126090105"));

        List<IdentificationResult> results = service.identify(request);

        assertEquals(1, results.size());
        assertEquals("TS2-PGD-DDFX", results.get(0).getFxbl());
        assertEquals("0126090105", results.get(0).getZyh());
        assertEquals("张三", results.get(0).getName());
        assertEquals("高风险", results.get(0).getFxdj());
    }

    @Test
    void testIdentify_BradenRisk_VeryHigh() {
        Map patient = createPatient("patient_001", "0126090105", "李四");
        when(smartCareMongo.find(any(), eq(Map.class), eq("patient")))
                .thenReturn(Arrays.asList(patient));

        Map record = createNurseRecord("patient_001", "param_yaChuang_score", "极高度危险", "2026-09-04 10:00:00");
        when(smartCareMongo.find(any(), eq(Map.class), eq("bedside")))
                .thenReturn(Arrays.asList(record));

        IdentificationRequest request = new IdentificationRequest();
        request.setRiskTypeList(Arrays.asList("TS2-PGD-Braden"));
        request.setPidList(Arrays.asList("0126090105"));

        List<IdentificationResult> results = service.identify(request);

        assertEquals(1, results.size());
        assertEquals("极高度危险", results.get(0).getFxdj());
    }

    @Test
    void testIdentify_BradenRisk_High() {
        Map patient = createPatient("patient_001", "0126090105", "王五");
        when(smartCareMongo.find(any(), eq(Map.class), eq("patient")))
                .thenReturn(Arrays.asList(patient));

        Map record = createNurseRecord("patient_001", "param_yaChuang_score", "高度危险", "2026-09-04 10:00:00");
        when(smartCareMongo.find(any(), eq(Map.class), eq("bedside")))
                .thenReturn(Arrays.asList(record));

        IdentificationRequest request = new IdentificationRequest();
        request.setRiskTypeList(Arrays.asList("TS2-PGD-Braden"));
        request.setPidList(Arrays.asList("0126090105"));

        List<IdentificationResult> results = service.identify(request);

        assertEquals(1, results.size());
        assertEquals("高度危险", results.get(0).getFxdj());
    }

    @Test
    void testIdentify_BradenRisk_Medium() {
        Map patient = createPatient("patient_001", "0126090105", "赵六");
        when(smartCareMongo.find(any(), eq(Map.class), eq("patient")))
                .thenReturn(Arrays.asList(patient));

        Map record = createNurseRecord("patient_001", "param_yaChuang_score", "中度危险", "2026-09-04 10:00:00");
        when(smartCareMongo.find(any(), eq(Map.class), eq("bedside")))
                .thenReturn(Arrays.asList(record));

        IdentificationRequest request = new IdentificationRequest();
        request.setRiskTypeList(Arrays.asList("TS2-PGD-Braden"));
        request.setPidList(Arrays.asList("0126090105"));

        List<IdentificationResult> results = service.identify(request);

        assertEquals(1, results.size());
        assertEquals("中度危险", results.get(0).getFxdj());
    }

    @Test
    void testIdentify_BradenRisk_Low() {
        Map patient = createPatient("patient_001", "0126090105", "孙七");
        when(smartCareMongo.find(any(), eq(Map.class), eq("patient")))
                .thenReturn(Arrays.asList(patient));

        Map record = createNurseRecord("patient_001", "param_yaChuang_score", "轻度危险", "2026-09-04 10:00:00");
        when(smartCareMongo.find(any(), eq(Map.class), eq("bedside")))
                .thenReturn(Arrays.asList(record));

        IdentificationRequest request = new IdentificationRequest();
        request.setRiskTypeList(Arrays.asList("TS2-PGD-Braden"));
        request.setPidList(Arrays.asList("0126090105"));

        List<IdentificationResult> results = service.identify(request);

        assertEquals(1, results.size());
        assertEquals("轻度危险", results.get(0).getFxdj());
    }

    @Test
    void testIdentify_UnplannedExtubationRisk() {
        Map patient = createPatient("patient_001", "0126090105", "周八");
        when(smartCareMongo.find(any(), eq(Map.class), eq("patient")))
                .thenReturn(Arrays.asList(patient));

        Map record = createNurseRecord("patient_001", "param_score_unPlannedCGZYY", "高险", "2026-09-04 10:00:00");
        when(smartCareMongo.find(any(), eq(Map.class), eq("bedside")))
                .thenReturn(Arrays.asList(record));

        IdentificationRequest request = new IdentificationRequest();
        request.setRiskTypeList(Arrays.asList("DGHTLX"));
        request.setPidList(Arrays.asList("0126090105"));

        List<IdentificationResult> results = service.identify(request);

        assertEquals(1, results.size());
        assertEquals("高风险", results.get(0).getFxdj());
    }

    // ============ 多患者多风险类型 ============

    @Test
    void testIdentify_MultiplePatientsAndRiskTypes() {
        Map patient1 = createPatient("patient_001", "0126090105", "张三");
        Map patient2 = createPatient("patient_002", "0126090150", "李四");
        when(smartCareMongo.find(any(), eq(Map.class), eq("patient")))
                .thenReturn(Arrays.asList(patient1, patient2));

        Map record1 = createNurseRecord("patient_001", "param_score_patientFallDangerFactorV2", "高风险", "2026-09-04 10:00:00");
        Map record2 = createNurseRecord("patient_001", "param_yaChuang_score", "高度危险", "2026-09-04 10:00:00");
        Map record3 = createNurseRecord("patient_002", "param_score_patientFallDangerFactorV2", "低风险", "2026-09-04 10:00:00");
        Map record4 = createNurseRecord("patient_002", "param_yaChuang_score", "轻度危险", "2026-09-04 10:00:00");
        when(smartCareMongo.find(any(), eq(Map.class), eq("bedside")))
                .thenReturn(Arrays.asList(record1, record2, record3, record4));

        IdentificationRequest request = new IdentificationRequest();
        request.setRiskTypeList(Arrays.asList("TS2-PGD-DDFX", "TS2-PGD-Braden"));
        request.setPidList(Arrays.asList("0126090105", "0126090150"));

        List<IdentificationResult> results = service.identify(request);

        // 2 患者 * 2 风险类型 = 4 条结果
        assertEquals(4, results.size());

        Map<String, IdentificationResult> resultMap = new HashMap<>();
        for (IdentificationResult r : results) {
            resultMap.put(r.getZyh() + "|" + r.getFxbl(), r);
        }

        assertEquals("高风险", resultMap.get("0126090105|TS2-PGD-DDFX").getFxdj());
        assertEquals("高度危险", resultMap.get("0126090105|TS2-PGD-Braden").getFxdj());
        assertEquals("低风险", resultMap.get("0126090150|TS2-PGD-DDFX").getFxdj());
        assertEquals("轻度危险", resultMap.get("0126090150|TS2-PGD-Braden").getFxdj());
    }

    // ============ 无记录场景 ============

    @Test
    void testIdentify_NoPatientFound() {
        when(smartCareMongo.find(any(), eq(Map.class), eq("patient")))
                .thenReturn(new ArrayList<>());

        IdentificationRequest request = new IdentificationRequest();
        request.setRiskTypeList(Arrays.asList("TS2-PGD-DDFX"));
        request.setPidList(Arrays.asList("999999"));

        List<IdentificationResult> results = service.identify(request);

        assertEquals(1, results.size());
        assertEquals("", results.get(0).getFxdj());
        assertEquals("", results.get(0).getName());
    }

    @Test
    void testIdentify_NoNurseRecordFound() {
        Map patient = createPatient("patient_001", "0126090105", "张三");
        when(smartCareMongo.find(any(), eq(Map.class), eq("patient")))
                .thenReturn(Arrays.asList(patient));

        when(smartCareMongo.find(any(), eq(Map.class), eq("bedside")))
                .thenReturn(new ArrayList<>());

        IdentificationRequest request = new IdentificationRequest();
        request.setRiskTypeList(Arrays.asList("TS2-PGD-DDFX"));
        request.setPidList(Arrays.asList("0126090105"));

        List<IdentificationResult> results = service.identify(request);

        assertEquals(1, results.size());
        assertEquals("张三", results.get(0).getName());
        assertEquals("", results.get(0).getFxdj());
    }

    // ============ 异常输入 ============

    @Test
    void testIdentify_EmptyRiskTypeList() {
        IdentificationRequest request = new IdentificationRequest();
        request.setRiskTypeList(new ArrayList<>());
        request.setPidList(Arrays.asList("0126090105"));
        assertThrows(IllegalArgumentException.class, () -> service.identify(request));
    }

    @Test
    void testIdentify_EmptyPidList() {
        IdentificationRequest request = new IdentificationRequest();
        request.setRiskTypeList(Arrays.asList("TS2-PGD-DDFX"));
        request.setPidList(new ArrayList<>());
        assertThrows(IllegalArgumentException.class, () -> service.identify(request));
    }

    @Test
    void testIdentify_NullRiskTypeList() {
        IdentificationRequest request = new IdentificationRequest();
        request.setRiskTypeList(null);
        request.setPidList(Arrays.asList("0126090105"));
        assertThrows(IllegalArgumentException.class, () -> service.identify(request));
    }

    @Test
    void testIdentify_NullPidList() {
        IdentificationRequest request = new IdentificationRequest();
        request.setRiskTypeList(Arrays.asList("TS2-PGD-DDFX"));
        request.setPidList(null);
        assertThrows(IllegalArgumentException.class, () -> service.identify(request));
    }

    @Test
    void testIdentify_UnsupportedRiskType() {
        IdentificationRequest request = new IdentificationRequest();
        request.setRiskTypeList(Arrays.asList("UNSUPPORTED_TYPE"));
        request.setPidList(Arrays.asList("0126090105"));
        assertThrows(IllegalArgumentException.class, () -> service.identify(request));
    }

    // ============ 风险等级标准化 ============

    @Test
    void testNormalizeFallRisk() {
        assertEquals("高风险", service.normalizeRiskLevel("TS2-PGD-DDFX", "高风险"));
        assertEquals("高风险", service.normalizeRiskLevel("TS2-PGD-DDFX", "跌倒/坠床风险：高风险"));
        assertEquals("中风险", service.normalizeRiskLevel("TS2-PGD-DDFX", "中风险"));
        assertEquals("低风险", service.normalizeRiskLevel("TS2-PGD-DDFX", "低风险"));
    }

    @Test
    void testNormalizeBradenRisk() {
        // Braden 用 contains 匹配提取危险等级
        assertEquals("极高度危险", service.normalizeRiskLevel("TS2-PGD-Braden", "极高度危险"));
        assertEquals("高度危险", service.normalizeRiskLevel("TS2-PGD-Braden", "高度危险"));
        assertEquals("中度危险", service.normalizeRiskLevel("TS2-PGD-Braden", "中度危险"));
        assertEquals("轻度危险", service.normalizeRiskLevel("TS2-PGD-Braden", "轻度危险"));
        assertEquals("轻度危险", service.normalizeRiskLevel("TS2-PGD-Braden", "16(轻度危险)"));
    }

    @Test
    void testNormalizeUnplannedExtubationRisk() {
        assertEquals("高风险", service.normalizeRiskLevel("DGHTLX", "高险"));
        assertEquals("高风险", service.normalizeRiskLevel("DGHTLX", "高危"));
        assertEquals("中风险", service.normalizeRiskLevel("DGHTLX", "中险"));
        assertEquals("中风险", service.normalizeRiskLevel("DGHTLX", "中危"));
        assertEquals("低风险", service.normalizeRiskLevel("DGHTLX", "低险"));
        assertEquals("低风险", service.normalizeRiskLevel("DGHTLX", "低危"));
        assertEquals("低风险", service.normalizeRiskLevel("DGHTLX", "5(低危)"));
    }

    @Test
    void testNormalizeRiskLevel_EmptyValue() {
        assertEquals("", service.normalizeRiskLevel("TS2-PGD-DDFX", ""));
        assertEquals("", service.normalizeRiskLevel("TS2-PGD-DDFX", null));
    }

    // ============ 辅助方法 ============

    private Map createPatient(String id, String mrn, String name) {
        Map patient = new HashMap<>();
        patient.put("_id", id);
        patient.put("mrn", mrn);
        patient.put("name", name);
        return patient;
    }

    /**
     * 创建护理记录（bedside 结构：{ code, strVal, valid }）
     */
    /**
     * 创建护理记录（顶层字段：code, strVal, valid）
     */
    private Map createNurseRecord(String pid, String code, String strVal, String time) {
        Map record = new HashMap<>();
        record.put("pid", pid);
        record.put("code", code);
        record.put("strVal", strVal);
        record.put("valid", true);
        record.put("time", time);
        return record;
    }
}
