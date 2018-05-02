package uk.gov.hmcts.reform.divorce.documentgenerator.config;

import java.util.concurrent.TimeUnit;

import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpClientConfiguration {

    @Value("${http.connect.timeout}")
    private int httpConnectTimeout;

    @Value("${http.connect.request.timeout}")
    private int httpConnectRequestTimeout;

    @Value("${http.connect.pool.max_total}")
    private int httpConnectPoolMaxTotal;

    @Value("${http.connect.pool.close_idle_seconds}")
    private int httpConnectPoolCloseIdleSeconds;

    @Value("${http.connect.pool.max_per_route}")
    private int httpConnectPoolMaxPerRoute;

    @Value("${http.connect.pool.validate_after_inactivity}")
    private int httpConnectionPoolValidateAfterInactivity;

    @Bean
    public HttpClient httpClient() {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
  
        connectionManager.setMaxTotal(httpConnectPoolMaxTotal);
        connectionManager.closeIdleConnections(httpConnectPoolCloseIdleSeconds, TimeUnit.SECONDS);
        connectionManager.setDefaultMaxPerRoute(httpConnectPoolMaxPerRoute);
        connectionManager.setValidateAfterInactivity(httpConnectionPoolValidateAfterInactivity);

        return HttpClientBuilder
            .create()
            .setConnectionManager(connectionManager)
            .build();
    }

}