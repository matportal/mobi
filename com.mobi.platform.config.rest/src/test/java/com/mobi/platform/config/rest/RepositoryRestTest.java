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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobi.repository.api.OsgiRepository;
import com.mobi.repository.api.RepositoryManager;
import com.mobi.rest.test.util.MobiRestTestCXF;
import com.mobi.rest.test.util.UsernameTestFilter;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

import java.io.IOException;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;
import java.util.Optional;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

public class RepositoryRestTest extends MobiRestTestCXF {
    private AutoCloseable closeable;
    private static RepositoryRest rest;
    private static RepositoryManager repositoryManager;
    private static ConfigurationAdmin configurationAdmin;
    private static Configuration configuration;

    private static OsgiRepository repository;
    private static final String REPO_ID = "test-repo";
    private static final String REPO_TITLE = "Test Repo";
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeClass
    public static void startServer() {
        repositoryManager = Mockito.mock(RepositoryManager.class);
        configurationAdmin = Mockito.mock(ConfigurationAdmin.class);
        configuration = Mockito.mock(Configuration.class);
        repository = Mockito.mock(OsgiRepository.class);

        rest = new RepositoryRest();
        rest.repositoryManager = repositoryManager;
        rest.configurationAdmin = configurationAdmin;

        configureServer(rest, new UsernameTestFilter());
    }

    @Before
    public void setupMocks() throws Exception {
        closeable = MockitoAnnotations.openMocks(this);

        when(repository.getRepositoryID()).thenReturn(REPO_ID);
        when(repository.getRepositoryTitle()).thenReturn("Test Repo");
        when(repository.getRepositoryType()).thenReturn("native");

        when(repositoryManager.getAllRepositories()).thenReturn(Map.of(REPO_ID, repository));
        when(repositoryManager.getRepository(anyString())).thenReturn(Optional.empty());
        when(repositoryManager.getRepository(REPO_ID)).thenReturn(Optional.of(repository));
        when(configurationAdmin.createFactoryConfiguration(anyString(), anyString())).thenReturn(configuration);
        when(configurationAdmin.listConfigurations(anyString())).thenReturn(null);
    }

    @After
    public void resetMocks() throws Exception {
        closeable.close();
        reset(repositoryManager);
        reset(configurationAdmin);
        reset(configuration);
        reset(repository);
    }

    @Test
    public void getRepositoriesTest() {
        Response response = target().path("repositories").request().get();
        assertEquals(response.getStatus(), 200);
        verify(repositoryManager).getAllRepositories();
        try {
            String str = response.readEntity(String.class);
            JsonNode json = mapper.readTree(str);
            assertEquals(1, json.size());
            JsonNode repoObj = json.get(0);
            assertEquals(REPO_ID, repoObj.get("id").asText());
            assertEquals(REPO_TITLE, repoObj.get("title").asText());
            assertEquals("native", repoObj.get("type").asText());
        } catch (Exception e) {
            fail("Expected no exception, but got: " + e.getMessage());
        }
    }

    @Test
    public void getRepositorySuccessTest() {
        Response response = target().path("repositories/" + REPO_ID).request().get();
        assertEquals(response.getStatus(), 200);
        verify(repositoryManager).getRepository(REPO_ID);
        try {
            String str = response.readEntity(String.class);
            JsonNode json = mapper.readTree(str);
            assertEquals(REPO_ID, json.get("id").asText());
            assertEquals(REPO_TITLE, json.get("title").asText());
            assertEquals("native", json.get("type").asText());
        } catch (Exception e) {
            fail("Expected no exception, but got: " + e.getMessage());
        }
    }

    @Test
    public void getRepositoryFailureTest() {
        Response response = target().path("repositories/ERROR").request().get();
        assertEquals(response.getStatus(), 400);
    }

    @Test
    public void createSparqlRepositoryTest() throws Exception {
        String payload = "{"
                + "\"id\":\"datasets-api\","
                + "\"title\":\"Datasets API Repo\","
                + "\"type\":\"sparql\","
                + "\"endpointUrl\":\"http://example.org/sparql\","
                + "\"updateEndpointUrl\":\"http://example.org/sparql\","
                + "\"quadMode\":true,"
                + "\"writable\":true"
                + "}";

        Response response = target().path("repositories")
                .request()
                .post(Entity.entity(payload, MediaType.APPLICATION_JSON_TYPE));
        assertEquals(201, response.getStatus());
        verify(configurationAdmin).createFactoryConfiguration("com.mobi.service.repository.sparql", "?");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Dictionary<String, Object>> propsCaptor = ArgumentCaptor.forClass(Dictionary.class);
        verify(configuration).update(propsCaptor.capture());
        Dictionary<String, Object> props = propsCaptor.getValue();
        assertEquals("datasets-api", props.get("id"));
        assertEquals("Datasets API Repo", props.get("title"));
        assertEquals("http://example.org/sparql", props.get("endpointUrl"));
        assertEquals("http://example.org/sparql", props.get("updateEndpointUrl"));
        assertEquals(true, props.get("quadMode"));
        assertEquals(true, props.get("writable"));
    }

    @Test
    public void createSparqlRepositoryWithBlankUpdateEndpointTest() throws Exception {
        String payload = "{"
                + "\"id\":\"datasets-api-readonly\","
                + "\"title\":\"Datasets API ReadOnly\","
                + "\"type\":\"sparql\","
                + "\"endpointUrl\":\"http://example.org/sparql\","
                + "\"updateEndpointUrl\":\"   \","
                + "\"quadMode\":true,"
                + "\"writable\":false"
                + "}";

        Response response = target().path("repositories")
                .request()
                .post(Entity.entity(payload, MediaType.APPLICATION_JSON_TYPE));
        assertEquals(201, response.getStatus());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Dictionary<String, Object>> propsCaptor = ArgumentCaptor.forClass(Dictionary.class);
        verify(configuration).update(propsCaptor.capture());
        Dictionary<String, Object> props = propsCaptor.getValue();
        assertEquals("datasets-api-readonly", props.get("id"));
        assertEquals("Datasets API ReadOnly", props.get("title"));
        assertEquals("http://example.org/sparql", props.get("endpointUrl"));
        assertNull(props.get("updateEndpointUrl"));
        assertEquals(true, props.get("quadMode"));
        assertEquals(false, props.get("writable"));
    }

    @Test
    public void createSparqlRepositoryMissingEndpointTest() {
        String payload = "{"
                + "\"id\":\"datasets-api\","
                + "\"title\":\"Datasets API Repo\","
                + "\"type\":\"sparql\""
                + "}";

        Response response = target().path("repositories")
                .request()
                .post(Entity.entity(payload, MediaType.APPLICATION_JSON_TYPE));
        assertEquals(400, response.getStatus());
    }

    @Test
    public void deleteRepositorySuccessTest() throws Exception {
        Configuration deleteConfig = Mockito.mock(Configuration.class);
        Dictionary<String, Object> props = new Hashtable<>();
        props.put("id", "datasets-api");
        when(deleteConfig.getProperties()).thenReturn(props);
        when(deleteConfig.getFactoryPid()).thenReturn("com.mobi.service.repository.sparql");
        when(configurationAdmin.listConfigurations(anyString())).thenReturn(new Configuration[] { deleteConfig });

        Response response = target().path("repositories/datasets-api").request().delete();
        assertEquals(204, response.getStatus());
        verify(deleteConfig).delete();
    }

    @Test
    public void deleteProtectedRepositoryFailureTest() {
        Response response = target().path("repositories/system").request().delete();
        assertEquals(400, response.getStatus());
    }
}
