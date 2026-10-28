package com.dooribeon.route;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class KakaoRoutingConfiguration {
    @Bean("kakaoRoutingRestClient")
    RestClient client(RestClient.Builder builder,
                      @Value("${kakao.routing.connect-timeout:5s}") Duration connectTimeout,
                      @Value("${kakao.routing.read-timeout:10s}") Duration readTimeout) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        return builder.clone().baseUrl("https://dapi.kakao.com").requestFactory(factory).build();
    }
}
