package com.cg.jy.dto;

import java.util.List;

/**
 * 风险识别请求DTO
 */
public class IdentificationRequest {

    /**
     * 风险类型列表
     * 支持：TS2-PGD-DDFX、TS2-PGD-Braden、DGHTLX
     */
    private List<String> riskTypeList;

    /**
     * 住院号列表（实际按 patient.mrn 查询）
     */
    private List<String> pidList;

    public IdentificationRequest() {
    }

    public List<String> getRiskTypeList() {
        return riskTypeList;
    }

    public void setRiskTypeList(List<String> riskTypeList) {
        this.riskTypeList = riskTypeList;
    }

    public List<String> getPidList() {
        return pidList;
    }

    public void setPidList(List<String> pidList) {
        this.pidList = pidList;
    }
}
