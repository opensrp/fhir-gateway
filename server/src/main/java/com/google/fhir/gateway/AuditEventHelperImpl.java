package com.google.fhir.gateway;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import ca.uhn.fhir.storage.interceptor.balp.BalpConstants;
import ca.uhn.fhir.storage.interceptor.balp.BalpProfileEnum;
import ca.uhn.fhir.util.FhirTerser;
import ca.uhn.fhir.util.UrlUtil;
import ca.uhn.hapi.converters.canonical.VersionCanonicalizer;
import com.google.fhir.gateway.interfaces.AuditEventHelper;
import com.google.fhir.gateway.interfaces.RequestDetailsReader;
import jakarta.annotation.Nonnull;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.utilities.xhtml.XhtmlNode;

public class AuditEventHelperImpl implements AuditEventHelper {

  public static final String CS_OBJECT_ROLE_24_QUERY_DISPLAY = "Query";
  private final Reference agentUserWho;
  private final IGenericClient iGenericClient;
  private final PatientFinderImp patientFinder;
  private final Date dateTimeStart = new Date();

  private AuditEventHelperImpl(FhirContext fhirContext, String baseUrl, Reference agentUserWho) {
    this.agentUserWho = agentUserWho;
    this.iGenericClient = fhirContext.newRestfulGenericClient(baseUrl);
    this.patientFinder = PatientFinderImp.getInstance(fhirContext);
  }

  private Set<String> myAdditionalPatientCompartmentParamNames;

  public void setAdditionalPatientCompartmentParamNames(
      Set<String> theAdditionalPatientCompartmentParamNames) {
    myAdditionalPatientCompartmentParamNames = theAdditionalPatientCompartmentParamNames;
  }

  @Nonnull
  private Set<String> determinePatientCompartmentOwnersForResources(
      List<IBaseResource> theResources, ServletRequestDetails theRequestDetails) {
    Set<String> patientIds = new TreeSet<>();
    FhirContext fhirContext = theRequestDetails.getFhirContext();

    for (IBaseResource resource : theResources) {
      RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resource);
      if (resourceDef.getName().equals("Patient")) {
        patientIds.add(massageResourceIdForStorage(theRequestDetails, resource));
      } else {
        List<RuntimeSearchParam> compartmentSearchParameters =
            resourceDef.getSearchParamsForCompartmentName("Patient");
        if (!compartmentSearchParameters.isEmpty()) {
          FhirTerser terser = fhirContext.newTerser();
          terser
              .getCompartmentOwnersForResource(
                  "Patient", resource, myAdditionalPatientCompartmentParamNames)
              .stream()
              .map(t -> massageResourceIdForStorage(theRequestDetails, resource))
              .forEach(patientIds::add);
        }
      }
    }
    return patientIds;
  }

  @Nonnull
  private AuditEvent createAuditEventCommonCreate(
      ServletRequestDetails theRequestDetails, IBaseResource theResource, BalpProfileEnum profile) {
    AuditEvent auditEvent = createAuditEventCommon(theRequestDetails, profile);

    String resourceId = massageResourceIdForStorage(theRequestDetails, theResource);
    addEntityData(auditEvent, resourceId, profile);
    return auditEvent;
  }

  @Nonnull
  private AuditEvent createAuditEventBasicCreateUpdateDelete(
      ServletRequestDetails theRequestDetails,
      IBaseResource theResource,
      BalpProfileEnum theProfile) {
    return createAuditEventCommonCreate(theRequestDetails, theResource, theProfile);
  }

  @Nonnull
  private AuditEvent createAuditEventPatientCreateUpdateDelete(
      ServletRequestDetails theRequestDetails,
      IBaseResource theResource,
      Set<String> thePatientCompartmentOwners,
      BalpProfileEnum theProfile) {
    AuditEvent retVal = createAuditEventCommonCreate(theRequestDetails, theResource, theProfile);
    for (String next : thePatientCompartmentOwners) {
      addEntityPatient(retVal, next, theProfile);
    }
    return retVal;
  }

  @Nonnull
  private AuditEvent createAuditEventBasicQuery(ServletRequestDetails theRequestDetails) {
    BalpProfileEnum profile = BalpProfileEnum.BASIC_QUERY;
    return createAuditEventCommonQuery(theRequestDetails, profile);
  }

  @Nonnull
  private AuditEvent createAuditEventBasicRead(
      ServletRequestDetails theRequestDetails, String dataResourceId) {
    return createAuditEventCommonRead(
        theRequestDetails, dataResourceId, BalpProfileEnum.BASIC_READ);
  }

  @Nonnull
  private AuditEvent createAuditEventPatientQuery(
      ServletRequestDetails theRequestDetails, Set<String> compartmentOwners) {
    BalpProfileEnum profile = BalpProfileEnum.PATIENT_QUERY;
    AuditEvent auditEvent = createAuditEventCommonQuery(theRequestDetails, profile);
    for (String next : compartmentOwners) {
      addEntityPatient(auditEvent, next, profile);
    }
    return auditEvent;
  }

  @Nonnull
  private AuditEvent createAuditEventPatientRead(
      ServletRequestDetails theRequestDetails, String dataResourceId, String patientId) {
    BalpProfileEnum profile = BalpProfileEnum.PATIENT_READ;
    AuditEvent auditEvent = createAuditEventCommonRead(theRequestDetails, dataResourceId, profile);
    addEntityPatient(auditEvent, patientId, profile);
    return auditEvent;
  }

  @Nonnull
  private AuditEvent createAuditEventCommon(
      ServletRequestDetails theRequestDetails, BalpProfileEnum theProfile) {
    RestOperationTypeEnum restOperationType = theRequestDetails.getRestOperationType();
    if (restOperationType == RestOperationTypeEnum.GET_PAGE) {
      restOperationType = RestOperationTypeEnum.SEARCH_TYPE;
    }

    AuditEvent auditEvent = new AuditEvent();
    auditEvent.getMeta().addProfile(theProfile.getProfileUrl());
    auditEvent
        .getText()
        .setDiv(new XhtmlNode().setValue("<div>Audit Event</div>"))
        .setStatus(Narrative.NarrativeStatus.GENERATED);
    auditEvent
        .getType()
        .setSystem(BalpConstants.CS_AUDIT_EVENT_TYPE)
        .setCode("rest")
        .setDisplay("Restful Operation");
    auditEvent
        .addSubtype()
        .setSystem(BalpConstants.CS_RESTFUL_INTERACTION)
        .setCode(restOperationType.getCode())
        .setDisplay(restOperationType.getCode());
    auditEvent.setAction(theProfile.getAction());
    auditEvent.setOutcome(AuditEvent.AuditEventOutcome._0);
    auditEvent.setRecorded(new Date());

    auditEvent.getSource().getObserver().setDisplay(theRequestDetails.getFhirServerBase());

    AuditEvent.AuditEventAgentComponent clientAgent = auditEvent.addAgent();
    clientAgent.setWho(getAgentClientWho(theRequestDetails));
    clientAgent.getType().addCoding(theProfile.getAgentClientTypeCoding());
    clientAgent.getWho().setDisplay(getNetworkAddress(theRequestDetails));
    clientAgent
        .getNetwork()
        .setAddress(getNetworkAddress(theRequestDetails))
        .setType(getNetworkAddressType(theRequestDetails));
    clientAgent.setRequestor(false);

    AuditEvent.AuditEventAgentComponent serverAgent = auditEvent.addAgent();
    serverAgent.getType().addCoding(theProfile.getAgentServerTypeCoding());
    serverAgent.getWho().setDisplay(theRequestDetails.getFhirServerBase());
    serverAgent.getNetwork().setAddress(theRequestDetails.getFhirServerBase());
    serverAgent.setRequestor(false);

    AuditEvent.AuditEventAgentComponent userAgent = auditEvent.addAgent();
    userAgent
        .getType()
        .addCoding()
        .setSystem("http://terminology.hl7.org/CodeSystem/v3-ParticipationType")
        .setCode("IRCP")
        .setDisplay("information recipient");
    userAgent.setWho(this.agentUserWho);
    userAgent.setRequestor(true);

    AuditEvent.AuditEventEntityComponent entityTransaction = auditEvent.addEntity();
    entityTransaction
        .getType()
        .setSystem("https://profiles.ihe.net/ITI/BALP/CodeSystem/BasicAuditEntityType")
        .setCode("XrequestId");
    entityTransaction.getWhat().getIdentifier().setValue(theRequestDetails.getRequestId());

    Period period = new Period();
    period.setStart(this.dateTimeStart);
    period.setEnd(new Date());
    auditEvent.setPeriod(period);

    return auditEvent;
  }

  @Nonnull
  private AuditEvent createAuditEventCommonQuery(
      ServletRequestDetails theRequestDetails, BalpProfileEnum profile) {
    AuditEvent auditEvent = createAuditEventCommon(theRequestDetails, profile);

    AuditEvent.AuditEventEntityComponent queryEntity = auditEvent.addEntity();
    queryEntity
        .getType()
        .setSystem(BalpConstants.CS_AUDIT_ENTITY_TYPE)
        .setCode(BalpConstants.CS_AUDIT_ENTITY_TYPE_2_SYSTEM_OBJECT)
        .setDisplay(BalpConstants.CS_AUDIT_ENTITY_TYPE_2_SYSTEM_OBJECT_DISPLAY);
    queryEntity
        .getRole()
        .setSystem(BalpConstants.CS_OBJECT_ROLE)
        .setCode(BalpConstants.CS_OBJECT_ROLE_24_QUERY)
        .setDisplay(CS_OBJECT_ROLE_24_QUERY_DISPLAY);

    // Description
    String description =
        theRequestDetails.getRequestType().name() + " " + theRequestDetails.getCompleteUrl();
    queryEntity.setDescription(description);

    // Query String
    StringBuilder queryString = new StringBuilder();
    queryString.append(theRequestDetails.getFhirServerBase());
    queryString.append("/");
    queryString.append(theRequestDetails.getRequestPath());
    boolean first = true;
    for (Map.Entry<String, String[]> nextEntrySet : theRequestDetails.getParameters().entrySet()) {
      for (String nextValue : nextEntrySet.getValue()) {
        if (first) {
          queryString.append("?");
          first = false;
        } else {
          queryString.append("&");
        }
        queryString.append(UrlUtil.escapeUrlParam(nextEntrySet.getKey()));
        queryString.append("=");
        queryString.append(UrlUtil.escapeUrlParam(nextValue));
      }
    }

    queryEntity.getQueryElement().setValue(queryString.toString().getBytes(StandardCharsets.UTF_8));
    return auditEvent;
  }

  @Nonnull
  private AuditEvent createAuditEventCommonRead(
      ServletRequestDetails theRequestDetails,
      String theDataResourceId,
      BalpProfileEnum theProfile) {
    AuditEvent auditEvent = createAuditEventCommon(theRequestDetails, theProfile);
    addEntityData(auditEvent, theDataResourceId, theProfile);
    return auditEvent;
  }

  private static void addEntityPatient(
      AuditEvent theAuditEvent, String thePatientId, BalpProfileEnum theProfile) {
    AuditEvent.AuditEventEntityComponent entityPatient = theAuditEvent.addEntity();
    entityPatient
        .getType()
        .setSystem(BalpConstants.CS_AUDIT_ENTITY_TYPE)
        .setCode(BalpConstants.CS_AUDIT_ENTITY_TYPE_1_PERSON)
        .setDisplay(BalpConstants.CS_AUDIT_ENTITY_TYPE_1_PERSON_DISPLAY);
    entityPatient
        .getRole()
        .setSystem(BalpConstants.CS_OBJECT_ROLE)
        .setCode(BalpConstants.CS_OBJECT_ROLE_1_PATIENT)
        .setDisplay(BalpConstants.CS_OBJECT_ROLE_1_PATIENT_DISPLAY);
    entityPatient.getWhat().setReference(thePatientId);

    if (theProfile == BalpProfileEnum.BASIC_DELETE
        || theProfile == BalpProfileEnum.PATIENT_DELETE) {
      entityPatient.setWhat(createResourceRef(thePatientId));
    } else {
      entityPatient.getWhat().setReference(thePatientId);
    }
  }

  private static void addEntityData(
      AuditEvent theAuditEvent, String theDataResourceId, BalpProfileEnum theProfile) {
    AuditEvent.AuditEventEntityComponent entityData = theAuditEvent.addEntity();
    entityData
        .getType()
        .setSystem(BalpConstants.CS_AUDIT_ENTITY_TYPE)
        .setCode(BalpConstants.CS_AUDIT_ENTITY_TYPE_2_SYSTEM_OBJECT)
        .setDisplay(BalpConstants.CS_AUDIT_ENTITY_TYPE_2_SYSTEM_OBJECT_DISPLAY);
    entityData
        .getRole()
        .setSystem(BalpConstants.CS_OBJECT_ROLE)
        .setCode(BalpConstants.CS_OBJECT_ROLE_4_DOMAIN_RESOURCE)
        .setDisplay(BalpConstants.CS_OBJECT_ROLE_4_DOMAIN_RESOURCE_DISPLAY);

    if (theProfile == BalpProfileEnum.BASIC_DELETE
        || theProfile == BalpProfileEnum.PATIENT_DELETE) {
      entityData.setWhat(createResourceRef(theDataResourceId));
    } else {
      entityData.getWhat().setReference(theDataResourceId);
    }
  }

  private static Reference createResourceRef(String resourceId) {
    String resourceType = resourceId.substring(0, resourceId.indexOf('/'));
    return new Reference()
        .setType(resourceType)
        .setDisplay("DELETED " + resourceId)
        .setIdentifier(
            new Identifier().setSystem("http://fhir-info-gateway/DELETE").setValue(resourceId));
  }

  public @NotNull String massageResourceIdForStorage(
      @Nonnull RequestDetails theRequestDetails, @Nonnull IBaseResource theResource) {
    return theRequestDetails.getId() != null
        ? theRequestDetails.getId().getValue()
        : ((Resource) theResource).getResourceType() + "/" + ((Resource) theResource).getIdPart();
  }

  /**
   * Provide the requesting network address to include in the AuditEvent.
   *
   * @see #getNetworkAddressType(RequestDetails) If this method is returning an adress type that is
   *     not an IP address, you must also oerride this method and return the correct code.
   */
  public String getNetworkAddress(RequestDetails theRequestDetails) {
    String remoteAddr = null;
    if (theRequestDetails instanceof ServletRequestDetails) {
      remoteAddr = ((ServletRequestDetails) theRequestDetails).getServletRequest().getRemoteAddr();
    }
    return remoteAddr;
  }

  /**
   * Provides a code representing the appropriate return type for {@link
   * #getNetworkAddress(RequestDetails)}. The default implementation returns {@link
   * BalpConstants#AUDIT_EVENT_AGENT_NETWORK_TYPE_IP_ADDRESS}.
   *
   * @param theRequestDetails The request details object
   * @see #getNetworkAddress(RequestDetails)
   * @see BalpConstants#AUDIT_EVENT_AGENT_NETWORK_TYPE_MACHINE_NAME Potential return type for this
   *     method
   * @see BalpConstants#AUDIT_EVENT_AGENT_NETWORK_TYPE_IP_ADDRESS Potential return type for this
   *     method
   * @see BalpConstants#AUDIT_EVENT_AGENT_NETWORK_TYPE_URI Potential return type for this method
   */
  public AuditEvent.AuditEventAgentNetworkType getNetworkAddressType(
      RequestDetails theRequestDetails) {
    return BalpConstants.AUDIT_EVENT_AGENT_NETWORK_TYPE_IP_ADDRESS;
  }

  public @NotNull Reference getAgentClientWho(RequestDetails requestDetails) {

    return new Reference()
        // .setReference("Device/fhir-info-gateway")
        .setType("Device")
        .setDisplay("FHIR Info Gateway")
        .setIdentifier(
            new Identifier()
                .setSystem("http://fhir-info-gateway/devices")
                .setValue("fhir-info-gateway"));
  }

  public List<AuditEvent> createAuditEventsRead(
      Set<String> compartmentOwners, ServletRequestDetails theRequestDetails) {
    List<AuditEvent> auditEventList = new ArrayList<>();

    if (!compartmentOwners.isEmpty()) {
      for (String owner : compartmentOwners) {
        auditEventList.add(
            createAuditEventPatientRead(
                theRequestDetails, theRequestDetails.getId().getValue(), owner));
      }

    } else {
      auditEventList.add(
          createAuditEventBasicRead(theRequestDetails, theRequestDetails.getId().getValue()));
    }

    return auditEventList;
  }

  @Override
  public void processAuditEvents(
      RequestDetailsReader requestDetailsReader, String serverContentResponse) {

    List<AuditEvent> auditEventList = List.of();

    switch (requestDetailsReader.getRestOperationType()) {
      case SEARCH_TYPE:
      case SEARCH_SYSTEM:
      case GET_PAGE:
        auditEventList = generateAuditEventsSearch(requestDetailsReader);
        break;

      case READ:
      case VREAD:
        auditEventList = generateAuditEventsRead(requestDetailsReader);
        break;

      case CREATE:
        auditEventList = generateAuditEventsCreate(requestDetailsReader, serverContentResponse);
        break;

      case UPDATE:
        auditEventList = generateAuditEventsUpdate(requestDetailsReader, serverContentResponse);
        break;

      case DELETE:
        auditEventList = generateAuditEventsDeleted(requestDetailsReader);
        break;
      default:
        // No actions for other operations
    }

    for (AuditEvent auditEvent : auditEventList) {
      this.iGenericClient.create().resource(auditEvent).encodedJson().execute();
    }
  }

  public List<AuditEvent> generateAuditEventsSearch(RequestDetailsReader requestDetailsReader) {
    Set<String> patientIds =
        patientFinder.findPatientsFromParams(requestDetailsReader).stream()
            .map(it -> "Patient/" + it)
            .collect(Collectors.toSet());
    return createAuditEvents(
        patientIds, (ServletRequestDetails) requestDetailsReader.getRequestDetails());
  }

  public List<AuditEvent> generateAuditEventsRead(RequestDetailsReader requestDetailsReader) {

    Set<String> patientIds =
        patientFinder.findPatientsFromParams(requestDetailsReader).stream()
            .map(it -> "Patient/" + it)
            .collect(Collectors.toSet());

    return createAuditEventsRead(
        patientIds, (ServletRequestDetails) requestDetailsReader.getRequestDetails());
  }

  public List<AuditEvent> generateAuditEventsCreate(
      RequestDetailsReader requestDetailsReader, String responseContent) {

    return handleResourceCreated(
        this.iGenericClient.getFhirContext().newJsonParser().parseResource(responseContent),
        (ServletRequestDetails) requestDetailsReader.getRequestDetails());
  }

  public List<AuditEvent> generateAuditEventsUpdated(
      RequestDetailsReader requestDetailsReader, String responseContent) {

    return handleResourceUpdated(
        this.iGenericClient.getFhirContext().newJsonParser().parseResource(responseContent),
        (ServletRequestDetails) requestDetailsReader.getRequestDetails());
  }

  public List<AuditEvent> generateAuditEventsDeleted(RequestDetailsReader requestDetailsReader) {

    String pseudoResource =
        String.format(
            "{\"resourceType\": \"%s\",\n" + "\"id\": \"%s\"}",
            requestDetailsReader.getResourceName(), requestDetailsReader.getId());

    return handleResourceDeleted(
        this.iGenericClient.getFhirContext().newJsonParser().parseResource(pseudoResource),
        (ServletRequestDetails) requestDetailsReader.getRequestDetails());
  }

  public List<AuditEvent> generateAuditEventsUpdate(
      RequestDetailsReader requestDetailsReader, String responseContent) {
    return generateAuditEventsUpdated(requestDetailsReader, responseContent);
  }

  // To be used when we have a different FHIR version for the destination server/audit repository
  private AuditEvent canonicalizeAuditEventVersion(AuditEvent auditEvent) {
    VersionCanonicalizer versionCanonicalizer =
        new VersionCanonicalizer(this.iGenericClient.getFhirContext());
    return (AuditEvent) versionCanonicalizer.auditEventFromCanonical(auditEvent);
  }

  public static AuditEventHelper createNewInstance(
      FhirContext fhirContext, String baseUrl, Reference agentUserWho) {
    return new AuditEventHelperImpl(fhirContext, baseUrl, agentUserWho);
  }

  public List<AuditEvent> handleResourceCreated(
      IBaseResource theResource, ServletRequestDetails theRequestDetails) {
    return handleCreateUpdateDelete(
        theResource,
        theRequestDetails,
        BalpProfileEnum.BASIC_CREATE,
        BalpProfileEnum.PATIENT_CREATE);
  }

  public List<AuditEvent> handleResourceUpdated(
      IBaseResource theResource, ServletRequestDetails theRequestDetails) {
    return handleCreateUpdateDelete(
        theResource,
        theRequestDetails,
        BalpProfileEnum.BASIC_UPDATE,
        BalpProfileEnum.PATIENT_UPDATE);
  }

  public List<AuditEvent> handleResourceDeleted(
      IBaseResource theResource, ServletRequestDetails theRequestDetails) {
    return handleCreateUpdateDelete(
        theResource,
        theRequestDetails,
        BalpProfileEnum.BASIC_DELETE,
        BalpProfileEnum.PATIENT_DELETE);
  }

  private List<AuditEvent> handleCreateUpdateDelete(
      IBaseResource theResource,
      ServletRequestDetails theRequestDetails,
      BalpProfileEnum theBasicProfile,
      BalpProfileEnum thePatientProfile) {

    List<AuditEvent> auditEventList = new ArrayList<>();

    Set<String> patientCompartmentOwners =
        determinePatientCompartmentOwnersForResources(List.of(theResource), theRequestDetails);
    AuditEvent auditEvent;
    if (patientCompartmentOwners.isEmpty()) {
      auditEvent =
          createAuditEventBasicCreateUpdateDelete(theRequestDetails, theResource, theBasicProfile);
    } else {
      auditEvent =
          createAuditEventPatientCreateUpdateDelete(
              theRequestDetails, theResource, patientCompartmentOwners, thePatientProfile);
    }
    auditEventList.add(auditEvent);

    return auditEventList;
  }

  public List<AuditEvent> createAuditEvents(
      Set<String> compartmentOwners, ServletRequestDetails theRequestDetails) {

    List<AuditEvent> auditEventList = new ArrayList<>();

    if (!compartmentOwners.isEmpty()) {
      for (String owner : compartmentOwners) {
        auditEventList.add(createAuditEventPatientQuery(theRequestDetails, Set.of(owner)));
      }

    } else {
      auditEventList.add(createAuditEventBasicQuery(theRequestDetails));
    }

    return auditEventList;
  }
}
