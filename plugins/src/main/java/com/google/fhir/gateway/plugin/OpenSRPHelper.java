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

import static org.smartregister.utils.Constants.EMPTY_STRING;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.ReferenceClientParam;
import com.google.fhir.gateway.ProxyConstants;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.BaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CareTeam;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Group;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.OrganizationAffiliation;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartregister.model.location.LocationHierarchy;
import org.smartregister.model.location.ParentChildrenMap;
import org.smartregister.model.practitioner.FhirPractitionerDetails;
import org.smartregister.model.practitioner.PractitionerDetails;
import org.smartregister.utils.Constants;
import org.springframework.lang.Nullable;

public class OpenSRPHelper {
  private static final Logger logger = LoggerFactory.getLogger(OpenSRPHelper.class);
  public static final String PRACTITIONER_GROUP_CODE = "405623001";
  public static final String HTTP_SNOMED_INFO_SCT = "http://snomed.info/sct";
  public static final Bundle EMPTY_BUNDLE = new Bundle();
  private IGenericClient r4FHIRClient;

  public OpenSRPHelper(IGenericClient fhirClient) {
    this.r4FHIRClient = fhirClient;
  }

  private IGenericClient getFhirClientForR4() {
    return r4FHIRClient;
  }

  public PractitionerDetails getPractitionerDetailsByKeycloakId(String keycloakUUID) {
    PractitionerDetails practitionerDetails = new PractitionerDetails();

    logger.info("Searching for practitioner with identifier: " + keycloakUUID);
    Practitioner practitioner = getPractitionerByIdentifier(keycloakUUID);

    if (practitioner != null) {

      practitionerDetails = getPractitionerDetailsByPractitioner(practitioner);

    } else {
      logger.error("Practitioner with KC identifier: " + keycloakUUID + " not found");
      practitionerDetails.setId(Constants.PRACTITIONER_NOT_FOUND);
    }

    return practitionerDetails;
  }

  public Bundle getSupervisorPractitionerDetailsByKeycloakId(String keycloakUUID) {

    long start = BenchmarkingHelper.startBenchmarking();

    Bundle bundle = new Bundle();

    logger.info("Searching for practitioner with identifier: " + keycloakUUID);
    Practitioner practitioner = getPractitionerByIdentifier(keycloakUUID);

    if (practitioner != null) {

      bundle = getAttributedPractitionerDetailsByPractitioner(practitioner);

    } else {
      logger.error("Practitioner with KC identifier: " + keycloakUUID + " not found");
    }

    BenchmarkingHelper.printCompletedInDuration(
        start,
        "getSupervisorPractitionerDetailsByKeycloakId : params --> keycloakUUID=" + keycloakUUID,
        logger);

    return bundle;
  }

  private Bundle getAttributedPractitionerDetailsByPractitioner(Practitioner practitioner) {

    long start = BenchmarkingHelper.startBenchmarking();

    Bundle responseBundle = new Bundle();
    List<Practitioner> attributedPractitioners = new ArrayList<>();
    PractitionerDetails practitionerDetails = getPractitionerDetailsByPractitioner(practitioner);

    List<CareTeam> careTeamList = practitionerDetails.getFhirPractitionerDetails().getCareTeams();
    // Get other guys.

    List<String> careTeamManagingOrganizationIds =
        getManagingOrganizationsOfCareTeamIds(careTeamList);

    List<OrganizationAffiliation> organizationAffiliations =
        getOrganizationAffiliationsByOrganizationIds(careTeamManagingOrganizationIds);
    List<String> supervisorCareTeamOrganizationLocationIds =
        getLocationIdsByOrganizationAffiliations(organizationAffiliations);
    List<LocationHierarchy> locationHierarchies =
        getLocationsHierarchyByLocationIds(supervisorCareTeamOrganizationLocationIds);
    List<String> attributedLocationsList = getAttributedLocations(locationHierarchies);
    List<String> attributedOrganizationIds =
        getOrganizationIdsByLocationIds(attributedLocationsList);

    // Get care teams by organization Ids
    List<CareTeam> attributedCareTeams = getCareTeamsByOrganizationIds(attributedOrganizationIds);

    for (CareTeam careTeam : careTeamList) {
      attributedCareTeams.removeIf(it -> it.getId().equals(careTeam.getId()));
    }

    careTeamList.addAll(attributedCareTeams);

    BenchmarkingHelper.printMessage(
        "getAttributedPractitionerDetailsByPractitioner() -> CareTeam List" + attributedCareTeams,
        logger);

    for (CareTeam careTeam : careTeamList) {
      // Add current supervisor practitioners
      attributedPractitioners.addAll(
          careTeam.getParticipant().stream()
              .filter(
                  it ->
                      it.hasMember()
                          && it.getMember()
                              .getReference()
                              .startsWith(Enumerations.ResourceType.PRACTITIONER.toCode()))
              .map(
                  it ->
                      getPractitionerByIdentifier(
                          getReferenceIDPart(it.getMember().getReference())))
              .collect(Collectors.toList()));
    }

    List<Bundle.BundleEntryComponent> bundleEntryComponentList = new ArrayList<>();

    for (Practitioner attributedPractitioner : attributedPractitioners) {
      bundleEntryComponentList.add(
          new Bundle.BundleEntryComponent()
              .setResource(getPractitionerDetailsByPractitioner(attributedPractitioner)));
    }

    responseBundle.setEntry(bundleEntryComponentList);
    responseBundle.setTotal(bundleEntryComponentList.size());

    BenchmarkingHelper.printCompletedInDuration(
        start, "getAttributedPractitionerDetailsByPractitioner: params " + practitioner, logger);

    return responseBundle;
  }

  @NotNull
  public static List<String> getAttributedLocations(List<LocationHierarchy> locationHierarchies) {

    long start = BenchmarkingHelper.startBenchmarking();

    List<ParentChildrenMap> parentChildrenList =
        locationHierarchies.stream()
            .flatMap(
                locationHierarchy ->
                    locationHierarchy
                        .getLocationHierarchyTree()
                        .getLocationsHierarchy()
                        .getParentChildren()
                        .stream())
            .collect(Collectors.toList());
    List<String> attributedLocationsList =
        parentChildrenList.stream()
            .flatMap(parentChildren -> parentChildren.getChildIdentifiers().stream())
            .map(it -> getReferenceIDPart(it.toString()))
            .collect(Collectors.toList());

    BenchmarkingHelper.printCompletedInDuration(
        start, "getAttributedLocations " + locationHierarchies, logger);

    return attributedLocationsList;
  }

  private List<String> getOrganizationIdsByLocationIds(List<String> attributedLocationsList) {
    if (attributedLocationsList == null || attributedLocationsList.isEmpty()) {
      return new ArrayList<>();
    }

    long start = BenchmarkingHelper.startBenchmarking();

    Bundle organizationAffiliationsBundle =
        getFhirClientForR4()
            .search()
            .forResource(OrganizationAffiliation.class)
            .where(OrganizationAffiliation.LOCATION.hasAnyOfIds(attributedLocationsList))
            .returnBundle(Bundle.class)
            .execute();

    List<String> organizationIDs =
        organizationAffiliationsBundle.getEntry().stream()
            .map(
                bundleEntryComponent ->
                    getReferenceIDPart(
                        ((OrganizationAffiliation) bundleEntryComponent.getResource())
                            .getOrganization()
                            .getReference()))
            .distinct()
            .collect(Collectors.toList());

    BenchmarkingHelper.printCompletedInDuration(
        start, "getOrganizationIdsByLocationIds : params " + attributedLocationsList, logger);

    return organizationIDs;
  }

  private String getPractitionerIdentifier(Practitioner practitioner) {
    String practitionerId = EMPTY_STRING;
    if (practitioner.getIdElement() != null && practitioner.getIdElement().getIdPart() != null) {
      practitionerId = practitioner.getIdElement().getIdPart();
    }
    return practitionerId;
  }

  private PractitionerDetails getPractitionerDetailsByPractitioner(Practitioner practitioner) {

    long start = BenchmarkingHelper.startBenchmarking();

    PractitionerDetails practitionerDetails = new PractitionerDetails();
    FhirPractitionerDetails fhirPractitionerDetails = new FhirPractitionerDetails();
    String practitionerId = getPractitionerIdentifier(practitioner);

    logger.error("Searching for care teams for practitioner with id: " + practitioner);
    Bundle careTeams = getCareTeams(practitionerId);
    List<CareTeam> careTeamsList = mapBundleToCareTeams(careTeams);
    fhirPractitionerDetails.setCareTeams(careTeamsList);
    fhirPractitionerDetails.setPractitioners(Arrays.asList(practitioner));

    logger.error("Searching for Organizations tied with CareTeams: ");
    List<String> careTeamManagingOrganizationIds =
        getManagingOrganizationsOfCareTeamIds(careTeamsList);

    Bundle careTeamManagingOrganizations = getOrganizationsById(careTeamManagingOrganizationIds);
    logger.error("Managing Organization are fetched");

    List<Organization> managingOrganizationTeams =
        mapBundleToOrganizations(careTeamManagingOrganizations);

    logger.error("Searching for organizations of practitioner with id: " + practitioner);

    List<PractitionerRole> practitionerRoleList =
        getPractitionerRolesByPractitionerId(practitionerId);
    logger.error("Practitioner Roles are fetched");

    List<String> practitionerOrganizationIds =
        getOrganizationIdsByPractitionerRoles(practitionerRoleList);

    Bundle practitionerOrganizations = getOrganizationsById(practitionerOrganizationIds);

    List<Organization> teams = mapBundleToOrganizations(practitionerOrganizations);
    List<Organization> bothOrganizations =
        Stream.concat(managingOrganizationTeams.stream(), teams.stream())
            .filter(distinctByKey(Organization::getId))
            .collect(Collectors.toList());

    fhirPractitionerDetails.setOrganizations(bothOrganizations);
    fhirPractitionerDetails.setPractitionerRoles(practitionerRoleList);

    Bundle groupsBundle = getGroupsAssignedToPractitioner(practitionerId);
    logger.error("Groups are fetched");

    List<Group> groupsList = mapBundleToGroups(groupsBundle);
    fhirPractitionerDetails.setGroups(groupsList);
    fhirPractitionerDetails.setId(practitionerId);

    logger.error("Searching for locations by organizations");

    Bundle organizationAffiliationsBundle =
        getOrganizationAffiliationsByOrganizationIdsBundle(
            Stream.concat(
                    careTeamManagingOrganizationIds.stream(), practitionerOrganizationIds.stream())
                .distinct()
                .collect(Collectors.toList()));

    List<OrganizationAffiliation> organizationAffiliations =
        mapBundleToOrganizationAffiliation(organizationAffiliationsBundle);

    fhirPractitionerDetails.setOrganizationAffiliations(organizationAffiliations);

    List<String> locationIds = getLocationIdsByOrganizationAffiliations(organizationAffiliations);

    logger.error("Searching for location hierarchy list by locations identifiers");
    List<LocationHierarchy> locationHierarchyList = getLocationsHierarchyByLocationIds(locationIds);
    fhirPractitionerDetails.setLocationHierarchyList(locationHierarchyList);

    logger.error("Searching for locations by ids");
    List<Location> locationsList = getLocationsByIds(locationIds);
    fhirPractitionerDetails.setLocations(locationsList);

    practitionerDetails.setId(practitionerId);
    practitionerDetails.setFhirPractitionerDetails(fhirPractitionerDetails);

    BenchmarkingHelper.printCompletedInDuration(
        start, "getPractitionerDetailsByPractitioner : params " + practitioner, logger);

    return practitionerDetails;
  }

  private List<Organization> mapBundleToOrganizations(Bundle organizationBundle) {
    return organizationBundle.getEntry().stream()
        .map(bundleEntryComponent -> (Organization) bundleEntryComponent.getResource())
        .collect(Collectors.toList());
  }

  private Bundle getGroupsAssignedToPractitioner(String practitionerId) {
    return getFhirClientForR4()
        .search()
        .forResource(Group.class)
        .where(Group.MEMBER.hasId(practitionerId))
        .where(Group.CODE.exactly().systemAndCode(HTTP_SNOMED_INFO_SCT, PRACTITIONER_GROUP_CODE))
        .returnBundle(Bundle.class)
        .execute();
  }

  public static <T> Predicate<T> distinctByKey(Function<? super T, ?> uniqueKeyExtractor) {
    Set<Object> seen = ConcurrentHashMap.newKeySet();
    return t -> seen.add(uniqueKeyExtractor.apply(t));
  }

  private List<PractitionerRole> getPractitionerRolesByPractitionerId(String practitionerId) {
    Bundle practitionerRoles = getPractitionerRoles(practitionerId);
    return mapBundleToPractitionerRolesWithOrganization(practitionerRoles);
  }

  private List<String> getOrganizationIdsByPractitionerRoles(
      List<PractitionerRole> practitionerRoles) {
    return practitionerRoles.stream()
        .filter(practitionerRole -> practitionerRole.hasOrganization())
        .map(it -> getReferenceIDPart(it.getOrganization().getReference()))
        .collect(Collectors.toList());
  }

  private Practitioner getPractitionerByIdentifier(String identifier) {
    Bundle resultBundle =
        getFhirClientForR4()
            .search()
            .forResource(Practitioner.class)
            .where(Practitioner.IDENTIFIER.exactly().identifier(identifier))
            .returnBundle(Bundle.class)
            .execute();

    return resultBundle != null
        ? (Practitioner) resultBundle.getEntryFirstRep().getResource()
        : null;
  }

  private List<CareTeam> getCareTeamsByOrganizationIds(List<String> organizationIds) {
    if (organizationIds.isEmpty()) return new ArrayList<>();

    Bundle bundle =
        getFhirClientForR4()
            .search()
            .forResource(CareTeam.class)
            .where(
                CareTeam.PARTICIPANT.hasAnyOfIds(
                    organizationIds.stream()
                        .map(
                            it ->
                                Enumerations.ResourceType.ORGANIZATION.toCode()
                                    + Constants.FORWARD_SLASH
                                    + it)
                        .collect(Collectors.toList())))
            .returnBundle(Bundle.class)
            .execute();

    return bundle.getEntry().stream()
        .filter(it -> ((CareTeam) it.getResource()).hasManagingOrganization())
        .map(it -> ((CareTeam) it.getResource()))
        .collect(Collectors.toList());
  }

  private Bundle getCareTeams(String practitionerId) {
    logger.info("Searching for Care Teams with practitioner id :" + practitionerId);

    return getFhirClientForR4()
        .search()
        .forResource(CareTeam.class)
        .where(
            CareTeam.PARTICIPANT.hasId(
                Enumerations.ResourceType.PRACTITIONER.toCode()
                    + Constants.FORWARD_SLASH
                    + practitionerId))
        .returnBundle(Bundle.class)
        .execute();
  }

  private Bundle getPractitionerRoles(String practitionerId) {
    logger.info("Searching for Practitioner roles  with practitioner id :" + practitionerId);
    return getFhirClientForR4()
        .search()
        .forResource(PractitionerRole.class)
        .where(PractitionerRole.PRACTITIONER.hasId(practitionerId))
        .returnBundle(Bundle.class)
        .execute();
  }

  private static String getReferenceIDPart(String reference) {
    return reference.substring(reference.indexOf(Constants.FORWARD_SLASH) + 1);
  }

  private Bundle getOrganizationsById(List<String> organizationIds) {
    return organizationIds.isEmpty()
        ? EMPTY_BUNDLE
        : getFhirClientForR4()
            .search()
            .forResource(Organization.class)
            .where(new ReferenceClientParam(BaseResource.SP_RES_ID).hasAnyOfIds(organizationIds))
            .returnBundle(Bundle.class)
            .execute();
  }

  private @Nullable List<Location> getLocationsByIds(List<String> locationIds) {
    if (locationIds == null || locationIds.isEmpty()) {
      return new ArrayList<>();
    }

    Bundle locationsBundle =
        getFhirClientForR4()
            .search()
            .forResource(Location.class)
            .where(new ReferenceClientParam(BaseResource.SP_RES_ID).hasAnyOfIds(locationIds))
            .returnBundle(Bundle.class)
            .execute();

    return locationsBundle.getEntry().stream()
        .map(bundleEntryComponent -> ((Location) bundleEntryComponent.getResource()))
        .collect(Collectors.toList());
  }

  private List<OrganizationAffiliation> getOrganizationAffiliationsByOrganizationIds(
      List<String> organizationIds) {
    if (organizationIds == null || organizationIds.isEmpty()) {
      return new ArrayList<>();
    }
    Bundle organizationAffiliationsBundle =
        getOrganizationAffiliationsByOrganizationIdsBundle(organizationIds);
    return mapBundleToOrganizationAffiliation(organizationAffiliationsBundle);
  }

  private Bundle getOrganizationAffiliationsByOrganizationIdsBundle(List<String> organizationIds) {
    return organizationIds.isEmpty()
        ? EMPTY_BUNDLE
        : getFhirClientForR4()
            .search()
            .forResource(OrganizationAffiliation.class)
            .where(OrganizationAffiliation.PRIMARY_ORGANIZATION.hasAnyOfIds(organizationIds))
            .returnBundle(Bundle.class)
            .execute();
  }

  private List<String> getLocationIdsByOrganizationAffiliations(
      List<OrganizationAffiliation> organizationAffiliations) {
    return organizationAffiliations.stream()
        .map(
            organizationAffiliation ->
                getReferenceIDPart(
                    organizationAffiliation.getLocation().stream()
                        .findFirst()
                        .get()
                        .getReference()))
        .collect(Collectors.toList());
  }

  private List<String> getManagingOrganizationsOfCareTeamIds(List<CareTeam> careTeamsList) {
    logger.info("Searching for Organizations with care teams list of size:" + careTeamsList.size());
    return careTeamsList.stream()
        .filter(careTeam -> careTeam.hasManagingOrganization())
        .flatMap(it -> it.getManagingOrganization().stream())
        .map(it -> getReferenceIDPart(it.getReference()))
        .collect(Collectors.toList());
  }

  private List<CareTeam> mapBundleToCareTeams(Bundle careTeams) {
    return careTeams.getEntry().stream()
        .map(bundleEntryComponent -> (CareTeam) bundleEntryComponent.getResource())
        .collect(Collectors.toList());
  }

  private List<PractitionerRole> mapBundleToPractitionerRolesWithOrganization(
      Bundle practitionerRoles) {
    return practitionerRoles.getEntry().stream()
        .map(it -> (PractitionerRole) it.getResource())
        .collect(Collectors.toList());
  }

  private List<Group> mapBundleToGroups(Bundle groupsBundle) {
    return groupsBundle.getEntry().stream()
        .map(bundleEntryComponent -> (Group) bundleEntryComponent.getResource())
        .collect(Collectors.toList());
  }

  private List<OrganizationAffiliation> mapBundleToOrganizationAffiliation(
      Bundle organizationAffiliationBundle) {
    return organizationAffiliationBundle.getEntry().stream()
        .map(bundleEntryComponent -> (OrganizationAffiliation) bundleEntryComponent.getResource())
        .collect(Collectors.toList());
  }

  private List<LocationHierarchy> getLocationsHierarchyByLocationIds(List<String> locationIds) {
    if (locationIds.isEmpty()) return new ArrayList<>();

    long start = BenchmarkingHelper.startBenchmarking();

    Bundle bundle =
        getFhirClientForR4()
            .search()
            .forResource(LocationHierarchy.class)
            .where(LocationHierarchy.RES_ID.exactly().codes(locationIds))
            .returnBundle(Bundle.class)
            .execute();

    List<LocationHierarchy> locationHierarchies =
        bundle.getEntry().stream()
            .map(it -> ((LocationHierarchy) it.getResource()))
            .collect(Collectors.toList());

    BenchmarkingHelper.printCompletedInDuration(
        start, "getLocationsHierarchyByLocationIds : params " + locationIds, logger);

    return locationHierarchies;
  }

  public static String createSearchTagValues(Map.Entry<String, String[]> entry) {
    return entry.getKey()
        + ProxyConstants.CODE_URL_VALUE_SEPARATOR
        + StringUtils.join(
            entry.getValue(),
            ProxyConstants.PARAM_VALUES_SEPARATOR
                + entry.getKey()
                + ProxyConstants.CODE_URL_VALUE_SEPARATOR);
  }
}
