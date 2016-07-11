/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.web.api;

import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;
import com.wordnik.swagger.annotations.ApiParam;
import com.wordnik.swagger.annotations.ApiResponse;
import com.wordnik.swagger.annotations.ApiResponses;
import com.wordnik.swagger.annotations.Authorization;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.cluster.context.ClusterContext;
import org.apache.nifi.cluster.context.ClusterContextThreadLocal;
import org.apache.nifi.cluster.manager.exception.UnknownNodeException;
import org.apache.nifi.cluster.manager.impl.WebClusterManager;
import org.apache.nifi.cluster.node.Node;
import org.apache.nifi.cluster.protocol.NodeIdentifier;
import org.apache.nifi.stream.io.StreamUtils;
import org.apache.nifi.util.NiFiProperties;
import org.apache.nifi.web.ConfigurationSnapshot;
import org.apache.nifi.web.DownloadableContent;
import org.apache.nifi.web.IllegalClusterResourceRequestException;
import org.apache.nifi.web.NiFiServiceFacade;
import org.apache.nifi.web.Revision;
import org.apache.nifi.web.api.dto.ConnectableDTO;
import org.apache.nifi.web.api.dto.ConnectionDTO;
import org.apache.nifi.web.api.dto.DropRequestDTO;
import org.apache.nifi.web.api.dto.FlowFileDTO;
import org.apache.nifi.web.api.dto.FlowFileSummaryDTO;
import org.apache.nifi.web.api.dto.ListingRequestDTO;
import org.apache.nifi.web.api.dto.PositionDTO;
import org.apache.nifi.web.api.dto.RevisionDTO;
import org.apache.nifi.web.api.dto.status.StatusHistoryDTO;
import org.apache.nifi.web.api.entity.ConnectionEntity;
import org.apache.nifi.web.api.entity.ConnectionsEntity;
import org.apache.nifi.web.api.entity.DropRequestEntity;
import org.apache.nifi.web.api.entity.FlowFileEntity;
import org.apache.nifi.web.api.entity.ListingRequestEntity;
import org.apache.nifi.web.api.entity.StatusHistoryEntity;
import org.apache.nifi.web.api.request.ClientIdParameter;
import org.apache.nifi.web.api.request.ConnectableTypeParameter;
import org.apache.nifi.web.api.request.IntegerParameter;
import org.apache.nifi.web.api.request.LongParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * RESTful endpoint for managing a Connection.
 */
@Api(hidden = true)
public class ConnectionResource extends ApplicationResource {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionResource.class);

    private NiFiServiceFacade serviceFacade;
    private WebClusterManager clusterManager;
    private NiFiProperties properties;
    private String groupId;

    /**
     * Populate the URIs for the specified connections.
     *
     * @param connections connections
     * @return dtos
     */
    public Set<ConnectionDTO> populateRemainingConnectionsContent(Set<ConnectionDTO> connections) {
        for (ConnectionDTO connection : connections) {
            populateRemainingConnectionContent(connection);
        }
        return connections;
    }

    /**
     * Populate the URIs for the specified connection.
     *
     * @param connection connection
     * @return dto
     */
    private ConnectionDTO populateRemainingConnectionContent(ConnectionDTO connection) {
        // populate the remaining properties
        connection.setUri(generateResourceUri("controller", "process-groups", groupId, "connections", connection.getId()));
        return connection;
    }

    /**
     * Populate the URIs for the specified flowfile listing.
     *
     * @param connectionId connection
     * @param flowFileListing flowfile listing
     * @return dto
     */
    public ListingRequestDTO populateRemainingFlowFileListingContent(final String connectionId, final ListingRequestDTO flowFileListing) {
        // uri of the listing
        flowFileListing.setUri(generateResourceUri("controller", "process-groups", groupId, "connections", connectionId, "listing-requests", flowFileListing.getId()));

        // uri of each flowfile
        if (flowFileListing.getFlowFileSummaries() != null) {
            for (FlowFileSummaryDTO flowFile : flowFileListing.getFlowFileSummaries()) {
                populateRemainingFlowFileContent(connectionId, flowFile);
            }
        }
        return flowFileListing;
    }

    /**
     * Populate the URIs for the specified flowfile.
     *
     * @param connectionId the connection id
     * @param flowFile the flowfile
     * @return the dto
     */
    private FlowFileSummaryDTO populateRemainingFlowFileContent(final String connectionId, final FlowFileSummaryDTO flowFile) {
        flowFile.setUri(generateResourceUri("controller", "process-groups", groupId, "connections", connectionId, "flowfiles", flowFile.getUuid()));
        return flowFile;
    }

    /**
     * Gets all the connections.
     *
     * @param clientId Optional client id. If the client id is not specified, a new one will be generated. This value (whether specified or generated) is included in the response.
     * @return A connectionsEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Path("") // necessary due to bug in swagger
    @PreAuthorize("hasAnyRole('ROLE_MONITOR', 'ROLE_DFM', 'ROLE_ADMIN')")
    @ApiOperation(
            value = "Gets all connections",
            response = ConnectionsEntity.class,
            authorizations = {
                @Authorization(value = "Read Only", type = "ROLE_MONITOR"),
                @Authorization(value = "Data Flow Manager", type = "ROLE_DFM"),
                @Authorization(value = "Administrator", type = "ROLE_ADMIN")
            }
    )
    @ApiResponses(
            value = {
                @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                @ApiResponse(code = 401, message = "Client could not be authenticated."),
                @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                @ApiResponse(code = 404, message = "The specified resource could not be found."),
                @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response getConnections(
            @ApiParam(
                    value = "If the client id is not specified, new one will be generated. This value (whether specified or generated) is included in the response.",
                    required = false
            )
            @QueryParam(CLIENT_ID) @DefaultValue(StringUtils.EMPTY) ClientIdParameter clientId) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.GET, getAbsolutePath(), getRequestParameters(true), getHeaders()).getResponse();
        }

        // all of the relationships for the specified source processor
        Set<ConnectionDTO> connections = serviceFacade.getConnections(groupId);

        // create the revision
        RevisionDTO revision = new RevisionDTO();
        revision.setClientId(clientId.getClientId());

        // create the client response entity
        ConnectionsEntity entity = new ConnectionsEntity();
        entity.setRevision(revision);
        entity.setConnections(populateRemainingConnectionsContent(connections));

        // generate the response
        return clusterContext(generateOkResponse(entity)).build();
    }

    /**
     * Retrieves the specified connection.
     *
     * @param clientId Optional client id. If the client id is not specified, a new one will be generated. This value (whether specified or generated) is included in the response.
     * @param id The id of the connection.
     * @return A connectionEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Path("/{id}")
    @PreAuthorize("hasAnyRole('ROLE_MONITOR', 'ROLE_DFM', 'ROLE_ADMIN')")
    @ApiOperation(
            value = "Gets a connection",
            response = ConnectionEntity.class,
            authorizations = {
                @Authorization(value = "Read Only", type = "ROLE_MONITOR"),
                @Authorization(value = "Data Flow Manager", type = "ROLE_DFM"),
                @Authorization(value = "Administrator", type = "ROLE_ADMIN")
            }
    )
    @ApiResponses(
            value = {
                @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                @ApiResponse(code = 401, message = "Client could not be authenticated."),
                @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                @ApiResponse(code = 404, message = "The specified resource could not be found."),
                @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response getConnection(
            @ApiParam(
                    value = "If the client id is not specified, new one will be generated. This value (whether specified or generated) is included in the response.",
                    required = false
            )
            @QueryParam(CLIENT_ID) @DefaultValue(StringUtils.EMPTY) ClientIdParameter clientId,
            @ApiParam(
                    value = "The connection id.",
                    required = true
            )
            @PathParam("id") String id) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.GET, getAbsolutePath(), getRequestParameters(true), getHeaders()).getResponse();
        }

        // get the specified relationship
        ConnectionDTO connection = serviceFacade.getConnection(groupId, id);

        // create the revision
        RevisionDTO revision = new RevisionDTO();
        revision.setClientId(clientId.getClientId());

        // create the response entity
        ConnectionEntity entity = new ConnectionEntity();
        entity.setRevision(revision);
        entity.setConnection(populateRemainingConnectionContent(connection));

        // generate the response
        return clusterContext(generateOkResponse(entity)).build();
    }

    /**
     * Retrieves the specified connection status history.
     *
     * @param clientId Optional client id. If the client id is not specified, a new one will be generated. This value (whether specified or generated) is included in the response.
     * @param id The id of the connection to retrieve.
     * @return A statusHistoryEntity.
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Path("/{id}/status/history")
    @PreAuthorize("hasAnyRole('ROLE_MONITOR', 'ROLE_DFM', 'ROLE_ADMIN')")
    @ApiOperation(
            value = "Gets the status history for a connection",
            response = StatusHistoryEntity.class,
            authorizations = {
                @Authorization(value = "Read Only", type = "ROLE_MONITOR"),
                @Authorization(value = "Data Flow Manager", type = "ROLE_DFM"),
                @Authorization(value = "Administrator", type = "ROLE_ADMIN")
            }
    )
    @ApiResponses(
            value = {
                @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                @ApiResponse(code = 401, message = "Client could not be authenticated."),
                @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                @ApiResponse(code = 404, message = "The specified resource could not be found."),
                @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response getConnectionStatusHistory(
            @ApiParam(
                    value = "If the client id is not specified, new one will be generated. This value (whether specified or generated) is included in the response.",
                    required = false
            )
            @QueryParam(CLIENT_ID) @DefaultValue(StringUtils.EMPTY) ClientIdParameter clientId,
            @ApiParam(
                    value = "The connection id.",
                    required = true
            )
            @PathParam("id") String id) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            throw new IllegalClusterResourceRequestException("This request is only supported in standalone mode.");
        }

        // get the specified processor status history
        final StatusHistoryDTO connectionStatusHistory = serviceFacade.getConnectionStatusHistory(groupId, id);

        // create the revision
        final RevisionDTO revision = new RevisionDTO();
        revision.setClientId(clientId.getClientId());

        // generate the response entity
        final StatusHistoryEntity entity = new StatusHistoryEntity();
        entity.setRevision(revision);
        entity.setStatusHistory(connectionStatusHistory);

        // generate the response
        return clusterContext(generateOkResponse(entity)).build();
    }

    /**
     * Creates a connection.
     *
     * @param httpServletRequest request
     * @param version The revision is used to verify the client is working with the latest version of the flow.
     * @param clientId Optional client id. If the client id is not specified, a new one will be generated. This value (whether specified or generated) is included in the response.
     * @param name The name of the connection.
     * @param sourceId The id of the source connectable.
     * @param sourceGroupId The parent group id for the source.
     * @param sourceType The type of the source connectable.
     * @param bends Array of bend points in string form ["x,y", "x,y", "x,y"]
     * @param relationships Array of relationships.
     * @param flowFileExpiration The flow file expiration in minutes
     * @param backPressureObjectThreshold The object count for when to apply back pressure.
     * @param backPressureDataSizeThreshold The object size for when to apply back pressure.
     * @param prioritizers Array of prioritizer types. These types should refer to one of the types in the GET /controller/prioritizers response. If this parameter is not specified no change will be
     * made. If this parameter appears with no value (empty string), it will be treated as an empty array.
     * @param destinationId The id of the destination connectable.
     * @param destinationGroupId The parent group id for the destination.
     * @param destinationType The type of the destination connectable.
     * @param formParams params
     * @return A connectionEntity.
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Path("") // necessary due to bug in swagger
    @PreAuthorize("hasRole('ROLE_DFM')")
    public Response createConnection(
            @Context HttpServletRequest httpServletRequest,
            @FormParam(VERSION) LongParameter version,
            @FormParam(CLIENT_ID) @DefaultValue(StringUtils.EMPTY) ClientIdParameter clientId,
            @FormParam("name") String name,
            @FormParam("sourceId") String sourceId,
            @FormParam("sourceGroupId") String sourceGroupId,
            @FormParam("sourceType") ConnectableTypeParameter sourceType,
            @FormParam("relationships[]") Set<String> relationships,
            @FormParam("bends[]") List<String> bends,
            @FormParam("flowFileExpiration") String flowFileExpiration,
            @FormParam("backPressureObjectThreshold") LongParameter backPressureObjectThreshold,
            @FormParam("backPressureDataSizeThreshold") String backPressureDataSizeThreshold,
            @FormParam("prioritizers[]") List<String> prioritizers,
            @FormParam("destinationId") String destinationId,
            @FormParam("destinationGroupId") String destinationGroupId,
            @FormParam("destinationType") ConnectableTypeParameter destinationType,
            MultivaluedMap<String, String> formParams) {

        if (sourceId == null || sourceGroupId == null || destinationId == null || destinationGroupId == null) {
            throw new IllegalArgumentException("The source and destination (and parent groups) must be specified.");
        }

        // ensure the source and destination type has been specified
        if (sourceType == null || destinationType == null) {
            throw new IllegalArgumentException("The source and destination type must be specified.");
        }

        // create the source dto
        final ConnectableDTO source = new ConnectableDTO();
        source.setId(sourceId);
        source.setType(sourceType.getConnectableType().name());
        source.setGroupId(sourceGroupId);

        // create the destination dto
        final ConnectableDTO destination = new ConnectableDTO();
        destination.setId(destinationId);
        destination.setType(destinationType.getConnectableType().name());
        destination.setGroupId(destinationGroupId);

        // create the connection dto
        final ConnectionDTO connectionDTO = new ConnectionDTO();
        connectionDTO.setName(name);
        connectionDTO.setSource(source);
        connectionDTO.setDestination(destination);

        // only set the relationships when applicable
        if (!relationships.isEmpty() || formParams.containsKey("relationships[]")) {
            connectionDTO.setSelectedRelationships(relationships);
        }

        connectionDTO.setFlowFileExpiration(flowFileExpiration);
        connectionDTO.setBackPressureDataSizeThreshold(backPressureDataSizeThreshold);

        if (backPressureObjectThreshold != null) {
            connectionDTO.setBackPressureObjectThreshold(backPressureObjectThreshold.getLong());
        }

        // handle the bends when applicable
        if (!bends.isEmpty() || formParams.containsKey("bends[]")) {
            final List<PositionDTO> bendPoints = new ArrayList<>(bends.size());
            for (final String bend : bends) {
                final String[] coordinate = bend.split(",");

                // ensure the appropriate number of tokens
                if (coordinate.length != 2) {
                    throw new IllegalArgumentException("Bend points should be an array where each entry is in the form 'x,y'");
                }

                // convert the coordinate
                final Double x;
                final Double y;
                try {
                    x = Double.parseDouble(coordinate[0].trim());
                    y = Double.parseDouble(coordinate[1].trim());
                } catch (final NumberFormatException nfe) {
                    throw new IllegalArgumentException("Bend points should be an array where each entry is in the form 'x,y'");
                }

                // add the bend point
                bendPoints.add(new PositionDTO(x, y));
            }

            // set the bend points
            connectionDTO.setBends(bendPoints);
        }

        // create prioritizer list
        final List<String> prioritizerTypes = new ArrayList<>(prioritizers.size());

        // add each prioritizer specified
        for (String rawPrioritizer : prioritizers) {
            // when prioritizers[] is specified in the request with no value, it creates an array
            // with a single element (empty string). an empty array is created when prioritizers[]
            // is not found in the request
            if (StringUtils.isNotBlank(rawPrioritizer)) {
                prioritizerTypes.add(rawPrioritizer);
            }
        }

        // only set the prioritizers when appropriate
        if (!prioritizerTypes.isEmpty() || formParams.containsKey("prioritizers[]")) {
            connectionDTO.setPrioritizers(prioritizerTypes);
        }

        // create the revision
        final RevisionDTO revision = new RevisionDTO();
        revision.setClientId(clientId.getClientId());

        if (version != null) {
            revision.setVersion(version.getLong());
        }

        // create the connection entity
        final ConnectionEntity entity = new ConnectionEntity();
        entity.setRevision(revision);
        entity.setConnection(connectionDTO);

        // create the relationship target
        return createConnection(httpServletRequest, entity);
    }

    /**
     * Creates a new connection.
     *
     * @param httpServletRequest request
     * @param connectionEntity A connectionEntity.
     * @return A connectionEntity.
     */
    @POST
    @Consumes({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Path("") // necessary due to bug in swagger
    @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
            value = "Creates a connection",
            response = ConnectionEntity.class,
            authorizations = {
                @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
            }
    )
    @ApiResponses(
            value = {
                @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                @ApiResponse(code = 401, message = "Client could not be authenticated."),
                @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                @ApiResponse(code = 404, message = "The specified resource could not be found."),
                @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response createConnection(
            @Context HttpServletRequest httpServletRequest,
            @ApiParam(
                    value = "The connection configuration details.",
                    required = true
            ) ConnectionEntity connectionEntity) {

        if (connectionEntity == null || connectionEntity.getConnection() == null) {
            throw new IllegalArgumentException("Connection details must be specified.");
        }

        if (connectionEntity.getConnection().getId() != null) {
            throw new IllegalArgumentException("Connection ID cannot be specified.");
        }

        if (connectionEntity.getRevision() == null) {
            throw new IllegalArgumentException("Revision must be specified.");
        }

        // if cluster manager, convert POST to PUT (to maintain same ID across nodes) and replicate
        if (properties.isClusterManager()) {

            // create ID for resource
            final String id = UUID.randomUUID().toString();

            // set ID for resource
            connectionEntity.getConnection().setId(id);

            // convert POST request to PUT request to force entity ID to be the same across nodes
            URI putUri = null;
            try {
                putUri = new URI(getAbsolutePath().toString() + "/" + id);
            } catch (final URISyntaxException e) {
                throw new WebApplicationException(e);
            }

            // change content type to JSON for serializing entity
            final Map<String, String> headersToOverride = new HashMap<>();
            headersToOverride.put("content-type", MediaType.APPLICATION_JSON);

            // replicate put request
            return (Response) clusterManager.applyRequest(HttpMethod.PUT, putUri, updateClientId(connectionEntity), getHeaders(headersToOverride)).getResponse();
        }

        // get the connection
        final ConnectionDTO connection = connectionEntity.getConnection();

        // handle expects request (usually from the cluster manager)
        final String expects = httpServletRequest.getHeader(WebClusterManager.NCM_EXPECTS_HTTP_HEADER);
        if (expects != null) {
            serviceFacade.verifyCreateConnection(groupId, connection);
            return generateContinueResponse().build();
        }

        // create the new relationship target
        final RevisionDTO revision = connectionEntity.getRevision();
        final ConfigurationSnapshot<ConnectionDTO> controllerResponse = serviceFacade.createConnection(
                new Revision(revision.getVersion(), revision.getClientId()), groupId, connection);
        ConnectionDTO connectionDTO = controllerResponse.getConfiguration();

        // marshall the target and add the source processor
        populateRemainingConnectionContent(connectionDTO);

        // get the updated revision
        final RevisionDTO updatedRevision = new RevisionDTO();
        updatedRevision.setClientId(revision.getClientId());
        updatedRevision.setVersion(controllerResponse.getVersion());

        // create the response entity
        ConnectionEntity entity = new ConnectionEntity();
        entity.setRevision(updatedRevision);
        entity.setConnection(connectionDTO);

        // extract the href and build the response
        String href = connectionDTO.getUri();

        return clusterContext(generateCreatedResponse(URI.create(href), entity)).build();
    }

    /**
     * Updates the specified relationship target.
     *
     * @param httpServletRequest request
     * @param version The revision is used to verify the client is working with the latest version of the flow.
     * @param clientId Optional client id. If the client id is not specified, a new one will be generated. This value (whether specified or generated) is included in the response.
     * @param connectionId The id of the source processor.
     * @param name The name of the connection.
     * @param relationships Array of relationships.
     * @param bends Array of bend points in string form ["x,y", "x,y", "x,y"]
     * @param labelIndex The control point index for the connection label
     * @param zIndex The zIndex for this connection
     * @param flowFileExpiration The flow file expiration in minutes
     * @param backPressureObjectThreshold The object count for when to apply back pressure.
     * @param backPressureDataSizeThreshold The object size for when to apply back pressure.
     * @param prioritizers Array of prioritizer types. These types should refer to one of the types in the GET /controller/prioritizers response. If this parameter is not specified no change will be
     * made. If this parameter appears with no value (empty string), it will be treated as an empty array.
     * @param destinationId The id of the destination connectable.
     * @param destinationGroupId The group id of the destination.
     * @param destinationType The type of the destination type.
     * @param formParams params
     * @return A connectionEntity.
     */
    @PUT
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Path("/{id}")
    @PreAuthorize("hasRole('ROLE_DFM')")
    public Response updateConnection(
            @Context HttpServletRequest httpServletRequest,
            @FormParam(VERSION) LongParameter version,
            @FormParam(CLIENT_ID) @DefaultValue(StringUtils.EMPTY) ClientIdParameter clientId,
            @PathParam("id") String connectionId,
            @FormParam("name") String name,
            @FormParam("relationships[]") Set<String> relationships,
            @FormParam("bends[]") List<String> bends,
            @FormParam("labelIndex") IntegerParameter labelIndex,
            @FormParam("zIndex") LongParameter zIndex,
            @FormParam("flowFileExpiration") String flowFileExpiration,
            @FormParam("backPressureObjectThreshold") LongParameter backPressureObjectThreshold,
            @FormParam("backPressureDataSizeThreshold") String backPressureDataSizeThreshold,
            @FormParam("prioritizers[]") List<String> prioritizers,
            @FormParam("destinationId") String destinationId,
            @FormParam("destinationGroupId") String destinationGroupId,
            @FormParam("destinationType") ConnectableTypeParameter destinationType,
            MultivaluedMap<String, String> formParams) {

        // create the target connectable if necessary
        ConnectableDTO destination = null;
        if (destinationId != null) {
            if (destinationGroupId == null) {
                throw new IllegalArgumentException("The destination group must be specified.");
            }

            if (destinationType == null) {
                throw new IllegalArgumentException("The destination type must be specified.");
            }

            destination = new ConnectableDTO();
            destination.setId(destinationId);
            destination.setType(destinationType.getConnectableType().name());
            destination.setGroupId(destinationGroupId);
        }

        // create the relationship target dto
        final ConnectionDTO connectionDTO = new ConnectionDTO();
        connectionDTO.setId(connectionId);
        connectionDTO.setName(name);
        connectionDTO.setDestination(destination);
        if (labelIndex != null) {
            connectionDTO.setLabelIndex(labelIndex.getInteger());
        }
        if (zIndex != null) {
            connectionDTO.setzIndex(zIndex.getLong());
        }

        // handle the bends when applicable
        if (!bends.isEmpty() || formParams.containsKey("bends[]")) {
            final List<PositionDTO> bendPoints = new ArrayList<>(bends.size());
            for (final String bend : bends) {
                final String[] coordinate = bend.split(",");

                // ensure the appropriate number of tokens
                if (coordinate.length != 2) {
                    throw new IllegalArgumentException("Bend points should be an array where each entry is in the form 'x,y'");
                }

                // convert the coordinate
                final Double x;
                final Double y;
                try {
                    x = Double.parseDouble(coordinate[0].trim());
                    y = Double.parseDouble(coordinate[1].trim());
                } catch (final NumberFormatException nfe) {
                    throw new IllegalArgumentException("Bend points should be an array where each entry is in the form 'x,y'");
                }

                // add the bend point
                bendPoints.add(new PositionDTO(x, y));
            }

            // set the bend points
            connectionDTO.setBends(bendPoints);
        }

        // only set the relationships when applicable
        if (!relationships.isEmpty() || formParams.containsKey("relationships[]")) {
            connectionDTO.setSelectedRelationships(relationships);
        }

        connectionDTO.setFlowFileExpiration(flowFileExpiration);
        connectionDTO.setBackPressureDataSizeThreshold(backPressureDataSizeThreshold);

        if (backPressureObjectThreshold != null) {
            connectionDTO.setBackPressureObjectThreshold(backPressureObjectThreshold.getLong());
        }

        // create prioritizer list
        final List<String> prioritizerTypes = new ArrayList<>(prioritizers.size());

        // add each prioritizer specified
        for (final String rawPrioritizer : prioritizers) {
            // when prioritizers[] is specified in the request with no value, it creates an array
            // with a single element (empty string). an empty array is created when prioritizers[]
            // is not found in the request
            if (StringUtils.isNotBlank(rawPrioritizer)) {
                prioritizerTypes.add(rawPrioritizer);
            }
        }

        // only set the prioritizers when appropriate
        if (!prioritizerTypes.isEmpty() || formParams.containsKey("prioritizers[]")) {
            connectionDTO.setPrioritizers(prioritizerTypes);
        }

        // create the revision
        final RevisionDTO revision = new RevisionDTO();
        revision.setClientId(clientId.getClientId());

        if (version != null) {
            revision.setVersion(version.getLong());
        }

        // create the connection entity
        final ConnectionEntity entity = new ConnectionEntity();
        entity.setRevision(revision);
        entity.setConnection(connectionDTO);

        // update the relationship target
        return updateConnection(httpServletRequest, connectionId, entity);
    }

    /**
     * Updates the specified connection.
     *
     * @param httpServletRequest request
     * @param id The id of the connection.
     * @param connectionEntity A connectionEntity.
     * @return A connectionEntity.
     */
    @PUT
    @Consumes({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Path("/{id}")
    @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
            value = "Updates a connection",
            response = ConnectionEntity.class,
            authorizations = {
                @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
            }
    )
    @ApiResponses(
            value = {
                @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                @ApiResponse(code = 401, message = "Client could not be authenticated."),
                @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                @ApiResponse(code = 404, message = "The specified resource could not be found."),
                @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response updateConnection(
            @Context HttpServletRequest httpServletRequest,
            @ApiParam(
                    value = "The connection id.",
                    required = true
            )
            @PathParam("id") String id,
            @ApiParam(
                    value = "The connection configuration details.",
                    required = true
            ) ConnectionEntity connectionEntity) {

        if (connectionEntity == null || connectionEntity.getConnection() == null) {
            throw new IllegalArgumentException("Connection details must be specified.");
        }

        if (connectionEntity.getRevision() == null) {
            throw new IllegalArgumentException("Revision must be specified.");
        }

        // ensure the ids are the same
        final ConnectionDTO connection = connectionEntity.getConnection();
        if (!id.equals(connection.getId())) {
            throw new IllegalArgumentException(String.format("The connection id "
                    + "(%s) in the request body does not equal the connection id of the "
                    + "requested resource (%s).", connection.getId(), id));
        }

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            // change content type to JSON for serializing entity
            final Map<String, String> headersToOverride = new HashMap<>();
            headersToOverride.put("content-type", MediaType.APPLICATION_JSON);

            // replicate the request
            return clusterManager.applyRequest(HttpMethod.PUT, getAbsolutePath(), updateClientId(connectionEntity), getHeaders(headersToOverride)).getResponse();
        }

        // handle expects request (usually from the cluster manager)
        final String expects = httpServletRequest.getHeader(WebClusterManager.NCM_EXPECTS_HTTP_HEADER);
        if (expects != null) {
            serviceFacade.verifyUpdateConnection(groupId, connection);
            return generateContinueResponse().build();
        }

        // update the relationship target
        final RevisionDTO revision = connectionEntity.getRevision();
        final ConfigurationSnapshot<ConnectionDTO> controllerResponse = serviceFacade.updateConnection(
                new Revision(revision.getVersion(), revision.getClientId()), groupId, connection);

        // get the updated revision
        final RevisionDTO updatedRevision = new RevisionDTO();
        updatedRevision.setClientId(revision.getClientId());
        updatedRevision.setVersion(controllerResponse.getVersion());

        // marshall the target and add the source processor
        final ConnectionDTO connectionDTO = controllerResponse.getConfiguration();
        populateRemainingConnectionContent(connectionDTO);

        // create the response entity
        ConnectionEntity entity = new ConnectionEntity();
        entity.setRevision(updatedRevision);
        entity.setConnection(connectionDTO);

        // generate the response
        if (controllerResponse.isNew()) {
            return clusterContext(generateCreatedResponse(URI.create(connectionDTO.getUri()), entity)).build();
        } else {
            return clusterContext(generateOkResponse(entity)).build();
        }
    }

    /**
     * Removes the specified connection.
     *
     * @param httpServletRequest request
     * @param version The revision is used to verify the client is working with the latest version of the flow.
     * @param clientId Optional client id. If the client id is not specified, a new one will be generated. This value (whether specified or generated) is included in the response.
     * @param id The id of the connection.
     * @return An Entity containing the client id and an updated revision.
     */
    @DELETE
    @Consumes(MediaType.WILDCARD)
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Path("/{id}")
    @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
            value = "Deletes a connection",
            response = ConnectionEntity.class,
            authorizations = {
                @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
            }
    )
    @ApiResponses(
            value = {
                @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                @ApiResponse(code = 401, message = "Client could not be authenticated."),
                @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                @ApiResponse(code = 404, message = "The specified resource could not be found."),
                @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response deleteConnection(
            @Context HttpServletRequest httpServletRequest,
            @ApiParam(
                    value = "The revision is used to verify the client is working with the latest version of the flow.",
                    required = false
            )
            @QueryParam(VERSION) LongParameter version,
            @ApiParam(
                    value = "If the client id is not specified, new one will be generated. This value (whether specified or generated) is included in the response.",
                    required = false
            )
            @QueryParam(CLIENT_ID) @DefaultValue(StringUtils.EMPTY) ClientIdParameter clientId,
            @ApiParam(
                    value = "The connection id.",
                    required = true
            )
            @PathParam("id") String id) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.DELETE, getAbsolutePath(), getRequestParameters(true), getHeaders()).getResponse();
        }

        // handle expects request (usually from the cluster manager)
        final String expects = httpServletRequest.getHeader(WebClusterManager.NCM_EXPECTS_HTTP_HEADER);
        if (expects != null) {
            serviceFacade.verifyDeleteConnection(groupId, id);
            return generateContinueResponse().build();
        }

        // determine the specified version
        Long clientVersion = null;
        if (version != null) {
            clientVersion = version.getLong();
        }

        // delete the connection
        final ConfigurationSnapshot<Void> controllerResponse = serviceFacade.deleteConnection(new Revision(clientVersion, clientId.getClientId()), groupId, id);

        // create the revision
        final RevisionDTO updatedRevision = new RevisionDTO();
        updatedRevision.setClientId(clientId.getClientId());
        updatedRevision.setVersion(controllerResponse.getVersion());

        // create the response entity
        final ConnectionEntity entity = new ConnectionEntity();
        entity.setRevision(updatedRevision);

        // generate the response
        return clusterContext(generateOkResponse(entity)).build();
    }

    /**
     * Gets the specified flowfile from the specified connection.
     *
     * @param clientId Optional client id. If the client id is not specified, a new one will be generated. This value (whether specified or generated) is included in the response.
     * @param connectionId The connection id
     * @param flowFileUuid The flowfile uuid
     * @param clusterNodeId The cluster node id where the flowfile resides
     * @return a flowFileDTO
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Path("/{connection-id}/flowfiles/{flowfile-uuid}")
    @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
        value = "Gets a FlowFile from a Connection.",
        authorizations = {
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response getFlowFile(
            @ApiParam(
                value = "If the client id is not specified, new one will be generated. This value (whether specified or generated) is included in the response.",
                required = false
            )
            @QueryParam(CLIENT_ID) @DefaultValue(StringUtils.EMPTY) ClientIdParameter clientId,
            @ApiParam(
                value = "The connection id.",
                required = true
            )
            @PathParam("connection-id") String connectionId,
            @ApiParam(
                value = "The flowfile uuid.",
                required = true
            )
            @PathParam("flowfile-uuid") String flowFileUuid,
            @ApiParam(
                value = "The id of the node where the content exists if clustered.",
                required = false
            )
            @QueryParam("clusterNodeId") String clusterNodeId) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            // determine where this request should be sent
            if (clusterNodeId == null) {
                throw new IllegalArgumentException("The id of the node in the cluster is required.");
            } else {
                // get the target node and ensure it exists
                final Node targetNode = clusterManager.getNode(clusterNodeId);
                if (targetNode == null) {
                    throw new UnknownNodeException("The specified cluster node does not exist.");
                }

                final Set<NodeIdentifier> targetNodes = new HashSet<>();
                targetNodes.add(targetNode.getNodeId());

                // replicate the request to the specific node
                return clusterManager.applyRequest(HttpMethod.GET, getAbsolutePath(), getRequestParameters(true), getHeaders(), targetNodes).getResponse();
            }
        }

        // get the flowfile
        final FlowFileDTO flowfileDto = serviceFacade.getFlowFile(groupId, connectionId, flowFileUuid);
        populateRemainingFlowFileContent(connectionId, flowfileDto);

        // create the revision
        final RevisionDTO revision = new RevisionDTO();
        revision.setClientId(clientId.getClientId());

        // create the response entity
        final FlowFileEntity entity = new FlowFileEntity();
        entity.setRevision(revision);
        entity.setFlowFile(flowfileDto);

        return generateOkResponse(entity).build();
    }

    /**
     * Gets the content for the specified flowfile in the specified connection.
     *
     * @param clientId Optional client id. If the client id is not specified, a new one will be generated. This value (whether specified or generated) is included in the response.
     * @param connectionId The connection id
     * @param flowFileUuid The flowfile uuid
     * @param clusterNodeId The cluster node id
     * @return The content stream
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.WILDCARD)
    @Path("/{connection-id}/flowfiles/{flowfile-uuid}/content")
    @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
        value = "Gets the content for a FlowFile in a Connection.",
        authorizations = {
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response downloadFlowFileContent(
            @ApiParam(
                value = "If the client id is not specified, new one will be generated. This value (whether specified or generated) is included in the response.",
                required = false
            )
            @QueryParam(CLIENT_ID) @DefaultValue(StringUtils.EMPTY) ClientIdParameter clientId,
            @ApiParam(
                value = "The connection id.",
                required = true
            )
            @PathParam("connection-id") String connectionId,
            @ApiParam(
                value = "The flowfile uuid.",
                required = true
            )
            @PathParam("flowfile-uuid") String flowFileUuid,
            @ApiParam(
                value = "The id of the node where the content exists if clustered.",
                required = false
            )
            @QueryParam("clusterNodeId") String clusterNodeId) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            // determine where this request should be sent
            if (clusterNodeId == null) {
                throw new IllegalArgumentException("The id of the node in the cluster is required.");
            } else {
                // get the target node and ensure it exists
                final Node targetNode = clusterManager.getNode(clusterNodeId);
                if (targetNode == null) {
                    throw new UnknownNodeException("The specified cluster node does not exist.");
                }

                final Set<NodeIdentifier> targetNodes = new HashSet<>();
                targetNodes.add(targetNode.getNodeId());

                // replicate the request to the specific node
                return clusterManager.applyRequest(HttpMethod.GET, getAbsolutePath(), getRequestParameters(true), getHeaders(), targetNodes).getResponse();
            }
        }

        // get the uri of the request
        final String uri = generateResourceUri("controller", "process-groups", groupId, "connections", connectionId, "flowfiles", flowFileUuid, "content");

        // get an input stream to the content
        final DownloadableContent content = serviceFacade.getContent(groupId, connectionId, flowFileUuid, uri);

        // generate a streaming response
        final StreamingOutput response = new StreamingOutput() {
            @Override
            public void write(OutputStream output) throws IOException, WebApplicationException {
                try (InputStream is = content.getContent()) {
                    // stream the content to the response
                    StreamUtils.copy(is, output);

                    // flush the response
                    output.flush();
                }
            }
        };

        // use the appropriate content type
        String contentType = content.getType();
        if (contentType == null) {
            contentType = MediaType.APPLICATION_OCTET_STREAM;
        }

        return generateOkResponse(response).type(contentType).header("Content-Disposition", String.format("attachment; filename=\"%s\"", content.getFilename())).build();
    }

    /**
     * Drops the flowfiles in the queue of the specified connection. This endpoint is DEPRECATED. Please use
     * POST /nifi-api/controller/process-groups/{process-group-id}/connections/{connection-id}/drop-requests instead.
     *
     * @param httpServletRequest request
     * @param clientId Optional client id. If the client id is not specified, a new one will be generated. This value (whether specified or generated) is included in the response.
     * @param id The id of the connection
     * @return A dropRequestEntity
     */
    @DELETE
    @Consumes(MediaType.WILDCARD)
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Path("/{connection-id}/contents")
    @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
            value = "Drops the contents of the queue in this connection.",
            notes = "This endpoint is DEPRECATED. Please use POST /nifi-api/controller/process-groups/{process-group-id}/connections/{connection-id}/drop-requests instead.",
            response = DropRequestEntity.class,
            authorizations = {
                @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
            }
    )
    @ApiResponses(
            value = {
                @ApiResponse(code = 202, message = "The request has been accepted. A HTTP response header will contain the URI where the response can be polled."),
                @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                @ApiResponse(code = 401, message = "Client could not be authenticated."),
                @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                @ApiResponse(code = 404, message = "The specified resource could not be found."),
                @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    @Deprecated
    public Response dropQueueContents(
            @Context HttpServletRequest httpServletRequest,
            @ApiParam(
                    value = "If the client id is not specified, new one will be generated. This value (whether specified or generated) is included in the response.",
                    required = false
            )
            @QueryParam(CLIENT_ID) @DefaultValue(StringUtils.EMPTY) ClientIdParameter clientId,
            @ApiParam(
                    value = "The connection id.",
                    required = true
            )
            @PathParam("connection-id") String id) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.DELETE, getAbsolutePath(), getRequestParameters(true), getHeaders()).getResponse();
        }

        // defer to the new endpoint that references /drop-requests in the URI
        return createDropRequest(httpServletRequest, clientId, id);
    }

    /**
     * Creates a request to list the flowfiles in the queue of the specified connection.
     *
     * @param httpServletRequest request
     * @param clientId Optional client id. If the client id is not specified, a new one will be generated. This value (whether specified or generated) is included in the response.
     * @param id The id of the connection
     * @return A listRequestEntity
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Path("/{connection-id}/listing-requests")
    @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
        value = "Lists the contents of the queue in this connection.",
        response = ListingRequestEntity.class,
        authorizations = {
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 202, message = "The request has been accepted. A HTTP response header will contain the URI where the response can be polled."),
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response createFlowFileListing(
            @Context HttpServletRequest httpServletRequest,
            @ApiParam(
                value = "If the client id is not specified, new one will be generated. This value (whether specified or generated) is included in the response.",
                required = false
            )
            @FormParam(CLIENT_ID) @DefaultValue(StringUtils.EMPTY) ClientIdParameter clientId,
            @ApiParam(
                value = "The connection id.",
                required = true
            )
            @PathParam("connection-id") String id) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.POST, getAbsolutePath(), getRequestParameters(true), getHeaders()).getResponse();
        }

        // handle expects request (usually from the cluster manager)
        final String expects = httpServletRequest.getHeader(WebClusterManager.NCM_EXPECTS_HTTP_HEADER);
        if (expects != null) {
            serviceFacade.verifyListQueue(groupId, id);
            return generateContinueResponse().build();
        }

        // ensure the id is the same across the cluster
        final String listingRequestId;
        final ClusterContext clusterContext = ClusterContextThreadLocal.getContext();
        if (clusterContext != null) {
            listingRequestId = UUID.nameUUIDFromBytes(clusterContext.getIdGenerationSeed().getBytes(StandardCharsets.UTF_8)).toString();
        } else {
            listingRequestId = UUID.randomUUID().toString();
        }

        // submit the listing request
        final ListingRequestDTO listingRequest = serviceFacade.createFlowFileListingRequest(groupId, id, listingRequestId);
        populateRemainingFlowFileListingContent(id, listingRequest);

        // create the revision
        final RevisionDTO revision = new RevisionDTO();
        revision.setClientId(clientId.getClientId());

        // create the response entity
        final ListingRequestEntity entity = new ListingRequestEntity();
        entity.setRevision(revision);
        entity.setListingRequest(listingRequest);

        // generate the URI where the response will be
        final URI location = URI.create(listingRequest.getUri());
        return Response.status(Status.ACCEPTED).location(location).entity(entity).build();
    }

    /**
     * Checks the status of an outstanding listing request.
     *
     * @param clientId Optional client id. If the client id is not specified, a new one will be generated. This value (whether specified or generated) is included in the response.
     * @param connectionId The id of the connection
     * @param listingRequestId The id of the drop request
     * @return A dropRequestEntity
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Path("/{connection-id}/listing-requests/{listing-request-id}")
    @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
        value = "Gets the current status of a listing request for the specified connection.",
        response = ListingRequestEntity.class,
        authorizations = {
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response getListingRequest(
            @ApiParam(
                value = "If the client id is not specified, new one will be generated. This value (whether specified or generated) is included in the response.",
                required = false
            )
            @QueryParam(CLIENT_ID) @DefaultValue(StringUtils.EMPTY) ClientIdParameter clientId,
            @ApiParam(
                value = "The connection id.",
                required = true
            )
            @PathParam("connection-id") String connectionId,
            @ApiParam(
                value = "The listing request id.",
                required = true
            )
            @PathParam("listing-request-id") String listingRequestId) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.GET, getAbsolutePath(), getRequestParameters(true), getHeaders()).getResponse();
        }

        // get the listing request
        final ListingRequestDTO listingRequest = serviceFacade.getFlowFileListingRequest(groupId, connectionId, listingRequestId);
        populateRemainingFlowFileListingContent(connectionId, listingRequest);

        // create the revision
        final RevisionDTO revision = new RevisionDTO();
        revision.setClientId(clientId.getClientId());

        // create the response entity
        final ListingRequestEntity entity = new ListingRequestEntity();
        entity.setRevision(revision);
        entity.setListingRequest(listingRequest);

        return generateOkResponse(entity).build();
    }

    /**
     * Deletes the specified listing request.
     *
     * @param httpServletRequest request
     * @param clientId Optional client id. If the client id is not specified, a new one will be generated. This value (whether specified or generated) is included in the response.
     * @param connectionId The connection id
     * @param listingRequestId The drop request id
     * @return A dropRequestEntity
     */
    @DELETE
    @Consumes(MediaType.WILDCARD)
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Path("/{connection-id}/listing-requests/{listing-request-id}")
    @ApiOperation(
        value = "Cancels and/or removes a request to list the contents of this connection.",
        response = DropRequestEntity.class,
        authorizations = {
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response deleteListingRequest(
            @Context HttpServletRequest httpServletRequest,
            @ApiParam(
                value = "If the client id is not specified, new one will be generated. This value (whether specified or generated) is included in the response.",
                required = false
            )
            @QueryParam(CLIENT_ID) @DefaultValue(StringUtils.EMPTY) ClientIdParameter clientId,
            @ApiParam(
                value = "The connection id.",
                required = true
            )
            @PathParam("connection-id") String connectionId,
            @ApiParam(
                value = "The listing request id.",
                required = true
            )
            @PathParam("listing-request-id") String listingRequestId) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.DELETE, getAbsolutePath(), getRequestParameters(true), getHeaders()).getResponse();
        }

        // handle expects request (usually from the cluster manager)
        final String expects = httpServletRequest.getHeader(WebClusterManager.NCM_EXPECTS_HTTP_HEADER);
        if (expects != null) {
            return generateContinueResponse().build();
        }

        // delete the listing request
        final ListingRequestDTO listingRequest = serviceFacade.deleteFlowFileListingRequest(groupId, connectionId, listingRequestId);

        // prune the results as they were already received when the listing completed
        listingRequest.setFlowFileSummaries(null);

        // populate remaining content
        populateRemainingFlowFileListingContent(connectionId, listingRequest);

        // create the revision
        final RevisionDTO revision = new RevisionDTO();
        revision.setClientId(clientId.getClientId());

        // create the response entity
        final ListingRequestEntity entity = new ListingRequestEntity();
        entity.setRevision(revision);
        entity.setListingRequest(listingRequest);

        return generateOkResponse(entity).build();
    }

    /**
     * Creates a request to delete the flowfiles in the queue of the specified connection.
     *
     * @param httpServletRequest request
     * @param clientId Optional client id. If the client id is not specified, a new one will be generated. This value (whether specified or generated) is included in the response.
     * @param id The id of the connection
     * @return A dropRequestEntity
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Path("/{connection-id}/drop-requests")
    @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
        value = "Creates a request to drop the contents of the queue in this connection.",
        response = DropRequestEntity.class,
        authorizations = {
            @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
        }
    )
    @ApiResponses(
        value = {
            @ApiResponse(code = 202, message = "The request has been accepted. A HTTP response header will contain the URI where the response can be polled."),
            @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
            @ApiResponse(code = 401, message = "Client could not be authenticated."),
            @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
            @ApiResponse(code = 404, message = "The specified resource could not be found."),
            @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
        }
    )
    public Response createDropRequest(
        @Context HttpServletRequest httpServletRequest,
        @ApiParam(
            value = "If the client id is not specified, new one will be generated. This value (whether specified or generated) is included in the response.",
            required = false
        )
        @FormParam(CLIENT_ID) @DefaultValue(StringUtils.EMPTY) ClientIdParameter clientId,
        @ApiParam(
            value = "The connection id.",
            required = true
        )
        @PathParam("connection-id") String id) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.POST, getAbsolutePath(), getRequestParameters(true), getHeaders()).getResponse();
        }

        // handle expects request (usually from the cluster manager)
        final String expects = httpServletRequest.getHeader(WebClusterManager.NCM_EXPECTS_HTTP_HEADER);
        if (expects != null) {
            return generateContinueResponse().build();
        }

        // ensure the id is the same across the cluster
        final String dropRequestId;
        final ClusterContext clusterContext = ClusterContextThreadLocal.getContext();
        if (clusterContext != null) {
            dropRequestId = UUID.nameUUIDFromBytes(clusterContext.getIdGenerationSeed().getBytes(StandardCharsets.UTF_8)).toString();
        } else {
            dropRequestId = UUID.randomUUID().toString();
        }

        // submit the drop request
        final DropRequestDTO dropRequest = serviceFacade.createFlowFileDropRequest(groupId, id, dropRequestId);
        dropRequest.setUri(generateResourceUri("controller", "process-groups", groupId, "connections", id, "drop-requests", dropRequest.getId()));

        // create the revision
        final RevisionDTO revision = new RevisionDTO();
        revision.setClientId(clientId.getClientId());

        // create the response entity
        final DropRequestEntity entity = new DropRequestEntity();
        entity.setRevision(revision);
        entity.setDropRequest(dropRequest);

        // generate the URI where the response will be
        final URI location = URI.create(dropRequest.getUri());
        return Response.status(Status.ACCEPTED).location(location).entity(entity).build();
    }

    /**
     * Checks the status of an outstanding drop request.
     *
     * @param clientId Optional client id. If the client id is not specified, a new one will be generated. This value (whether specified or generated) is included in the response.
     * @param connectionId The id of the connection
     * @param dropRequestId The id of the drop request
     * @return A dropRequestEntity
     */
    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Path("/{connection-id}/drop-requests/{drop-request-id}")
    @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
            value = "Gets the current status of a drop request for the specified connection.",
            response = DropRequestEntity.class,
            authorizations = {
                @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
            }
    )
    @ApiResponses(
            value = {
                @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                @ApiResponse(code = 401, message = "Client could not be authenticated."),
                @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                @ApiResponse(code = 404, message = "The specified resource could not be found."),
                @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response getDropRequest(
            @ApiParam(
                    value = "If the client id is not specified, new one will be generated. This value (whether specified or generated) is included in the response.",
                    required = false
            )
            @QueryParam(CLIENT_ID) @DefaultValue(StringUtils.EMPTY) ClientIdParameter clientId,
            @ApiParam(
                    value = "The connection id.",
                    required = true
            )
            @PathParam("connection-id") String connectionId,
            @ApiParam(
                    value = "The drop request id.",
                    required = true
            )
            @PathParam("drop-request-id") String dropRequestId) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.GET, getAbsolutePath(), getRequestParameters(true), getHeaders()).getResponse();
        }

        // get the drop request
        final DropRequestDTO dropRequest = serviceFacade.getFlowFileDropRequest(groupId, connectionId, dropRequestId);
        dropRequest.setUri(generateResourceUri("controller", "process-groups", groupId, "connections", connectionId, "drop-requests", dropRequestId));

        // create the revision
        final RevisionDTO revision = new RevisionDTO();
        revision.setClientId(clientId.getClientId());

        // create the response entity
        final DropRequestEntity entity = new DropRequestEntity();
        entity.setRevision(revision);
        entity.setDropRequest(dropRequest);

        return generateOkResponse(entity).build();
    }

    /**
     * Deletes the specified drop request.
     *
     * @param httpServletRequest request
     * @param clientId Optional client id. If the client id is not specified, a new one will be generated. This value (whether specified or generated) is included in the response.
     * @param connectionId The connection id
     * @param dropRequestId The drop request id
     * @return A dropRequestEntity
     */
    @DELETE
    @Consumes(MediaType.WILDCARD)
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Path("/{connection-id}/drop-requests/{drop-request-id}")
    @PreAuthorize("hasRole('ROLE_DFM')")
    @ApiOperation(
            value = "Cancels and/or removes a request to drop the contents of this connection.",
            response = DropRequestEntity.class,
            authorizations = {
                @Authorization(value = "Data Flow Manager", type = "ROLE_DFM")
            }
    )
    @ApiResponses(
            value = {
                @ApiResponse(code = 400, message = "NiFi was unable to complete the request because it was invalid. The request should not be retried without modification."),
                @ApiResponse(code = 401, message = "Client could not be authenticated."),
                @ApiResponse(code = 403, message = "Client is not authorized to make this request."),
                @ApiResponse(code = 404, message = "The specified resource could not be found."),
                @ApiResponse(code = 409, message = "The request was valid but NiFi was not in the appropriate state to process it. Retrying the same request later may be successful.")
            }
    )
    public Response removeDropRequest(
            @Context HttpServletRequest httpServletRequest,
            @ApiParam(
                    value = "If the client id is not specified, new one will be generated. This value (whether specified or generated) is included in the response.",
                    required = false
            )
            @QueryParam(CLIENT_ID) @DefaultValue(StringUtils.EMPTY) ClientIdParameter clientId,
            @ApiParam(
                    value = "The connection id.",
                    required = true
            )
            @PathParam("connection-id") String connectionId,
            @ApiParam(
                    value = "The drop request id.",
                    required = true
            )
            @PathParam("drop-request-id") String dropRequestId) {

        // replicate if cluster manager
        if (properties.isClusterManager()) {
            return clusterManager.applyRequest(HttpMethod.DELETE, getAbsolutePath(), getRequestParameters(true), getHeaders()).getResponse();
        }

        // handle expects request (usually from the cluster manager)
        final String expects = httpServletRequest.getHeader(WebClusterManager.NCM_EXPECTS_HTTP_HEADER);
        if (expects != null) {
            return generateContinueResponse().build();
        }

        // delete the drop request
        final DropRequestDTO dropRequest = serviceFacade.deleteFlowFileDropRequest(groupId, connectionId, dropRequestId);
        dropRequest.setUri(generateResourceUri("controller", "process-groups", groupId, "connections", connectionId, "drop-requests", dropRequestId));

        // create the revision
        final RevisionDTO revision = new RevisionDTO();
        revision.setClientId(clientId.getClientId());

        // create the response entity
        final DropRequestEntity entity = new DropRequestEntity();
        entity.setRevision(revision);
        entity.setDropRequest(dropRequest);

        return generateOkResponse(entity).build();
    }

    // setters
    public void setServiceFacade(NiFiServiceFacade serviceFacade) {
        this.serviceFacade = serviceFacade;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public void setClusterManager(WebClusterManager clusterManager) {
        this.clusterManager = clusterManager;
    }

    public void setProperties(NiFiProperties properties) {
        this.properties = properties;
    }
}
