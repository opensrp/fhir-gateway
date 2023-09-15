package com.google.fhir.gateway.plugin;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.config.SocketConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

public enum HttpHelper {
  INSTANCE;

  public static HttpHelper getInstance() {
    return INSTANCE;
  }

  HttpHelper() {

    PoolingHttpClientConnectionManager poolingHttpClientConnectionManager =
        new PoolingHttpClientConnectionManager();

    if (StringUtils.isNotBlank(System.getenv(MAX_CONNECTION_TOTAL))) {
      poolingHttpClientConnectionManager.setMaxTotal(
          Integer.valueOf(System.getenv(MAX_CONNECTION_TOTAL)));
    }

    if (StringUtils.isNotBlank(System.getenv(MAX_CONNECTION_PER_ROUTE))) {
      poolingHttpClientConnectionManager.setDefaultMaxPerRoute(
          Integer.valueOf(System.getenv(MAX_CONNECTION_PER_ROUTE)));
    }

    if (StringUtils.isNotBlank(System.getenv(SOCKET_TIMEOUT))) {
      SocketConfig socketConfig =
          SocketConfig.custom()
              .setSoTimeout(Integer.valueOf(System.getenv(SOCKET_TIMEOUT)) * 1000)
              .build();
      poolingHttpClientConnectionManager.setDefaultSocketConfig(socketConfig);
    }

    httpClient =
        HttpClients.custom().setConnectionManager(poolingHttpClientConnectionManager).build();

    genericClient = createFhirClientForR4(FhirContext.forR4());
  }

  private final CloseableHttpClient httpClient;

  public CloseableHttpClient getHttpClient() {
    return httpClient;
  }

  private final IGenericClient genericClient;

  public IGenericClient getGenericClient() {
    return genericClient;
  }

  private IGenericClient createFhirClientForR4(FhirContext fhirContext) {
    long start = BenchmarkingHelper.startBenchmarking();
    String fhirServer = System.getenv(PROXY_TO_ENV);
    IGenericClient client = fhirContext.newRestfulGenericClient(fhirServer);
    System.out.println(
        String.format(
            "########## createFhirClientForR4 created in %.1f seconds",
            ((System.nanoTime() - start) / 1000)));
    return client;
  }

  public static final String SOCKET_TIMEOUT = "GATEWAY_SOCKET_TIMEOUT";
  public static final String CONNECTION_REQUEST_TIMEOUT = "GATEWAY_CONNECTION_REQUEST_TIMEOUT";
  public static final String CONNECT_TIMEOUT = "GATEWAY_CONNECT_TIMEOUT";
  public static final String MAX_CONNECTION_TOTAL = "GATEWAY_MAX_CONNECTION_TOTAL";
  public static final String MAX_CONNECTION_PER_ROUTE = "GATEWAY_MAX_CONNECTION_PER_ROUTE";

  public static final String PROXY_TO_ENV = "PROXY_TO";
}
