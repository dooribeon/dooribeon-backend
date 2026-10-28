package com.dooribeon.condition;

/** 미확인 값과 적용하지 않는 다른 임대 유형의 값은 null로 보존한다. */
public record Budget(
        RentalType rentalType,
        Long jeonseMaxWon,
        Long depositMaxWon,
        Long monthlyRentMaxWon
) {}
