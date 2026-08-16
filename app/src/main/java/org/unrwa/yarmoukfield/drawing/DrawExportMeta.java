package org.unrwa.yarmoukfield.drawing;

/** Title-block / header text for an exported drawing. Beneficiary fields are supplied by the caller (M8 will
 * fill them from the beneficiary record); until then they may be sample/blank. Pure data, no Android. */
public final class DrawExportMeta {
    public String projectAr = "مشروع المأوى في الأونروا";
    public String projectEn = "UNRWA Shelter Project";
    public String benefName = "";
    public String fileNo = "";
    public String floor = "";
    public String engineer = "";
    public String date = "";
    public String ceiling = ""; // e.g. "h=3.20m", empty when not entered
}
