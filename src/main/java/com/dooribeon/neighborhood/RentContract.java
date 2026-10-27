package com.dooribeon.neighborhood;

/** 월세 계약 1건 — rent_contracts.csv.gz 한 줄 */
public record RentContract(
        String gu,
        String dong,
        String buildingType,
        double area,        // ㎡
        Integer floor,      // 빈 값이면 null (단독다가구)
        int deposit,        // 만원
        int rent            // 만원
) {
    public String key() {
        return gu + " " + dong;
    }
}