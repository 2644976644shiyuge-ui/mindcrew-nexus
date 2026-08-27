package com.simon.MindCrew.digitalemployee.export.provider;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

final class PptProviderSupport {

    private static final int CONNECT_TIMEOUT_SECONDS = 15;

    private PptProviderSupport() {
    }

    static RestClient restClient(int timeoutSeconds) {
        int readTimeoutMillis = Math.max(1, timeoutSeconds) * 1000;
        int connectTimeoutMillis = Math.min(readTimeoutMillis, CONNECT_TIMEOUT_SECONDS * 1000);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 连接失败应快速触发内置渲染回退，不能跟完整 PPT 生成共用 10 分钟超时。
        factory.setConnectTimeout(connectTimeoutMillis);
        factory.setReadTimeout(readTimeoutMillis);
        return RestClient.builder().requestFactory(factory).build();
    }

    static boolean isPptx(byte[] body) {
        return body != null && body.length > 512
                && body[0] == 0x50 && body[1] == 0x4b;
    }

    static String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("/+$", "");
    }
}
