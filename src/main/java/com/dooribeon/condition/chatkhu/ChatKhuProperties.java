package com.dooribeon.condition.chatkhu;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("chatkhu")
public record ChatKhuProperties(URI baseUrl, String apiKey, String model, Duration connectTimeout, Duration readTimeout) {
    @Override
    public String toString() { return "ChatKhuProperties[apiKey=hidden]"; }
}
