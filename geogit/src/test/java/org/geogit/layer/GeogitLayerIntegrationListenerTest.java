package org.geogit.layer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import org.geogit.api.ObjectId;
import org.geogit.geoserver.GeoGitTestData;
import org.geogit.geoserver.GeoGitTestData.CatalogBuilder;
import org.geogit.geotools.data.GeoGitDataStoreFactory;
import org.geoserver.catalog.AuthorityURLInfo;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.catalog.LayerIdentifierInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.StoreInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.data.test.SystemTestData;
import org.geoserver.test.GeoServerSystemTestSupport;
import org.geoserver.test.TestSetup;
import org.geoserver.test.TestSetupFrequency;
import org.geoserver.wms.WMSInfo;
import org.junit.Rule;
import org.junit.Test;

@TestSetup(run = TestSetupFrequency.REPEAT)
public class GeogitLayerIntegrationListenerTest extends GeoServerSystemTestSupport {

    @Rule
    public GeoGitTestData geogitData = new GeoGitTestData();

    @Override
    protected void onSetUp(SystemTestData testData) throws Exception {
        geogitData.init()//
                .config("user.name", "gabriel")//
                .config("user.email", "gabriel@test.com")//
                .createTypeTree("lines", "geom:LineString:srid=4326")//
                .createTypeTree("points", "geom:Point:srid=4326")//
                .add()//
                .commit("created type trees")//
                .get();

        geogitData.insert("points",//
                "p1=geom:POINT(0 0)",//
                "p2=geom:POINT(1 1)",//
                "p3=geom:POINT(2 2)");

        geogitData.insert("lines",//
                "l1=geom:LINESTRING(-10 0, 10 0)",//
                "l2=geom:LINESTRING(0 0, 180 0)");

        geogitData.add().commit("Added test features");

    }

    @Test
    public void testAddGeogitLayerForcesCreationOfRootAuthURL() {
        addAvailableGeogitLayers();

        WMSInfo service = getGeoServer().getService(WMSInfo.class);
        List<AuthorityURLInfo> authorityURLs = service.getAuthorityURLs();
        AuthorityURLInfo expected = null;
        for (AuthorityURLInfo auth : authorityURLs) {
            if (GeogitLayerIntegrationListener.AUTHORITY_URL_NAME.equals(auth.getName())) {
                expected = auth;
                break;
            }
        }
        assertNotNull("No geogit auth url found: " + authorityURLs, expected);
    }

    @Test
    public void testAddGeogitLayerAddsLayerIdentifier() {
        addAvailableGeogitLayers();

        Catalog catalog = getCatalog();
        CatalogBuilder catalogBuilder = geogitData.newCatalogBuilder(catalog);
        String layerName = catalogBuilder.workspaceName() + ":points";
        LayerInfo pointLayerInfo = catalog.getLayerByName(layerName);
        assertIdentifier(pointLayerInfo);

        layerName = catalogBuilder.workspaceName() + ":lines";
        LayerInfo lineLayerInfo = catalog.getLayerByName(layerName);
        assertIdentifier(lineLayerInfo);
    }

    @Test
    public void testAddGeogitLayerAddsLayerIdentifierWithExplicitBranch() {
        addAvailableGeogitLayers();

        Catalog catalog = getCatalog();
        CatalogBuilder catalogBuilder = geogitData.newCatalogBuilder(catalog);
        DataStoreInfo store = catalog.getDataStoreByName(catalogBuilder.storeName());
        store.getConnectionParameters().put(GeoGitDataStoreFactory.BRANCH.key, "master");
        catalog.save(store);

        String layerName = catalogBuilder.workspaceName() + ":points";
        LayerInfo pointLayerInfo = catalog.getLayerByName(layerName);
        assertIdentifier(pointLayerInfo);

        layerName = catalogBuilder.workspaceName() + ":lines";
        LayerInfo lineLayerInfo = catalog.getLayerByName(layerName);
        assertIdentifier(lineLayerInfo);
    }

    @Test
    public void testAddGeogitLayerAddsLayerIdentifierWithExplicitHead() {
        addAvailableGeogitLayers();

        Catalog catalog = getCatalog();
        CatalogBuilder catalogBuilder = geogitData.newCatalogBuilder(catalog);
        DataStoreInfo store = catalog.getDataStoreByName(catalogBuilder.storeName());

        final String fakeHead = ObjectId.forString("something").toString();

        store.getConnectionParameters().put(GeoGitDataStoreFactory.HEAD.key, fakeHead);
        catalog.save(store);

        String layerName = catalogBuilder.workspaceName() + ":points";
        LayerInfo pointLayerInfo = catalog.getLayerByName(layerName);
        assertIdentifier(pointLayerInfo);

        layerName = catalogBuilder.workspaceName() + ":lines";
        LayerInfo lineLayerInfo = catalog.getLayerByName(layerName);
        assertIdentifier(lineLayerInfo);
    }

    @Test
    public void testRenameStore() {
        addAvailableGeogitLayers();

        Catalog catalog = getCatalog();
        CatalogBuilder catalogBuilder = geogitData.newCatalogBuilder(catalog);
        String storeName = catalogBuilder.storeName();
        DataStoreInfo store = catalog.getStoreByName(storeName, DataStoreInfo.class);
        store.setName("new_store_name");
        catalog.save(store);

        String layerName = catalogBuilder.workspaceName() + ":points";
        LayerInfo pointLayerInfo = catalog.getLayerByName(layerName);
        assertIdentifier(pointLayerInfo);

        layerName = catalogBuilder.workspaceName() + ":lines";
        LayerInfo lineLayerInfo = catalog.getLayerByName(layerName);
        assertIdentifier(lineLayerInfo);
    }

    @Test
    public void testRenameWorkspace() {
        addAvailableGeogitLayers();

        Catalog catalog = getCatalog();
        CatalogBuilder catalogBuilder = geogitData.newCatalogBuilder(catalog);
        String wsName = catalogBuilder.workspaceName();
        WorkspaceInfo ws = catalog.getWorkspaceByName(wsName);
        String newWsName = "new_ws_name";
        ws.setName(newWsName);
        catalog.save(ws);

        String layerName = newWsName + ":points";
        LayerInfo pointLayerInfo = catalog.getLayerByName(layerName);
        assertIdentifier(pointLayerInfo);

        layerName = newWsName + ":lines";
        LayerInfo lineLayerInfo = catalog.getLayerByName(layerName);
        assertIdentifier(lineLayerInfo);
    }

    private void assertIdentifier(LayerInfo layer) {
        assertNotNull(layer);
        List<LayerIdentifierInfo> identifiers = layer.getIdentifiers();
        LayerIdentifierInfo expected = null;
        for (LayerIdentifierInfo idinfo : identifiers) {
            if (GeogitLayerIntegrationListener.AUTHORITY_URL_NAME.equals(idinfo.getAuthority())) {
                expected = idinfo;
            }
        }

        assertNotNull("No geogit identifier added for layer " + layer, expected);

        ResourceInfo resource = layer.getResource();
        StoreInfo store = resource.getStore();
        WorkspaceInfo workspace = store.getWorkspace();

        String expectedId = workspace.getName() + ":" + store.getName() + ":"
                + resource.getNativeName();
        Map<String, Serializable> params = store.getConnectionParameters();
        if (params.containsKey(GeoGitDataStoreFactory.BRANCH.key)) {
            String branch = (String) params.get(GeoGitDataStoreFactory.BRANCH.key);
            expectedId += ":" + branch;
        } else if (params.containsKey(GeoGitDataStoreFactory.HEAD.key)) {
            String head = (String) params.get(GeoGitDataStoreFactory.HEAD.key);
            expectedId += ":" + head;
        }

        assertEquals(expectedId, expected.getIdentifier());
    }

    private void addAvailableGeogitLayers() {
        Catalog catalog = getCatalog();
        CatalogBuilder catalogBuilder = geogitData.newCatalogBuilder(catalog);
        catalogBuilder.addAllRepoLayers().build();
    }

}
