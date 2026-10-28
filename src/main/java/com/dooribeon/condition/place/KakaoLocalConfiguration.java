package com.dooribeon.condition.place;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class KakaoLocalConfiguration {
    @Bean("kakaoLocalRestClient")
    RestClient kakaoLocalRestClient(@Value("${kakao.local.connect-timeout:5s}") Duration connectTimeout,
                                   @Value("${kakao.local.read-timeout:10s}") Duration readTimeout) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl("https://dapi.kakao.com").requestFactory(factory).build();
    }
}
