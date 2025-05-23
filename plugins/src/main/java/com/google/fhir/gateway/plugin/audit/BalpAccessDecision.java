package com.google.fhir.gateway.plugin.audit;

import com.auth0.jwt.JWT;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.fhir.gateway.JwtUtil;
import com.google.fhir.gateway.interfaces.AccessDecision;
import com.google.fhir.gateway.interfaces.RequestDetailsReader;
import com.google.fhir.gateway.interfaces.RequestMutation;
import java.io.IOException;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Reference;
import org.jetbrains.annotations.Nullable;

public class BalpAccessDecision implements AccessDecision {
  private static final String BEARER_PREFIX = "Bearer ";
  private static final String CLAIM_NAME = "name";
  private static final String CLAIM_PREFERRED_NAME = "preferred_username";
  private static final String CLAIM_SUBJECT = "sub";
  private AccessDecision accessDecision;

  @Override
  public boolean canAccess() {
    return accessDecision.canAccess();
  }

  @Override
  public @Nullable RequestMutation getRequestMutation(RequestDetailsReader requestDetailsReader) {
    return accessDecision.getRequestMutation(requestDetailsReader);
  }

  @Override
  public String postProcess(RequestDetailsReader request, HttpResponse response)
      throws IOException {
    return accessDecision.postProcess(request, response);
  }

  public BalpAccessDecision withAccess(AccessDecision accessDecision) {
    this.accessDecision = accessDecision;
    return this;
  }

  @Override
  public @Nullable Reference getUserWho(RequestDetailsReader request) {
    String username = getClaimIfExists(request, CLAIM_PREFERRED_NAME);
    String name = getClaimIfExists(request, CLAIM_NAME);
    String subject = getClaimIfExists(request, CLAIM_SUBJECT);

    return new Reference()
        // .setReference("Practitioner/" + subject)
        .setType("Practitioner")
        .setDisplay(name)
        .setIdentifier(
            new Identifier()
                .setSystem("http://fhir-info-gateway/practitioners")
                .setValue(username)); // Here we can choose to capture the username or the IAM uuid
  }

  private String getClaimIfExists(RequestDetailsReader requestDetails, String claimName) {
    String claim;
    try {
      String authHeader = requestDetails.getHeader(HttpHeaders.AUTHORIZATION);
      String bearerToken = authHeader.substring(BEARER_PREFIX.length());
      DecodedJWT jwt;

      jwt = JWT.decode(bearerToken);
      claim = JwtUtil.getClaimOrDie(jwt, claimName);
    } catch (JWTDecodeException e) {
      claim = "";
    }
    return claim;
  }
}
