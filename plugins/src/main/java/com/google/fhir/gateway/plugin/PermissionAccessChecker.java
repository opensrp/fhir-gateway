/*
 * Copyright 2021-2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.fhir.gateway.plugin;

import static com.google.fhir.gateway.ProxyConstants.SYNC_STRATEGY;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.fhir.gateway.*;
import com.google.fhir.gateway.interfaces.*;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.stream.Collectors;
import javax.inject.Named;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.hl7.fhir.r4.model.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartregister.model.practitioner.PractitionerDetails;
import org.smartregister.utils.Constants;

public class PermissionAccessChecker implements AccessChecker {
  private static final Logger logger = LoggerFactory.getLogger(PermissionAccessChecker.class);
  private final ResourceFinder resourceFinder;
  private final List<String> userRoles;
  private OpenSRPSyncAccessDecision openSRPSyncAccessDecision;

  private PermissionAccessChecker(
      FhirContext fhirContext,
      String keycloakUUID,
      List<String> userRoles,
      ResourceFinderImp resourceFinder,
      String applicationId,
      String syncStrategy,
      Map<String, List<String>> syncStrategyIds) {
    Preconditions.checkNotNull(userRoles);
    Preconditions.checkNotNull(resourceFinder);
    Preconditions.checkNotNull(applicationId);
    Preconditions.checkNotNull(syncStrategyIds);
    Preconditions.checkNotNull(syncStrategy);
    this.resourceFinder = resourceFinder;
    this.userRoles = userRoles;
    this.openSRPSyncAccessDecision =
        new OpenSRPSyncAccessDecision(
            fhirContext,
            keycloakUUID,
            applicationId,
            true,
            syncStrategyIds,
            syncStrategy,
            userRoles);
  }

  @Override
  public AccessDecision checkAccess(RequestDetailsReader requestDetails) {
    // For a Bundle requestDetails.getResourceName() returns null
    if (requestDetails.getRequestType() == RequestTypeEnum.POST
        && requestDetails.getResourceName() == null) {
      return processBundle(requestDetails);

    } else {

      boolean userHasRole =
          checkUserHasRole(
              requestDetails.getResourceName(), requestDetails.getRequestType().name());

      RequestTypeEnum requestType = requestDetails.getRequestType();

      switch (requestType) {
        case GET:
          return processGet(userHasRole);
        case DELETE:
          return processDelete(userHasRole);
        case POST:
          return processPost(userHasRole);
        case PUT:
          return processPut(userHasRole);
        default:
          // TODO handle other cases like PATCH
          return NoOpAccessDecision.accessDenied();
      }
    }
  }

  private boolean checkUserHasRole(String resourceName, String requestType) {
    return checkIfRoleExists(getAdminRoleName(resourceName), this.userRoles)
        || checkIfRoleExists(getRelevantRoleName(resourceName, requestType), this.userRoles);
  }

  private AccessDecision processGet(boolean userHasRole) {
    return getAccessDecision(userHasRole);
  }

  private AccessDecision processDelete(boolean userHasRole) {
    return getAccessDecision(userHasRole);
  }

  private AccessDecision getAccessDecision(boolean userHasRole) {
    return userHasRole ? openSRPSyncAccessDecision : NoOpAccessDecision.accessDenied();
  }

  private AccessDecision processPost(boolean userHasRole) {
    return getAccessDecision(userHasRole);
  }

  private AccessDecision processPut(boolean userHasRole) {
    return getAccessDecision(userHasRole);
  }

  private AccessDecision processBundle(RequestDetailsReader requestDetails) {
    boolean hasMissingRole = false;
    List<BundleResources> resourcesInBundle = resourceFinder.findResourcesInBundle(requestDetails);
    // Verify Authorization for individual requests in Bundle
    for (BundleResources bundleResources : resourcesInBundle) {
      if (!checkUserHasRole(
          bundleResources.getResource().fhirType(), bundleResources.getRequestType().name())) {

        if (isDevMode()) {
          hasMissingRole = true;
          logger.info(
              "Missing role "
                  + getRelevantRoleName(
                      bundleResources.getResource().fhirType(),
                      bundleResources.getRequestType().name()));
        } else {
          return NoOpAccessDecision.accessDenied();
        }
      }
    }

    return (isDevMode() && !hasMissingRole) || !isDevMode()
        ? NoOpAccessDecision.accessGranted()
        : NoOpAccessDecision.accessDenied();
  }

  private String getRelevantRoleName(String resourceName, String methodType) {
    return methodType + "_" + resourceName.toUpperCase();
  }

  private String getAdminRoleName(String resourceName) {
    return "MANAGE_" + resourceName.toUpperCase();
  }

  @VisibleForTesting
  protected boolean isDevMode() {
    return FhirProxyServer.isDevMode();
  }

  private boolean checkIfRoleExists(String roleName, List<String> existingRoles) {
    return existingRoles.contains(roleName);
  }

  @Named(value = "permission")
  static class Factory implements AccessCheckerFactory {

    @VisibleForTesting static final String REALM_ACCESS_CLAIM = "realm_access";
    @VisibleForTesting static final String ROLES = "roles";

    @VisibleForTesting static final String FHIR_CORE_APPLICATION_ID_CLAIM = "fhir_core_app_id";

    @VisibleForTesting static final String PROXY_TO_ENV = "PROXY_TO";

    private List<String> getUserRolesFromJWT(DecodedJWT jwt) {
      Claim claim = jwt.getClaim(REALM_ACCESS_CLAIM);
      Map<String, Object> roles = claim.asMap();
      List<String> rolesList = (List) roles.get(ROLES);
      return rolesList;
    }

    private String getApplicationIdFromJWT(DecodedJWT jwt) {
      return JwtUtil.getClaimOrDie(jwt, FHIR_CORE_APPLICATION_ID_CLAIM);
    }

    private IGenericClient createFhirClientForR4(FhirContext fhirContext) {
      long start = BenchmarkingHelper.startBenchmarking();
      String fhirServer = System.getenv(PROXY_TO_ENV);
      IGenericClient client = fhirContext.newRestfulGenericClient(fhirServer);
      BenchmarkingHelper.printCompletedInDuration(start, "createFhirClientForR4", logger);
      return client;
    }

    private String getBinaryResourceReference(Composition composition) {

      long start = BenchmarkingHelper.startBenchmarking();

      String id = "";
      if (composition != null && composition.getSection() != null) {
        composition.getSection().stream()
            .filter(
                sectionComponent ->
                    sectionComponent.getFocus().getIdentifier() != null
                        && sectionComponent.getFocus().getIdentifier().getValue() != null
                        && ProxyConstants.APPLICATION.equals(
                            sectionComponent.getFocus().getIdentifier().getValue()))
            .map(sectionComponent -> composition.getSection().indexOf(sectionComponent))
            .collect(Collectors.toList());
        Composition.SectionComponent sectionComponent = composition.getSection().get(0);
        Reference focus = sectionComponent != null ? sectionComponent.getFocus() : null;
        id = focus != null ? focus.getReference() : null;
      }

      BenchmarkingHelper.printCompletedInDuration(
          start,
          "getBinaryResourceReference: params [Composition] -> Composition" + " with id=" + id,
          logger);

      return id;
    }

    private Binary findApplicationConfigBinaryResource(
        String binaryResourceId, FhirContext fhirContext) {

      long start = BenchmarkingHelper.startBenchmarking();

      IGenericClient client = createFhirClientForR4(fhirContext);
      Binary binary = null;
      if (!binaryResourceId.isBlank()) {
        binary = client.read().resource(Binary.class).withId(binaryResourceId).execute();
      }

      BenchmarkingHelper.printCompletedInDuration(
          start,
          "findApplicationConfigBinaryResource : param binary resource with"
              + " id="
              + binaryResourceId,
          logger);

      return binary;
    }

    private String findSyncStrategy(Binary binary) {

      long start = BenchmarkingHelper.startBenchmarking();

      byte[] bytes =
          binary != null && binary.getDataElement() != null
              ? Base64.getDecoder().decode(binary.getDataElement().getValueAsString())
              : null;
      String syncStrategy = Constants.EMPTY_STRING;
      if (bytes != null) {
        String json = new String(bytes);
        JsonObject jsonObject = new Gson().fromJson(json, JsonObject.class);
        JsonArray jsonArray = jsonObject.getAsJsonArray(SYNC_STRATEGY);
        if (jsonArray != null && !jsonArray.isEmpty())
          syncStrategy = jsonArray.get(0).getAsString();
      }

      BenchmarkingHelper.printCompletedInDuration(start, "findSyncStrategy", logger);

      return syncStrategy;
    }

    Pair<Composition, PractitionerDetails> fetchCompositionAndPractitionerDetails(
        String subject, String applicationId, FhirContext fhirContext) {

      long start = BenchmarkingHelper.startBenchmarking();

      fhirContext.registerCustomType(PractitionerDetails.class);

      IGenericClient client = createFhirClientForR4(fhirContext);

      Bundle requestBundle = new Bundle();
      requestBundle.setType(Bundle.BundleType.BATCH);

      requestBundle.addEntry(
          OpenSRPSyncAccessDecision.createBundleEntryComponent(
              Bundle.HTTPVerb.GET, "Composition?identifier=" + applicationId, null));
      requestBundle.addEntry(
          OpenSRPSyncAccessDecision.createBundleEntryComponent(
              Bundle.HTTPVerb.GET, "practitioner-details?keycloak-uuid=" + subject, null));

      Bundle responsebundle = client.transaction().withBundle(requestBundle).execute();

      Pair<Composition, PractitionerDetails> result =
          getCompositionPractitionerDetailsPair(applicationId, responsebundle);
      BenchmarkingHelper.printCompletedInDuration(
          start, "fetchCompositionAndPractitionerDetails", logger);

      return result;
    }

    @NotNull
    private static Pair<Composition, PractitionerDetails> getCompositionPractitionerDetailsPair(
        String applicationId, Bundle responsebundle) {
      long start = BenchmarkingHelper.startBenchmarking();
      Composition composition = null;
      PractitionerDetails practitionerDetails = null;

      Bundle innerBundle;
      for (int i = 0; i < responsebundle.getEntry().size(); i++) {

        innerBundle = (Bundle) responsebundle.getEntry().get(i).getResource();
        if (innerBundle == null) continue;

        for (int j = 0; j < innerBundle.getEntry().size(); j++) {

          if (innerBundle.getEntry().get(j).getResource() instanceof Composition) {
            composition = (Composition) innerBundle.getEntry().get(j).getResource();
          } else if (innerBundle.getEntry().get(j).getResource() instanceof PractitionerDetails) {
            practitionerDetails = (PractitionerDetails) innerBundle.getEntry().get(j).getResource();
          }
        }
      }

      if (composition == null)
        throw new IllegalStateException(
            "No Composition resource found for application id '" + applicationId + "'");

      BenchmarkingHelper.printCompletedInDuration(
          start, "getCompositionPractitionerDetailsPair", logger);
      return Pair.of(composition, practitionerDetails);
    }

    Pair<String, PractitionerDetails> fetchSyncStrategyDetails(
        String subject, String applicationId, FhirContext fhirContext) {

      long start = BenchmarkingHelper.startBenchmarking();

      Pair<Composition, PractitionerDetails> compositionPractitionerDetailsPair =
          fetchCompositionAndPractitionerDetails(subject, applicationId, fhirContext);
      Composition composition = compositionPractitionerDetailsPair.getLeft();
      PractitionerDetails practitionerDetails = compositionPractitionerDetailsPair.getRight();

      String binaryResourceReference = getBinaryResourceReference(composition);
      Binary binary = findApplicationConfigBinaryResource(binaryResourceReference, fhirContext);

      Pair<String, PractitionerDetails> strategyToPractitioner =
          Pair.of(findSyncStrategy(binary), practitionerDetails); // TODO Return immediately

      BenchmarkingHelper.printCompletedInDuration(start, "fetchSyncStrategyDetails", logger);
      return strategyToPractitioner;
    }

    @Override
    public AccessChecker create(
        DecodedJWT jwt,
        HttpFhirClient httpFhirClient,
        FhirContext fhirContext,
        PatientFinder patientFinder)
        throws AuthenticationException {

      long start = BenchmarkingHelper.startBenchmarking();

      List<String> userRoles = getUserRolesFromJWT(jwt);
      String applicationId = getApplicationIdFromJWT(jwt);

      Map<String, List<String>> syncStrategyIds =
          CacheHelper.INSTANCE.cache.get(
              jwt.getSubject(),
              k -> {
                Pair<String, PractitionerDetails> syncStrategyDetails =
                    fetchSyncStrategyDetails(jwt.getSubject(), applicationId, fhirContext);

                String syncStrategy = syncStrategyDetails.getLeft();
                PractitionerDetails practitionerDetails = syncStrategyDetails.getRight();

                return getSyncStrategyIds(syncStrategy, practitionerDetails);
              });

      BenchmarkingHelper.printCompletedInDuration(start, "create", logger);

      return new PermissionAccessChecker(
          fhirContext,
          jwt.getSubject(),
          userRoles,
          ResourceFinderImp.getInstance(fhirContext),
          applicationId,
          syncStrategyIds.keySet().iterator().next(),
          syncStrategyIds);
    }

    @NotNull
    private static Map<String, List<String>> getSyncStrategyIds(
        String syncStrategy, PractitionerDetails practitionerDetails) {
      Map<String, List<String>> resultMap = new HashMap<>();
      List<CareTeam> careTeams;
      List<Organization> organizations;
      List<String> careTeamIds = new ArrayList<>();
      List<String> organizationIds = new ArrayList<>();
      List<String> locationIds = new ArrayList<>();
      if (StringUtils.isNotBlank(syncStrategy)) {
        if (ProxyConstants.CARE_TEAM.equalsIgnoreCase(syncStrategy)) {
          careTeams =
              practitionerDetails != null
                      && practitionerDetails.getFhirPractitionerDetails() != null
                  ? practitionerDetails.getFhirPractitionerDetails().getCareTeams()
                  : Collections.singletonList(new CareTeam());

          careTeamIds =
              careTeams.stream()
                  .filter(careTeam -> careTeam.getIdElement() != null)
                  .map(careTeam -> careTeam.getIdElement().getIdPart())
                  .collect(Collectors.toList());

          resultMap = Map.of(syncStrategy, careTeamIds);

        } else if (ProxyConstants.ORGANIZATION.equalsIgnoreCase(syncStrategy)) {
          organizations =
              practitionerDetails != null
                      && practitionerDetails.getFhirPractitionerDetails() != null
                  ? practitionerDetails.getFhirPractitionerDetails().getOrganizations()
                  : Collections.singletonList(new Organization());

          organizationIds =
              organizations.stream()
                  .filter(organization -> organization.getIdElement() != null)
                  .map(organization -> organization.getIdElement().getIdPart())
                  .collect(Collectors.toList());

          resultMap = Map.of(syncStrategy, organizationIds);

        } else if (ProxyConstants.LOCATION.equalsIgnoreCase(syncStrategy)) {
          locationIds =
              practitionerDetails != null
                      && practitionerDetails.getFhirPractitionerDetails() != null
                  ? OpenSRPHelper.getAttributedLocations(
                      practitionerDetails.getFhirPractitionerDetails().getLocationHierarchyList())
                  : locationIds;

          resultMap = Map.of(syncStrategy, locationIds);
        }
      } else
        throw new IllegalStateException(
            "Sync strategy not configured. Please confirm Keycloak fhir_core_app_id attribute for"
                + " the user matches the Composition.json config official identifier value");

      return resultMap;
    }

    private static class Result {
      public final List<String> careTeamIds;
      public final List<String> organizationIds;
      public final List<String> locationIds;

      public Result(
          List<String> careTeamIds, List<String> organizationIds, List<String> locationIds) {
        this.careTeamIds = careTeamIds;
        this.organizationIds = organizationIds;
        this.locationIds = locationIds;
      }
    }
  }
}
