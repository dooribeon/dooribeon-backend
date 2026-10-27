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
import java.util.List;

@Slf4j
@Repository
public class NeighborhoodRepository {

    private List<Neighborhood> neighborhoods = List.of();

    @PostConstruct
    void load() throws IOException {
        var resource = new ClassPathResource("data/neighborhoods.csv");
        List<Neighborhood> result = new ArrayList<>();

        try (var reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            reader.readLine();   // 첫 줄(헤더) 건너뛰기
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] c = line.split(",");
                result.add(new Neighborhood(
                        c[0], c[1],
                        Integer.parseInt(c[2]),
                        Double.parseDouble(c[3]),
                        Double.parseDouble(c[4])));
            }
        }
        neighborhoods = List.copyOf(result);
        log.info("동네 {}개 로딩 완료", neighborhoods.size());
    }

    public List<Neighborhood> findAll() {
        return neighborhoods;
    }
}