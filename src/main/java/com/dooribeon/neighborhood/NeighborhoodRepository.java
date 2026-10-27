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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Repository
public class NeighborhoodRepository {

    private List<Neighborhood> neighborhoods = List.of();

    @PostConstruct
    void load() throws IOException {
        var resource = new ClassPathResource("data/neighborhoods.csv");
        List<Neighborhood> raw = new ArrayList<>();

        try (var reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            reader.readLine();   // 헤더 건너뛰기
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] c = line.split(",");
                raw.add(new Neighborhood(
                        c[0], DongNames.normalize(c[1]),
                        Integer.parseInt(c[2]),
                        Double.parseDouble(c[3]),
                        Double.parseDouble(c[4])));
            }
        }

        neighborhoods = mergeSameName(raw);
        log.info("동네 {}개 로딩 완료 (원본 {}개에서 동 이름 정리)", neighborhoods.size(), raw.size());
    }

    /** 이름이 같아진 동네 합치기 : 계약 수는 합, 좌표는 계약 수 가중 평균 */
    private List<Neighborhood> mergeSameName(List<Neighborhood> raw) {
        Map<String, List<Neighborhood>> groups = new LinkedHashMap<>();
        for (Neighborhood n : raw) {
            groups.computeIfAbsent(n.key(), k -> new ArrayList<>()).add(n);
        }

        List<Neighborhood> merged = new ArrayList<>();
        for (List<Neighborhood> group : groups.values()) {
            int total = group.stream().mapToInt(Neighborhood::contractCount).sum();
            double lat = group.stream().mapToDouble(n -> n.lat() * n.contractCount()).sum() / total;
            double lng = group.stream().mapToDouble(n -> n.lng() * n.contractCount()).sum() / total;
            Neighborhood first = group.getFirst();
            merged.add(new Neighborhood(first.gu(), first.dong(), total, lat, lng));
        }
        return List.copyOf(merged);
    }

    public List<Neighborhood> findAll() {
        return neighborhoods;
    }
}