package com.mobi.platform.config.rest;

/*-
 * #%L
 * com.mobi.platform.config.rest
 * $Id:$
 * $HeadURL:$
 * %%
 * Copyright (C) 2016 - 2026 iNovex Information Systems, Inc.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mobi.repository.api.OsgiRepository;
import com.mobi.repository.api.RepositoryManager;
import com.mobi.rest.util.ErrorUtils;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JaxrsResource;

import java.io.IOException;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Component(service = RepositoryRest.class, immediate = true)
@JaxrsResource
@Path("/repositories")
public class RepositoryRest {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String MEMORY_FACTORY_PID = "com.mobi.service.repository.memory";
    private static final String NATIVE_FACTORY_PID = "com.mobi.service.repository.native";
    private static final String SPARQL_FACTORY_PID = "com.mobi.service.repository.sparql";
    private static final String HTTP_FACTORY_PID = "com.mobi.service.repository.http";
    private static final Set<String> PROTECTED_REPOSITORY_IDS = Set.of("system", "prov", "ontologyCache");
    private static final Set<String> MANAGED_FACTORY_PIDS = Set.of(
            MEMORY_FACTORY_PID,
            NATIVE_FACTORY_PID,
            SPARQL_FACTORY_PID,
            HTTP_FACTORY_PID
    );

    @Reference
    protected RepositoryManager repositoryManager;
    @Reference
    protected ConfigurationAdmin configurationAdmin;

    /**
     * Retrieves a JSON array of all the repositories configured in this Mobi installation. Each repository is
     * represented with its id, title, and type as a simple string.
     *
     * @return a Response with an JSON array of objects representing individual repositories
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("user")
    @Operation(
            tags = "repositories",
            summary = "Retrieves all the configured repositories",
            responses = {
                    @ApiResponse(responseCode = "200",
                            description = "Response indicating the success or failure of the request"),
                    @ApiResponse(responseCode = "403", description = "Permission Denied"),
                    @ApiResponse(responseCode = "500", description = "INTERNAL SERVER ERROR"),
            }
    )
    public Response getRepositories() {
        Map<String, OsgiRepository> repos = repositoryManager.getAllRepositories();
        ArrayNode array = repos.values().stream()
                .sorted((repo1, repo2) -> repo1.getRepositoryID().compareToIgnoreCase(repo2.getRepositoryID()))
                .map(this::createRepoJson)
                .collect(mapper::createArrayNode, ArrayNode::add, ArrayNode::add);
        return Response.ok(array.toString()).build();
    }

    /**
     * Retrieve a JSON object representing the repository matching the given id.
     *
     * @return a Response with an JSON object of an individual repository
     */
    @GET
    @Path("{repoId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("user")
    @Operation(
            tags = "repositories",
            summary = "Retrieves a repository based on its id",
            responses = {
                    @ApiResponse(responseCode = "200",
                            description = "Response indicating the success or failure of the request"),
                    @ApiResponse(responseCode = "403", description = "Permission Denied"),
                    @ApiResponse(responseCode = "500", description = "INTERNAL SERVER ERROR"),
            }
    )
    public Response getRepository(@PathParam("repoId") String repoId) {
        OsgiRepository repo = repositoryManager.getRepository(repoId).orElseThrow(() ->
                ErrorUtils.sendError("No repository found for that id", Response.Status.BAD_REQUEST));
        ObjectNode obj = createRepoJson(repo);
        return Response.ok(obj.toString()).build();
    }

    /**
     * Creates a new repository configuration in OSGi ConfigAdmin. The repository will be dynamically registered
     * by the matching repository wrapper component.
     *
     * @param configJson JSON object containing repository configuration fields
     * @return a Response with the created repository metadata
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("admin")
    @Operation(
            tags = "repositories",
            summary = "Creates a new repository configuration",
            responses = {
                    @ApiResponse(responseCode = "201",
                            description = "Repository configuration created",
                            content = @Content(schema = @Schema(type = "object"))),
                    @ApiResponse(responseCode = "400", description = "BAD REQUEST"),
                    @ApiResponse(responseCode = "403", description = "Permission Denied"),
                    @ApiResponse(responseCode = "500", description = "INTERNAL SERVER ERROR"),
            }
    )
    public Response createRepository(
            @Parameter(description = "Repository configuration JSON", required = true) String configJson) {
        try {
            ObjectNode payload = mapper.readValue(configJson, ObjectNode.class);
            String id = getRequiredString(payload, "id");
            String title = getRequiredString(payload, "title");
            String type = getRequiredString(payload, "type");

            if (PROTECTED_REPOSITORY_IDS.contains(id)) {
                throw ErrorUtils.sendError("That repository id is reserved by the system", Response.Status.BAD_REQUEST);
            }
            if (repositoryManager.getRepository(id).isPresent()) {
                throw ErrorUtils.sendError("A repository with that id already exists", Response.Status.BAD_REQUEST);
            }
            if (getManagedRepositoryConfiguration(id).isPresent()) {
                throw ErrorUtils.sendError("A repository configuration with that id already exists",
                        Response.Status.BAD_REQUEST);
            }

            String factoryPid = getFactoryPid(type);
            Configuration configuration = configurationAdmin.createFactoryConfiguration(factoryPid, "?");
            Dictionary<String, Object> properties = buildRepositoryProperties(payload, id, title, type);
            configuration.update(properties);

            ObjectNode response = mapper.createObjectNode();
            response.put("id", id);
            response.put("title", title);
            response.put("type", type);
            return Response.status(201).entity(response.toString()).build();
        } catch (IllegalArgumentException ex) {
            throw ErrorUtils.sendError(ex, ex.getMessage(), Response.Status.BAD_REQUEST);
        } catch (IOException ex) {
            throw ErrorUtils.sendError(ex, "Invalid repository configuration payload", Response.Status.BAD_REQUEST);
        } catch (Exception ex) {
            throw ErrorUtils.sendError(ex, ex.getMessage(), Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Deletes a repository configuration in OSGi ConfigAdmin. Protected system repositories cannot be deleted.
     *
     * @param repoId repository id to delete
     * @return a Response indicating whether deletion succeeded
     */
    @DELETE
    @Path("{repoId}")
    @RolesAllowed("admin")
    @Operation(
            tags = "repositories",
            summary = "Deletes a repository configuration",
            responses = {
                    @ApiResponse(responseCode = "204", description = "Repository configuration deleted"),
                    @ApiResponse(responseCode = "400", description = "BAD REQUEST"),
                    @ApiResponse(responseCode = "403", description = "Permission Denied"),
                    @ApiResponse(responseCode = "500", description = "INTERNAL SERVER ERROR"),
            }
    )
    public Response deleteRepository(@PathParam("repoId") String repoId) {
        if (PROTECTED_REPOSITORY_IDS.contains(repoId)) {
            throw ErrorUtils.sendError("Cannot delete protected system repository", Response.Status.BAD_REQUEST);
        }

        try {
            Configuration configuration = getManagedRepositoryConfiguration(repoId).orElseThrow(() ->
                    ErrorUtils.sendError("No repository configuration found for that id", Response.Status.BAD_REQUEST));
            configuration.delete();
            return Response.noContent().build();
        } catch (IOException ex) {
            throw ErrorUtils.sendError(ex, ex.getMessage(), Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    private ObjectNode createRepoJson(OsgiRepository repo) {
        ObjectNode obj = mapper.createObjectNode();
        obj.put("id", repo.getRepositoryID());
        obj.put("title", repo.getRepositoryTitle());
        obj.put("type", repo.getRepositoryType());
        repo.getLimit().ifPresent(limit -> obj.put("limit", limit));
        repo.getTripleCount().ifPresent(tripleCount -> obj.put("tripleCount", tripleCount));
        return obj;
    }

    private String getRequiredString(ObjectNode payload, String key) {
        if (!payload.hasNonNull(key) || payload.get(key).asText().isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return payload.get(key).asText();
    }

    private String getFactoryPid(String type) {
        switch (type) {
            case "memory":
                return MEMORY_FACTORY_PID;
            case "native":
                return NATIVE_FACTORY_PID;
            case "sparql":
                return SPARQL_FACTORY_PID;
            case "http":
                return HTTP_FACTORY_PID;
            default:
                throw new IllegalArgumentException("Unsupported repository type: " + type);
        }
    }

    private Dictionary<String, Object> buildRepositoryProperties(ObjectNode payload, String id, String title, String type) {
        Dictionary<String, Object> props = new Hashtable<>();
        props.put("id", id);
        props.put("title", title);

        switch (type) {
            case "memory":
                putIfPresent(payload, props, "dataDir");
                putIfPresent(payload, props, "tripleIndexes");
                putIfPresent(payload, props, "syncDelay");
                break;
            case "native":
                props.put("dataDir", getRequiredString(payload, "dataDir"));
                putIfPresent(payload, props, "tripleIndexes");
                break;
            case "sparql":
                props.put("endpointUrl", getRequiredString(payload, "endpointUrl"));
                putIfPresent(payload, props, "updateEndpointUrl");
                putIfPresent(payload, props, "quadMode");
                putIfPresent(payload, props, "writable");
                break;
            case "http":
                props.put("serverUrl", getRequiredString(payload, "serverUrl"));
                break;
            default:
                throw new IllegalArgumentException("Unsupported repository type: " + type);
        }

        return props;
    }

    private void putIfPresent(ObjectNode payload, Dictionary<String, Object> props, String key) {
        if (payload.has(key) && !payload.get(key).isNull()) {
            props.put(key, mapper.convertValue(payload.get(key), Object.class));
        }
    }

    private Optional<Configuration> getManagedRepositoryConfiguration(String repoId) throws IOException {
        String filter = String.format(
                "(&(|(service.factoryPid=%s)(service.factoryPid=%s)(service.factoryPid=%s)(service.factoryPid=%s))(id=%s))",
                MEMORY_FACTORY_PID,
                NATIVE_FACTORY_PID,
                SPARQL_FACTORY_PID,
                HTTP_FACTORY_PID,
                escapeFilterValue(repoId)
        );
        Configuration[] configurations;
        try {
            configurations = configurationAdmin.listConfigurations(filter);
        } catch (InvalidSyntaxException ex) {
            throw new IOException("Invalid repository configuration filter", ex);
        }
        if (configurations == null) {
            return Optional.empty();
        }

        List<Configuration> matches = java.util.Arrays.stream(configurations)
                .filter(config -> MANAGED_FACTORY_PIDS.contains(config.getFactoryPid()))
                .filter(config -> {
                    Dictionary<String, Object> properties = config.getProperties();
                    if (properties == null) {
                        return false;
                    }
                    return repoId.equals(properties.get("id"));
                })
                .collect(Collectors.toList());

        if (matches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }

    private String escapeFilterValue(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\':
                    sb.append("\\5c");
                    break;
                case '*':
                    sb.append("\\2a");
                    break;
                case '(':
                    sb.append("\\28");
                    break;
                case ')':
                    sb.append("\\29");
                    break;
                case '\0':
                    sb.append("\\00");
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }
}
