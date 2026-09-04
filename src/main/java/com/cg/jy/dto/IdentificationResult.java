package com.cg.jy.dto;

/**
 * 风险识别结果DTO
 */
public class IdentificationResult {

    /**
     * 风险表单类型，值来自请求的 riskTypeList
     */
    private String fxbl;

    /**
     * 住院号，即 patient.mrn
     */
    private String zyh;

    /**
     * 患者姓名
     */
    private String name;

    /**
     * 归一化后的风险等级
     */
    private String fxdj;

    public IdentificationResult() {
    }

    public IdentificationResult(String fxbl, String zyh, String name, String fxdj) {
        this.fxbl = fxbl;
        this.zyh = zyh;
        this.name = name;
        this.fxdj = fxdj;
    }

    public String getFxbl() {
        return fxbl;
    }

    public void setFxbl(String fxbl) {
        this.fxbl = fxbl;
    }

    public String getZyh() {
        return zyh;
    }

    public void setZyh(String zyh) {
        this.zyh = zyh;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFxdj() {
        return fxdj;
    }

    public void setFxdj(String fxdj) {
        this.fxdj = fxdj;
    }
}
