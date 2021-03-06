/* Copyright (c) 2014 OpenPlans. All rights reserved.
 * This code is licensed under the GNU GPL 2.0 license, available at the root
 * application directory.
 */
package org.geogit.gwc;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.geogit.api.Context;
import org.geogit.api.ObjectId;
import org.geogit.api.Ref;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.gwc.layer.GeoServerTileLayer;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.geowebcache.GeoWebCacheException;
import org.geowebcache.filter.parameters.ParameterFilter;
import org.geowebcache.grid.Grid;
import org.geowebcache.grid.GridSubset;
import org.geowebcache.grid.SRS;
import org.geowebcache.layer.TileLayer;
import org.geowebcache.mime.MimeType;
import org.geowebcache.seed.GWCTask;
import org.geowebcache.seed.GWCTask.TYPE;
import org.geowebcache.seed.TileBreeder;
import org.geowebcache.storage.DiscontinuousTileRange;
import org.geowebcache.storage.TileRange;
import org.geowebcache.storage.TileRangeMask;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.io.WKTWriter;
import com.vividsolutions.jts.operation.buffer.BufferOp;
import com.vividsolutions.jts.operation.buffer.BufferParameters;
import com.vividsolutions.jts.simplify.TopologyPreservingSimplifier;

class TruncateHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(TruncateHelper.class);

    public static void issueTruncateTasks(Context geogit, Optional<Ref> oldRef,
            Optional<Ref> newRef, GeoServerTileLayer tileLayer, TileBreeder breeder) {

        final ObjectId oldCommit = oldRef.isPresent() ? oldRef.get().getObjectId() : ObjectId.NULL;
        final ObjectId newCommit = newRef.isPresent() ? newRef.get().getObjectId() : ObjectId.NULL;

        final String tileLayerName = tileLayer.getName();
        final String layerTreeName = tileLayer.getLayerInfo().getResource().getNativeName();

        LOGGER.debug(
                "Computing minimal bounds geometry on layer '{}' (tree '{}') for change {}...{} ",
                tileLayerName, layerTreeName, oldCommit, newCommit);
        final Geometry minimalBounds;
        Stopwatch sw = Stopwatch.createStarted();
        try {
            MinimalDiffBounds geomBuildCommand = geogit.command(MinimalDiffBounds.class)
                    .setOldVersion(oldCommit.toString()).setNewVersion(newCommit.toString());

            geomBuildCommand.setTreeNameFilter(layerTreeName);

            minimalBounds = geomBuildCommand.call();
            sw.stop();
            if (minimalBounds.isEmpty()) {
                LOGGER.debug("Feature tree '{}' not affected by change {}...{} (took {})",
                        layerTreeName, oldCommit, newCommit, sw);
                return;
            }
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Minimal bounds on layer '{}' computed in {}: {}", tileLayerName, sw,
                        formattedWKT(minimalBounds));
            }
        } catch (Exception e) {
            sw.stop();
            LOGGER.error("Error computing minimal bounds for {}...{} on layer '{}' after {}",
                    oldCommit, newCommit, tileLayerName, sw);
            throw Throwables.propagate(e);
        }
        final Set<String> gridSubsets = tileLayer.getGridSubsets();

        LayerInfo layerInfo = tileLayer.getLayerInfo();
        ResourceInfo resource = layerInfo.getResource();
        final CoordinateReferenceSystem nativeCrs = resource.getNativeCRS();

        for (String gridsetId : gridSubsets) {
            GridSubset gridSubset = tileLayer.getGridSubset(gridsetId);
            final CoordinateReferenceSystem gridSetCrs = getGridsetCrs(gridSubset);

            LOGGER.debug("Reprojecting geometry mask to gridset {}", gridsetId);
            Geometry geomInGridsetCrs = transformToGridsetCrs(minimalBounds, nativeCrs, gridSetCrs);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("geometry mask reprojected to gridset {}: {}", gridsetId,
                        formattedWKT(geomInGridsetCrs));
            }
            geomInGridsetCrs = bufferAndSimplifyBySizeOfSmallerTile(geomInGridsetCrs, gridSetCrs,
                    gridSubset);
            try {
                truncate(tileLayer, gridsetId, geomInGridsetCrs, breeder);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static Geometry bufferAndSimplifyBySizeOfSmallerTile(Geometry geomInGridsetCrs,
            CoordinateReferenceSystem gridSetCrs, GridSubset gridSubset) {

        double bufferRatio;
        // try {
        // Unit<?> axisUnit = gridSetCrs.getCoordinateSystem().getAxis(0).getUnit();
        // bufferRatio = toMeters(axisUnit, 1);
        // } catch (RuntimeException e) {
        // }
        //
        Integer zoomStop = gridSubset.getMaxCachedZoom();
        if (zoomStop == null) {
            zoomStop = gridSubset.getGridSet().getNumLevels() - 1;
        }
        // we know some degenerate gridsets have like 30+ zoom levels and nobody could really seed
        // them to that level where the resolution is sub-millimetric. 18 is usually a safe option
        zoomStop = Math.min(18, zoomStop);

        Grid grid = gridSubset.getGridSet().getGrid(zoomStop);
        double width = grid.getResolution() * gridSubset.getTileWidth();
        double height = grid.getResolution() * gridSubset.getTileHeight();

        // buffer by the length of two tiles at the finest zoom level
        bufferRatio = 2 * Math.max(width, height);

        // create a buffer with no rounded joins
        BufferParameters bp = new BufferParameters();
        bp.setEndCapStyle(BufferParameters.CAP_SQUARE);
        bp.setJoinStyle(BufferParameters.JOIN_MITRE);
        BufferOp bufferOp = new BufferOp(geomInGridsetCrs, bp);
        Geometry geometry = bufferOp.getResultGeometry(bufferRatio);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Geometry buffered by the size of a tile at zoom level {} ({} units): {}",
                    zoomStop, bufferRatio, formattedWKT(geometry));
        }
        TopologyPreservingSimplifier simplifier = new TopologyPreservingSimplifier(geometry);
        simplifier.setDistanceTolerance(bufferRatio / 2);
        geometry = simplifier.getResultGeometry();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Simplified geometry: {}", formattedWKT(geometry));
        }
        return geometry;
    }

    /**
     * A one-line WKT may be too large, this function returns a multiline formatted one
     */
    private static Object formattedWKT(Geometry geometry) {
        WKTWriter w = new WKTWriter();
        w.setFormatted(true);
        w.setMaxCoordinatesPerLine(100);
        return w.write(geometry);
    }

    private static void truncate(final GeoServerTileLayer tileLayer, final String gridsetId,
            final Geometry geomInGridsetCrs, final TileBreeder breeder) {

        final List<MimeType> mimeTypes = tileLayer.getMimeTypes();
        final Set<String> cachedStyles = getCachedStyles(tileLayer);

        final String defaultStyle = tileLayer.getStyles();

        GridSubset gridSubset = tileLayer.getGridSubset(gridsetId);

        for (String style : cachedStyles) {
            Map<String, String> parameters;
            if (style.isEmpty() || style.equals(defaultStyle)) {
                parameters = null;
            } else {
                parameters = Collections.singletonMap("STYLES", style);
            }

            for (MimeType mime : mimeTypes) {
                truncate(breeder, tileLayer, gridSubset, mime, parameters, geomInGridsetCrs);
            }
        }
    }

    private static void truncate(TileBreeder breeder, GeoServerTileLayer tileLayer,
            GridSubset gridSubset, MimeType mimeType, Map<String, String> parameters,
            Geometry geomInGridsetCrs) {

        Integer zoomStart = gridSubset.getMinCachedZoom();
        Integer zoomStop = gridSubset.getMaxCachedZoom();
        if (zoomStart == null) {
            zoomStart = 0;
        }
        if (zoomStop == null) {
            zoomStop = gridSubset.getGridSet().getNumLevels() - 1;
        }

        TileRangeMask rasterMask = GeometryTileRangeMask.build(tileLayer, gridSubset,
                geomInGridsetCrs);

        String layerName = tileLayer.getName();
        String gridSetId = gridSubset.getName();

        TileRange tileRange = new DiscontinuousTileRange(layerName, gridSetId, zoomStart, zoomStop,
                rasterMask, mimeType, parameters);

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Truncating layer {}#{}#{} with geom mask {}", layerName, gridSetId,
                    mimeType.getFormat(), formattedWKT(geomInGridsetCrs));
        }
        try {
            GWCTask[] tasks = breeder.createTasks(tileRange, TYPE.TRUNCATE, 1, false);
            breeder.dispatchTasks(tasks);
        } catch (GeoWebCacheException e) {
            throw Throwables.propagate(e);
        }

    }

    private static Geometry transformToGridsetCrs(Geometry minimalBounds,
            CoordinateReferenceSystem defaultCrs, CoordinateReferenceSystem gridSetCrs) {

        Geometry geomInGridsetCrs;
        try {
            MathTransform transform = CRS.findMathTransform(defaultCrs, gridSetCrs);
            geomInGridsetCrs = JTS.transform(minimalBounds, transform);
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }

        return geomInGridsetCrs;
    }

    private static CoordinateReferenceSystem getGridsetCrs(GridSubset gridSubset) {
        final CoordinateReferenceSystem gridSetCrs;

        SRS srs = gridSubset.getGridSet().getSrs();
        try {
            int epsgCode = srs.getNumber();
            String epsgId = "EPSG:" + epsgCode;
            boolean longitudeFirst = true;// as used by geoserver
            gridSetCrs = CRS.decode(epsgId, longitudeFirst);
        } catch (Exception e) {
            throw new RuntimeException("Can't decode SRS  ESPG:" + srs.getNumber());
        }
        return gridSetCrs;
    }

    private static Set<String> getCachedStyles(final TileLayer l) {
        Set<String> cachedStyles = new HashSet<String>();
        String defaultStyle = l.getStyles();
        if (defaultStyle != null) {
            cachedStyles.add(defaultStyle);
        }
        List<ParameterFilter> parameterFilters = l.getParameterFilters();
        if (parameterFilters != null) {
            for (ParameterFilter pf : parameterFilters) {
                if (!"STYLES".equalsIgnoreCase(pf.getKey())) {
                    continue;
                }
                cachedStyles.add(pf.getDefaultValue());
                cachedStyles.addAll(pf.getLegalValues());
                break;
            }
        }
        if (cachedStyles.isEmpty()) {
            cachedStyles.add("");
        }
        return cachedStyles;
    }
}
