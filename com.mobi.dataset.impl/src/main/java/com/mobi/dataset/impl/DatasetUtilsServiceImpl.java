package com.mobi.dataset.impl;

/*-
 * #%L
 * com.mobi.dataset.impl
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

import com.mobi.dataset.api.DatasetConnection;
import com.mobi.dataset.api.DatasetUtilsService;
import com.mobi.dataset.ontology.dataset.Dataset;
import com.mobi.dataset.ontology.dataset.DatasetFactory;
import com.mobi.persistence.utils.ConnectionUtils;
import com.mobi.repository.api.OsgiRepository;
import com.mobi.repository.api.RepositoryManager;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.ValidatingValueFactory;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.Update;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

@Component(immediate = true)
public class DatasetUtilsServiceImpl implements DatasetUtilsService {

    private static final String SYSTEM_DEFAULT_NG_SUFFIX = "_system_dng";

    final ValueFactory vf = new ValidatingValueFactory();

    @Reference
    RepositoryManager repoManager;

    @Reference
    DatasetFactory dsFactory;


    @Override
    public boolean createDataset(Resource dataset, String repositoryId) {
        return createDataset(dataset, repositoryId, iri -> true);
    }

    @Override
    public boolean createDataset(Resource dataset, String repositoryId, Predicate<Resource> steps) {
        OsgiRepository dsRepo = repoManager.getRepository(repositoryId).orElseThrow(() ->
                new IllegalArgumentException("Dataset target repository does not exist."));

        IRI sdgIRI = vf.createIRI(dataset + SYSTEM_DEFAULT_NG_SUFFIX);

        try (RepositoryConnection conn = dsRepo.getConnection()) {
            if (ConnectionUtils.contains(conn, null, null, null, dataset)) {
                throw new IllegalArgumentException("Dataset already exists in the specified repository.");
            }

            Dataset newDataset = dsFactory.createNew(dataset);
            newDataset.setSystemDefaultNamedGraph(sdgIRI);

            if (steps.test(dataset)) {
                conn.add(newDataset.getModel(), dataset);
            } else {
                return false;
            }
        }

        return true;
    }

    @Override
    public void deleteDataset(Resource dataset, String repositoryId) {
        OsgiRepository dsRepo = getDatasetRepo(repositoryId);
        try (RepositoryConnection conn = dsRepo.getConnection()) {
            deleteGraphs(conn, dataset);
            removeDatasetMetadata(conn, dataset);
        }
    }

    @Override
    public void safeDeleteDataset(Resource dataset, String repositoryId) {
        OsgiRepository dsRepo = getDatasetRepo(repositoryId);
        try (RepositoryConnection conn = dsRepo.getConnection()) {
            safeDeleteGraphs(conn, dataset);
            removeDatasetMetadata(conn, dataset);
        }
    }

    @Override
    public void clearDataset(Resource dataset, String repositoryId) {
        OsgiRepository dsRepo = getDatasetRepo(repositoryId);

        try (RepositoryConnection conn = dsRepo.getConnection()) {
            deleteGraphs(conn, dataset);
            deleteGraphLinks(conn, dataset);
        }
    }

    @Override
    public void safeClearDataset(Resource dataset, String repositoryId) {
        OsgiRepository dsRepo = getDatasetRepo(repositoryId);

        try (RepositoryConnection conn = dsRepo.getConnection()) {
            safeDeleteGraphs(conn, dataset);
            deleteGraphLinks(conn, dataset);
        }
    }

    @Override
    public DatasetConnection getConnection(Resource dataset, String repositoryId) {
        OsgiRepository dsRepo = getDatasetRepo(repositoryId);
        return new SimpleDatasetRepositoryConnection(dsRepo.getConnection(), dataset, repositoryId, vf);
    }

    private OsgiRepository getDatasetRepo(String repositoryId) {
        return repoManager.getRepository(repositoryId).orElseThrow(() ->
                new IllegalArgumentException("Dataset target repository does not exist."));
    }

    private void deleteGraphs(RepositoryConnection conn, Resource dataset) {
        IRI ngPred = vf.createIRI(Dataset.namedGraph_IRI);
        IRI dngPred = vf.createIRI(Dataset.defaultNamedGraph_IRI);
        IRI sdngPred = vf.createIRI(Dataset.systemDefaultNamedGraph_IRI);
        getLinkedGraphs(conn, dataset, ngPred).forEach(graph -> clearGraph(conn, graph));
        getLinkedGraphs(conn, dataset, dngPred).forEach(graph -> clearGraph(conn, graph));
        getLinkedGraphs(conn, dataset, sdngPred).forEach(graph -> clearGraph(conn, graph));
    }

    private void clearGraph(RepositoryConnection conn, Value graph) {
        if (graph instanceof IRI) {
            clearIriGraph(conn, (IRI) graph);
        } else if (graph instanceof BNode) {
            conn.clear(vf.createBNode(graph.stringValue()));
        }
    }

    private void safeDeleteGraphs(RepositoryConnection conn, Resource dataset) {
        IRI ngPred = vf.createIRI(Dataset.namedGraph_IRI);
        IRI dngPred = vf.createIRI(Dataset.defaultNamedGraph_IRI);
        IRI sdngPred = vf.createIRI(Dataset.systemDefaultNamedGraph_IRI);
        getLinkedGraphs(conn, dataset, ngPred).forEach(graph -> {
            if (safeToDelete(conn, dataset, graph)) {
                clearGraph(conn, graph);
            }
        });
        getLinkedGraphs(conn, dataset, dngPred).forEach(graph -> {
            if (safeToDelete(conn, dataset, graph)) {
                clearGraph(conn, graph);
            }
        });
        getLinkedGraphs(conn, dataset, sdngPred).forEach(graph -> {
            if (safeToDelete(conn, dataset, graph)) {
                clearGraph(conn, graph);
            }
        });
    }

    private boolean safeToDelete(RepositoryConnection conn, Resource dataset, Value graph) {
        IRI ngPred = vf.createIRI(Dataset.namedGraph_IRI);
        IRI dngPred = vf.createIRI(Dataset.defaultNamedGraph_IRI);

        return !hasOtherDatasetReference(conn, dataset, ngPred, graph)
                && !hasOtherDatasetReference(conn, dataset, dngPred, graph);
    }

    private void deleteGraphLinks(RepositoryConnection conn, Resource dataset) {
        IRI ngPred = vf.createIRI(Dataset.namedGraph_IRI);
        IRI dngPred = vf.createIRI(Dataset.defaultNamedGraph_IRI);
        removeDatasetGraphLinks(conn, dataset, ngPred);
        removeDatasetGraphLinks(conn, dataset, dngPred);
    }

    private void removeDatasetMetadata(RepositoryConnection conn, Resource dataset) {
        clearGraph(conn, dataset);
        conn.remove(dataset, null, null);
    }

    private boolean hasOtherDatasetReference(RepositoryConnection conn, Resource dataset, IRI predicate, Value graph) {
        String queryString = "SELECT ?dataset WHERE {"
                + " GRAPH ?context { ?dataset <" + predicate.stringValue() + "> ?graph . }"
                + " FILTER(?dataset != ?currentDataset)"
                + "} LIMIT 1";
        TupleQuery query = conn.prepareTupleQuery(queryString);
        query.setBinding("currentDataset", dataset);
        query.setBinding("graph", graph);
        try (TupleQueryResult result = query.evaluate()) {
            return result.hasNext();
        }
    }

    private List<Value> getLinkedGraphs(RepositoryConnection conn, Resource dataset, IRI predicate) {
        List<Value> graphs = new ArrayList<>();
        String queryString = "SELECT ?graph WHERE {"
                + " GRAPH ?dataset { ?dataset <" + predicate.stringValue() + "> ?graph . }"
                + "}";
        TupleQuery query = conn.prepareTupleQuery(queryString);
        query.setBinding("dataset", dataset);
        try (TupleQueryResult result = query.evaluate()) {
            result.forEach(bindings -> graphs.add(bindings.getValue("graph")));
        }
        return graphs;
    }

    private void removeDatasetGraphLinks(RepositoryConnection conn, Resource dataset, IRI predicate) {
        String updateString = "DELETE { GRAPH ?dataset { ?dataset <" + predicate.stringValue() + "> ?graph . } }"
                + " WHERE { GRAPH ?dataset { ?dataset <" + predicate.stringValue() + "> ?graph . } }";
        Update update = conn.prepareUpdate(updateString);
        update.setBinding("dataset", dataset);
        update.execute();
    }

    private void clearIriGraph(RepositoryConnection conn, IRI graph) {
        Update update = conn.prepareUpdate("DELETE { GRAPH ?graph { ?s ?p ?o } }"
                + " WHERE { GRAPH ?graph { ?s ?p ?o } }");
        update.setBinding("graph", graph);
        update.execute();
    }
}
