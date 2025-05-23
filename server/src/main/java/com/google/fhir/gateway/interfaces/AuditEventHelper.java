package com.google.fhir.gateway.interfaces;

public interface AuditEventHelper {
  void processAuditEvents(
      RequestDetailsReader requestDetailsReader, String serverContentResponseReader);
}
