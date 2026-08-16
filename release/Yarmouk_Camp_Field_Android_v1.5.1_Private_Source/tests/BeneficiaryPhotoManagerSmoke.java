package org.unrwa.yarmoukfield;

/** Pure policy checks for folder naming; no Android storage is touched. */
public final class BeneficiaryPhotoManagerSmoke {
    public static void main(String[] args) {
        require("محمد_أمين".equals(BeneficiaryPhotoManager.safeName(" محمد/أمين ")),"unsafe path separator");
        require("بدون اسم".equals(BeneficiaryPhotoManager.safeName("...")),"empty fallback");
        require(BeneficiaryPhotoManager.safeName("أ".repeat(90)).length()<=70,"long name limit");
        require("01 - قبل الترميم".equals(BeneficiaryPhotoManager.phaseFolder(BeneficiaryPhotoManager.BEFORE)),"before folder");
        require("02 - أثناء الترميم".equals(BeneficiaryPhotoManager.phaseFolder(BeneficiaryPhotoManager.DURING)),"during folder");
        require("03 - بعد الترميم".equals(BeneficiaryPhotoManager.phaseFolder(BeneficiaryPhotoManager.AFTER)),"after folder");
        System.out.println("PHOTO_PATH_POLICY_OK");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
