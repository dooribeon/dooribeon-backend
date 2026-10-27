package com.dooribeon.neighborhood;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

@Slf4j
@Repository
public class RentContractRepository {

    /** 동네 키("관악구 봉천동") → 그 동네 계약 목록 */
    private Map<String, List<RentContract>> byNeighborhood = Map.of();

    @PostConstruct
    void load() throws IOException {
        var resource = new ClassPathResource("data/rent_contracts.csv.gz");
        Map<String, List<RentContract>> result = new HashMap<>();
        int count = 0;

        try (var reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(resource.getInputStream()), StandardCharsets.UTF_8))) {
            reader.readLine();   // 헤더 건너뛰기
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] c = line.split(",", -1);   // -1 : 빈 칸도 버리지 않고 유지
                RentContract contract = new RentContract(
                        c[0], c[1], c[2],
                        Double.parseDouble(c[3]),
                        c[4].isEmpty() ? null : Integer.parseInt(c[4]),
                        Integer.parseInt(c[5]),
                        Integer.parseInt(c[6]));
                result.computeIfAbsent(contract.key(), k -> new ArrayList<>()).add(contract);
                count++;
            }
        }
        byNeighborhood = result;
        log.info("계약 {}건 로딩 완료 (동네 {}개)", count, byNeighborhood.size());
    }

    /** 특정 동네의 계약 목록. 없으면 빈 목록 */
    public List<RentContract> findByNeighborhood(String key) {
        return byNeighborhood.getOrDefault(key, List.of());
    }
}