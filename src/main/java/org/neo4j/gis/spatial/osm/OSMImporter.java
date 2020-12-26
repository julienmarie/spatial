/*
 * Copyright (c) 2010-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j Spatial.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.gis.spatial.osm;

import org.geotools.referencing.datum.DefaultEllipsoid;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder;
import org.neo4j.gis.spatial.Constants;
import org.neo4j.gis.spatial.SpatialDatabaseService;
import org.neo4j.gis.spatial.index.NodeIndex;
import org.neo4j.gis.spatial.rtree.Envelope;
import org.neo4j.gis.spatial.rtree.Listener;
import org.neo4j.gis.spatial.rtree.NullListener;
import org.neo4j.gis.spatial.utilities.ReferenceNodes;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.traversal.Evaluators;
import org.neo4j.graphdb.traversal.TraversalDescription;
import org.neo4j.io.fs.FileUtils;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.io.layout.Neo4jLayout;
import org.neo4j.kernel.impl.traversal.MonoDirectionalTraversalDescription;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.*;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import static java.util.Arrays.asList;

public class OSMImporter implements Constants {
    public static DefaultEllipsoid WGS84 = DefaultEllipsoid.WGS84;
    public static String INDEX_NAME_CHANGESET = "changeset";
    public static String INDEX_NAME_USER = "user";
    public static String INDEX_NAME_NODE = "node";
    public static String INDEX_NAME_WAY = "node";

    protected boolean nodesProcessingFinished = false;
    private final String layerName;
    private final StatsManager stats = new StatsManager();
    private long osm_dataset = -1;
    private long missingChangesets = 0;
    private final Listener monitor;
    private final com.vividsolutions.jts.geom.Envelope filterEnvelope;

    private Charset charset = Charset.defaultCharset();

    private static class TagStats {
        private final String name;
        private int count = 0;
        private final HashMap<String, Integer> stats = new HashMap<>();

        TagStats(String name) {
            this.name = name;
        }

        int add(String key) {
            count++;
            if (stats.containsKey(key)) {
                int num = stats.get(key);
                stats.put(key, ++num);
                return num;
            } else {
                stats.put(key, 1);
                return 1;
            }
        }

        /**
         * Return only reasonably commonly used tags.
         */
        String[] getTags() {
            if (stats.size() > 0) {
                int threshold = count / (stats.size() * 20);
                ArrayList<String> tags = new ArrayList<>();
                for (String key : stats.keySet()) {
                    if (stats.get(key) > threshold) tags.add(key);
                }
                Collections.sort(tags);
                return tags.toArray(new String[0]);
            } else {
                return new String[0];
            }
        }

        public String toString() {
            return "TagStats[" + name + "]: " + asList(getTags());
        }
    }

    private static class StatsManager {
        private final HashMap<String, TagStats> tagStats = new HashMap<>();
        private final HashMap<Integer, Integer> geomStats = new HashMap<>();

        TagStats getTagStats(String type) {
            if (!tagStats.containsKey(type)) {
                tagStats.put(type, new TagStats(type));
            }
            return tagStats.get(type);
        }

        int addToTagStats(String type, String key) {
            getTagStats("all").add(key);
            return getTagStats(type).add(key);
        }

        int addToTagStats(String type, Collection<String> keys) {
            int count = 0;
            for (String key : keys) {
                count += addToTagStats(type, key);
            }
            return count;
        }

        void printTagStats() {
            System.out.println("Tag statistics for " + tagStats.size()
                    + " types:");
            for (String key : tagStats.keySet()) {
                TagStats stats = tagStats.get(key);
                System.out.println("\t" + key + ": " + stats);
            }
        }

        void addGeomStats(Node geomNode) {
            if (geomNode != null) {
                addGeomStats((Integer) geomNode.getProperty(PROP_TYPE, null));
            }
        }

        void addGeomStats(Integer geom) {
            Integer count = geomStats.get(geom);
            geomStats.put(geom, count == null ? 1 : count + 1);
        }

        void dumpGeomStats() {
            System.out.println("Geometry statistics for " + geomStats.size()
                    + " geometry types:");
            for (Integer key : geomStats.keySet()) {
                Integer count = geomStats.get(key);
                System.out.println("\t"
                        + SpatialDatabaseService.convertGeometryTypeToName(key)
                        + ": " + count);
            }
            geomStats.clear();
        }

    }

    public OSMImporter(String layerName) {
        this(layerName, null);
    }

    public OSMImporter(String layerName, Listener monitor) {
        this(layerName, null, null);
    }

    public OSMImporter(String layerName, Listener monitor, com.vividsolutions.jts.geom.Envelope filterEnvelope) {
        this.layerName = layerName;
        if (monitor == null) monitor = new NullListener();
        this.monitor = monitor;
        this.filterEnvelope = filterEnvelope;
    }

    public long reIndex(GraphDatabaseService database) {
        return reIndex(database, 10000, true);
    }

    public long reIndex(GraphDatabaseService database, int commitInterval) {
        return reIndex(database, commitInterval, true);
    }

    public long reIndex(GraphDatabaseService database, int commitInterval, boolean includePoints) {
        if (commitInterval < 1) {
            throw new IllegalArgumentException("commitInterval must be >= 1");
        }
        log("Re-indexing with GraphDatabaseService: " + database + " (class: " + database.getClass() + ")");

        setLogContext("Index");
        SpatialDatabaseService spatialDatabase = new SpatialDatabaseService();
        OSMLayer layer;
        try (Transaction tx = database.beginTx()) {
            layer = (OSMLayer) spatialDatabase.getOrCreateLayer(tx, layerName, OSMGeometryEncoder.class, OSMLayer.class);
            tx.commit();
        }
        // TODO: The next line creates the relationship between the dataset and
        // layer, but this seems more like a side-effect and should be done
        // explicitly
        OSMDataset dataset = layer.getDataset(database, osm_dataset);
        try (Transaction tx = database.beginTx()) {
            layer.clear(tx); // clear the index without destroying underlying data
            tx.commit();
        }

        TraversalDescription traversal = new MonoDirectionalTraversalDescription();
        long startTime = System.currentTimeMillis();
        org.neo4j.graphdb.traversal.TraversalDescription findWays = traversal.depthFirst()
                .evaluator(Evaluators.excludeStartPosition())
                .relationships(OSMRelation.WAYS, Direction.OUTGOING)
                .relationships(OSMRelation.NEXT, Direction.OUTGOING);
        org.neo4j.graphdb.traversal.TraversalDescription findNodes = traversal.depthFirst()
                .evaluator(Evaluators.excludeStartPosition())
                .relationships(OSMRelation.FIRST_NODE, Direction.OUTGOING)
                .relationships(OSMRelation.NEXT, Direction.OUTGOING);

        Transaction tx = database.beginTx();
        boolean useWays = missingChangesets > 0;
        int count = 0;
        try {
            layer.setExtraPropertyNames(stats.getTagStats("all").getTags(), tx);
            if (useWays) {
                beginProgressMonitor(dataset.getWayCount());
                for (Node way : toList(findWays.traverse(tx.getNodeById(osm_dataset)).nodes())) {
                    updateProgressMonitor(count);
                    incrLogContext();
                    stats.addGeomStats(layer.addWay(tx, way, true));
                    if (includePoints) {
                        long badProxies = 0;
                        long goodProxies = 0;
                        for (Node proxy : findNodes.traverse(way).nodes()) {
                            Relationship nodeRel = proxy.getSingleRelationship(OSMRelation.NODE, Direction.OUTGOING);
                            if (nodeRel == null) {
                                badProxies++;
                            } else {
                                goodProxies++;
                                Node node = proxy.getSingleRelationship(OSMRelation.NODE, Direction.OUTGOING).getEndNode();
                                stats.addGeomStats(layer.addWay(tx, node, true));
                            }
                        }
                        if (badProxies > 0) {
                            System.out.println("Unexpected dangling proxies for way: " + way);
                            if (way.hasProperty("way_osm_id")) {
                                System.out.println("\tWay:   " + way.getProperty("way_osm_id"));
                            }
                            System.out.println("\tBad Proxies:  " + badProxies);
                            System.out.println("\tGood Proxies: " + goodProxies);
                        }
                    }
                    if (++count % commitInterval == 0) {
                        tx.commit();
                        tx.close();
                        tx = database.beginTx();
                    }
                } // TODO ask charset to user?
            } else {
                beginProgressMonitor(dataset.getChangesetCount());
                for (Node changeset : toList(dataset.getAllChangesetNodes())) {
                    updateProgressMonitor(count);
                    incrLogContext();
                    for (Relationship rel : changeset.getRelationships(Direction.INCOMING, OSMRelation.CHANGESET)) {
                        stats.addGeomStats(layer.addWay(tx, rel.getStartNode(), true));
                    }
                    if (++count % commitInterval == 0) {
                        tx.commit();
                        tx.close();
                        tx = database.beginTx();
                    }
                } // TODO ask charset to user?
            }
            tx.commit();
        } finally {
            endProgressMonitor();
            tx.close();
        }

        if (verboseLog) {
            long stopTime = System.currentTimeMillis();
            log("info | Re-indexing elapsed time in seconds: "
                    + (1.0 * (stopTime - startTime) / 1000.0));
            stats.dumpGeomStats();
        }
        return count;
    }

    private List<Node> toList(Iterable<Node> iterable) {
        ArrayList<Node> list = new ArrayList<>();
        if (iterable != null) {
            for (Node e : iterable) {
                list.add(e);
            }
        }
        return list;
    }

    private static class GeometryMetaData {
        private Envelope bbox = null;
        private int vertices = 0;
        private int geometry = -1;

        GeometryMetaData(int type) {
            this.geometry = type;
        }

        public int getGeometryType() {
            return geometry;
        }

        private void expandToInclude(double[] location) {
            if (bbox == null) {
                bbox = new Envelope(location);
            } else {
                bbox.expandToInclude(location);
            }
        }

        void expandToIncludePoint(double[] location) {
            expandToInclude(location);
            vertices++;
            geometry = -1;
        }

        void expandToIncludeBBox(Map<String, Object> nodeProps) {
            double[] sbb = (double[]) nodeProps.get(PROP_BBOX);
            expandToInclude(new double[]{sbb[0], sbb[2]});
            expandToInclude(new double[]{sbb[1], sbb[3]});
            vertices += (Integer) nodeProps.get("vertices");
        }

        void checkSupportedGeometry(Integer memGType) {
            if ((memGType == null || memGType != GTYPE_LINESTRING)
                    && geometry != GTYPE_POLYGON) {
                geometry = -1;
            }
        }

        void setPolygon() {
            geometry = GTYPE_POLYGON;
        }

        boolean isValid() {
            return geometry > 0;
        }

        int getVertices() {
            return vertices;
        }

        private Envelope getBBox() {
            return bbox;
        }
    }

    private static abstract class OSMWriter<T> {
        private static final int UNKNOWN_CHANGESET = -1;
        StatsManager statsManager;
        OSMImporter osmImporter;
        T osm_dataset;
        long missingChangesets = 0;

        private OSMWriter(StatsManager statsManager, OSMImporter osmImporter) {
            this.statsManager = statsManager;
            this.osmImporter = osmImporter;
        }

        static OSMWriter<Node> fromGraphDatabase(
                GraphDatabaseService graphDb, StatsManager stats,
                OSMImporter osmImporter, int txInterval, boolean relaxedTxFlush) {
            return new OSMGraphWriter(graphDb, stats, osmImporter, txInterval, relaxedTxFlush);
        }

        protected abstract T getOrCreateNode(String name, String type, T parent, RelationshipType relType);

        protected abstract T getOrCreateOSMDataset(String name);

        protected abstract void setDatasetProperties(Map<String, Object> extractProperties);

        protected abstract void addNodeTags(T node, LinkedHashMap<String, Object> tags, String type);

        protected abstract void addNodeGeometry(T node, int gtype, Envelope bbox, int vertices);

        protected abstract T addNode(String name, Map<String, Object> properties, String indexKey);

        protected abstract void createRelationship(T from, T to, RelationshipType relType, LinkedHashMap<String, Object> relProps);

        void createRelationship(T from, T to, RelationshipType relType) {
            createRelationship(from, to, relType, null);
        }

        HashMap<String, Integer> stats = new HashMap<>();
        HashMap<String, LogCounter> nodeFindStats = new HashMap<>();
        long logTime = 0;
        long findTime = 0;
        long firstFindTime = 0;
        long lastFindTime = 0;
        long firstLogTime = 0;
        static int foundNodes = 0;
        static int createdNodes = 0;
        int foundOSMNodes = 0;
        int missingUserCount = 0;

        void logMissingUser(Map<String, Object> nodeProps) {
            if (missingUserCount++ < 10) {
                System.err.println("Missing user or uid: " + nodeProps.toString());
            }
        }

        private class LogCounter {
            private long count = 0;
            private long totalTime = 0;
        }

        void logNodeFoundFrom(String key) {
            LogCounter counter = nodeFindStats.computeIfAbsent(key, k -> new LogCounter());
            counter.count++;
            foundOSMNodes++;
            long currentTime = System.currentTimeMillis();
            if (lastFindTime > 0) {
                counter.totalTime += currentTime - lastFindTime;
            }
            lastFindTime = currentTime;
            logNodesFound(currentTime);
        }

        void logNodesFound(long currentTime) {
            if (firstFindTime == 0) {
                firstFindTime = currentTime;
                findTime = currentTime;
            }
            if (currentTime == 0 || currentTime - findTime > 1432) {
                int duration = 0;
                if (currentTime > 0) {
                    duration = (int) ((currentTime - firstFindTime) / 1000);
                }
                System.out.println(new Date(currentTime) + ": Found "
                        + foundOSMNodes + " nodes during "
                        + duration + "s way creation: ");
                for (String type : nodeFindStats.keySet()) {
                    LogCounter found = nodeFindStats.get(type);
                    double rate = 0.0f;
                    if (found.totalTime > 0) {
                        rate = (1000.0 * (float) found.count / (float) found.totalTime);
                    }
                    System.out.println("\t" + type + ": \t" + found.count
                            + "/" + (found.totalTime / 1000)
                            + "s" + " \t(" + rate
                            + " nodes/second)");
                }
                findTime = currentTime;
            }
        }

        void logNodeAddition(LinkedHashMap<String, Object> tags,
                             String type) {
            Integer count = stats.get(type);
            if (count == null) {
                count = 1;
            } else {
                count++;
            }
            stats.put(type, count);
            long currentTime = System.currentTimeMillis();
            if (firstLogTime == 0) {
                firstLogTime = currentTime;
                logTime = currentTime;
            }
            if (currentTime - logTime > 1432) {
                System.out.println(new Date(currentTime)
                        + ": Saving "
                        + type
                        + " "
                        + count
                        + " \t("
                        + (1000.0 * (float) count / (float) (currentTime - firstLogTime))
                        + " " + type + "/second)");
                logTime = currentTime;
            }
        }

        void describeLoaded() {
            logNodesFound(0);
            for (String type : new String[]{"node", "way", "relation"}) {
                Integer count = stats.get(type);
                if (count != null) {
                    System.out.println("Loaded " + count + " " + type + "s");
                }
            }
        }

        protected abstract long getDatasetId();

        private int missingNodeCount = 0;

        private void missingNode(long ndRef) {
            if (missingNodeCount++ < 10) {
                osmImporter.error("Cannot find node for osm-id " + ndRef);
            }
        }

        private void describeMissing() {
            if (missingNodeCount > 0) {
                osmImporter.error("When processing the ways, there were "
                        + missingNodeCount + " missing nodes");
            }
            if (missingMemberCount > 0) {
                osmImporter.error("When processing the relations, there were "
                        + missingMemberCount + " missing members");
            }
        }

        private int missingMemberCount = 0;

        private void missingMember(String description) {
            if (missingMemberCount++ < 10) {
                osmImporter.error("Cannot find member: " + description);
            }
        }

        T currentNode = null;
        T prev_way = null;
        T prev_relation = null;
        int nodeCount = 0;
        int poiCount = 0;
        int wayCount = 0;
        int relationCount = 0;
        int userCount = 0;
        int changesetCount = 0;

        /**
         * Add the BBox metadata to the dataset
         */
        void addOSMBBox(Map<String, Object> bboxProperties) {
            T bbox = addNode(PROP_BBOX, bboxProperties, null);
            createRelationship(osm_dataset, bbox, OSMRelation.BBOX);
        }

        /**
         * Create a new OSM node from the specified attributes (including
         * location, user, changeset). The node is stored in the currentNode
         * field, so that it can be used in the subsequent call to
         * addOSMNodeTags after we close the XML tag for OSM nodes.
         *
         * @param nodeProps HashMap of attributes for the OSM-node
         */
        void createOSMNode(Map<String, Object> nodeProps) {
            T userNode = getUserNode(nodeProps);
            T changesetNode = getChangesetNode(nodeProps, userNode);
            currentNode = addNode("node", nodeProps, "node_osm_id");
            createRelationship(currentNode, changesetNode, OSMRelation.CHANGESET);
            nodeCount++;
        }

        private void addOSMNodeTags(boolean allPoints,
                                    LinkedHashMap<String, Object> currentNodeTags) {
            currentNodeTags.remove("created_by"); // redundant information
            // Nodes with tags get added to the index as point geometries
            if (allPoints || currentNodeTags.size() > 0) {
                Map<String, Object> nodeProps = getNodeProperties(currentNode);
                double[] location = new double[]{
                        (Double) nodeProps.get("lon"),
                        (Double) nodeProps.get("lat")};
                addNodeGeometry(currentNode, GTYPE_POINT, new Envelope(location), 1);
                poiCount++;
            }
            addNodeTags(currentNode, currentNodeTags, "node");
        }

        protected void debugNodeWithId(T node, String idName, long[] idValues) {
            Map<String, Object> nodeProperties = getNodeProperties(node);
            String node_osm_id = nodeProperties.get(idName).toString();
            for (long idValue : idValues) {
                if (node_osm_id.equals(Long.toString(idValue))) {
                    System.out.println("Debug node: " + node_osm_id);
                }
            }
        }

        protected void createOSMWay(Map<String, Object> wayProperties,
                                    ArrayList<Long> wayNodes, LinkedHashMap<String, Object> wayTags) {
            RoadDirection direction = getRoadDirection(wayTags);
            String name = (String) wayTags.get("name");
            int geometry = GTYPE_LINESTRING;
            boolean isRoad = wayTags.containsKey("highway");
            if (isRoad) {
                wayProperties.put("oneway", direction.toString());
                wayProperties.put("highway", wayTags.get("highway"));
            }
            if (name != null) {
                // Copy name tag to way because this seems like a valuable
                // location for
                // such a property
                wayProperties.put("name", name);
            }
            T userNode = getUserNode(wayProperties);
            T changesetNode = getChangesetNode(wayProperties, userNode);
            T way = addNode(INDEX_NAME_WAY, wayProperties, "way_osm_id");
            createRelationship(way, changesetNode, OSMRelation.CHANGESET);
            if (prev_way == null) {
                createRelationship(osm_dataset, way, OSMRelation.WAYS);
            } else {
                createRelationship(prev_way, way, OSMRelation.NEXT);
            }
            prev_way = way;
            addNodeTags(way, wayTags, "way");
            Envelope bbox = null;
            T firstNode = null;
            T prevNode = null;
            T prevProxy = null;
            Map<String, Object> prevProps = null;
            LinkedHashMap<String, Object> relProps = new LinkedHashMap<String, Object>();
            HashMap<String, Object> directionProps = new HashMap<String, Object>();
            directionProps.put("oneway", true);
            for (long nd_ref : wayNodes) {
                // long pointNode =
                // batchIndexService.getSingleNode("node_osm_id", nd_ref);
                T pointNode = getOSMNode(nd_ref, changesetNode);
                if (pointNode == null) {
                    /*
                     * This can happen if we import not whole planet, so some referenced
                     * nodes will be unavailable
                     */
                    missingNode(nd_ref);
                    continue;
                }
                T proxyNode = createProxyNode();
                if (firstNode == null) {
                    firstNode = pointNode;
                }
                if (prevNode == pointNode) {
                    continue;
                }
                createRelationship(proxyNode, pointNode, OSMRelation.NODE,
                        null);
                Map<String, Object> nodeProps = getNodeProperties(pointNode);
                double[] location = new double[]{
                        (Double) nodeProps.get("lon"),
                        (Double) nodeProps.get("lat")};
                if (bbox == null) {
                    bbox = new Envelope(location);
                } else {
                    bbox.expandToInclude(location);
                }
                if (prevProxy == null) {
                    createRelationship(way, proxyNode, OSMRelation.FIRST_NODE);
                } else {
                    relProps.clear();
                    double[] prevLoc = new double[]{
                            (Double) prevProps.get("lon"),
                            (Double) prevProps.get("lat")};

                    double length = distance(prevLoc[0], prevLoc[1],
                            location[0], location[1]);
                    relProps.put("length", length);

                    // We default to bi-directional (and don't store direction
                    // in the
                    // way node), but if it is one-way we mark it as such, and
                    // define
                    // the direction using the relationship direction
                    if (direction == RoadDirection.BACKWARD) {
                        createRelationship(proxyNode, prevProxy,
                                OSMRelation.NEXT, relProps);
                    } else {
                        createRelationship(prevProxy, proxyNode,
                                OSMRelation.NEXT, relProps);
                    }
                }
                prevNode = pointNode;
                prevProxy = proxyNode;
                prevProps = nodeProps;
            }
            // if (prevNode > 0) {
            // batchGraphDb.createRelationship(way, prevNode,
            // OSMRelation.LAST_NODE, null);
            // }
            if (firstNode != null && prevNode == firstNode) {
                geometry = GTYPE_POLYGON;
            }
            if (wayNodes.size() < 2) {
                geometry = GTYPE_POINT;
            }
            addNodeGeometry(way, geometry, bbox, wayNodes.size());
            this.wayCount++;
        }

        private void createOSMRelation(Map<String, Object> relationProperties,
                                       ArrayList<Map<String, Object>> relationMembers,
                                       LinkedHashMap<String, Object> relationTags) {
            String name = (String) relationTags.get("name");
            if (name != null) {
                // Copy name tag to way because this seems like a valuable
                // location for
                // such a property
                relationProperties.put("name", name);
            }
            T relation = addNode("relation", relationProperties, "relation_osm_id");
            if (prev_relation == null) {
                createRelationship(osm_dataset, relation,
                        OSMRelation.RELATIONS);
            } else {
                createRelationship(prev_relation, relation, OSMRelation.NEXT);
            }
            prev_relation = relation;
            addNodeTags(relation, relationTags, "relation");
            // We will test for cases that invalidate multilinestring further
            // down
            GeometryMetaData metaGeom = new GeometryMetaData(
                    GTYPE_MULTILINESTRING);
            T prevMember = null;
            LinkedHashMap<String, Object> relProps = new LinkedHashMap<String, Object>();
            for (Map<String, Object> memberProps : relationMembers) {
                String memberType = (String) memberProps.get("type");
                long member_ref = Long.parseLong(memberProps.get("ref").toString());
                if (memberType != null) {
                    T member = getSingleNode(memberType, memberType + "_osm_id", member_ref);
                    if (null == member || prevMember == member) {
                        /*
                         * This can happen if we import not whole planet, so some
                         * referenced nodes will be unavailable
                         */
                        missingMember(memberProps.toString());
                        continue;
                    }
                    if (member == relation) {
                        osmImporter.error("Cannot add relation to same member: relation["
                                + relationTags
                                + "] - member["
                                + memberProps + "]");
                        continue;
                    }
                    Map<String, Object> nodeProps = getNodeProperties(member);
                    if (memberType.equals("node")) {
                        double[] location = new double[]{
                                (Double) nodeProps.get("lon"),
                                (Double) nodeProps.get("lat")};
                        metaGeom.expandToIncludePoint(location);
                    } else if (memberType.equals("nodes")) {
                        System.err.println("Unexpected 'nodes' member type");
                    } else {
                        updateGeometryMetaDataFromMember(member, metaGeom,
                                nodeProps);
                    }
                    relProps.clear();
                    String role = (String) memberProps.get("role");
                    if (role != null && role.length() > 0) {
                        relProps.put("role", role);
                        if (role.equals("outer")) {
                            metaGeom.setPolygon();
                        }
                    }
                    createRelationship(relation, member, OSMRelation.MEMBER, relProps);
                    // members can belong to multiple relations, in multiple
                    // orders, so NEXT will clash (also with NEXT between ways
                    // in original way load)
                    // if (prevMember < 0) {
                    // batchGraphDb.createRelationship(relation, member,
                    // OSMRelation.MEMBERS, null);
                    // } else {
                    // batchGraphDb.createRelationship(prevMember, member,
                    // OSMRelation.NEXT, null);
                    // }
                    prevMember = member;
                } else {
                    System.err.println("Cannot process invalid relation member: " + memberProps.toString());
                }
            }
            if (metaGeom.isValid()) {
                addNodeGeometry(relation, metaGeom.getGeometryType(),
                        metaGeom.getBBox(), metaGeom.getVertices());
            }
            this.relationCount++;
        }

        /**
         * This method should be overridden by implementation that are able to
         * perform database or index optimizations when requested, like the
         * batch inserter.
         */
        protected void optimize() {
        }

        protected abstract T getSingleNode(String name, String string,
                                           Object value);

        protected abstract Map<String, Object> getNodeProperties(T member);

        protected abstract T getOSMNode(long osmId, T changesetNode);

        protected abstract void updateGeometryMetaDataFromMember(T member,
                                                                 GeometryMetaData metaGeom, Map<String, Object> nodeProps);

        protected abstract void finish();

        protected abstract T createProxyNode();

        protected abstract T getChangesetNode(Map<String, Object> nodeProps, T userNode);

        protected abstract T getUserNode(Map<String, Object> nodeProps);

    }

    private static class OSMGraphWriter extends OSMWriter<Node> {
        private final GraphDatabaseService graphDb;
        private long currentChangesetId = -1;
        private Node currentChangesetNode;
        private long currentUserId = -1;
        private Node currentUserNode;
        private Node usersNode;
        private final HashMap<Long, Node> changesetNodes = new HashMap<>();
        private Transaction tx;
        private int checkCount = 0;
        private final int txInterval;

        private OSMGraphWriter(GraphDatabaseService graphDb,
                               StatsManager statsManager, OSMImporter osmImporter,
                               int txInterval, boolean relatxedTxFlush) {
            super(statsManager, osmImporter);
            this.graphDb = graphDb;
            this.txInterval = txInterval;
            if (this.txInterval < 100) {
                System.err.println("Warning: Unusually short txInterval, expect bad insert performance");
            }
            checkTx(null); // Opens transaction for future writes
        }

        private void successTx() {
            if (tx != null) {
                tx.commit();
                tx.close();
                tx = null;
                checkCount = 0;
            }
        }

        private Node checkTx(Node previous) {
            if (checkCount++ > txInterval || tx == null || checkCount > 10) {
                successTx();
                tx = graphDb.beginTx();
                osm_dataset = recoverNode(osm_dataset);
                currentNode = recoverNode(currentNode);
                prev_relation = recoverNode(prev_relation);
                prev_way = recoverNode(prev_way);
                currentChangesetNode = recoverNode(currentChangesetNode);
                currentUserNode = recoverNode(currentUserNode);
                usersNode = recoverNode(usersNode);
                previous = recoverNode(previous);
            }
            return previous;
        }

        private Node recoverNode(Node outOfTx) {
            if (outOfTx == null) {
                return null;
            } else {
                long id = outOfTx.getId();
                return tx.getNodeById(id);
            }
        }

        private NodeIndex<Object> indexFor(String indexName) {
            return new NodeIndex<>(indexName);
        }

        private Node findNode(String name, Node parent, RelationshipType relType) {
            for (Relationship relationship : parent.getRelationships(Direction.OUTGOING, relType)) {
                Node node = relationship.getEndNode();
                if (name.equals(node.getProperty("name"))) {
                    return node;
                }
            }
            return null;
        }

        @Override
        protected Node getOrCreateNode(String name, String type, Node parent, RelationshipType relType) {
            Node node = findNode(name, parent, relType);
            if (node == null) {
                node = tx.createNode();
                node.setProperty("name", name);
                node.setProperty("type", type);
                parent.createRelationshipTo(node, relType);
                node = checkTx(node);
            }
            return node;
        }

        @Override
        protected Node getOrCreateOSMDataset(String name) {
            if (osm_dataset == null) {
                Node osm_root = ReferenceNodes.getReferenceNode(tx, "osm_root");
                osm_dataset = getOrCreateNode(name, "osm", osm_root, OSMRelation.OSM);
            }
            return osm_dataset;
        }

        @Override
        protected void setDatasetProperties(Map<String, Object> extractProperties) {
            for (String key : extractProperties.keySet()) {
                osm_dataset.setProperty(key, extractProperties.get(key));
            }
        }

        private void addProperties(Entity node, Map<String, Object> properties) {
            for (String property : properties.keySet()) {
                node.setProperty(property, properties.get(property));
            }
        }

        @Override
        protected void addNodeTags(Node node, LinkedHashMap<String, Object> tags, String type) {
            logNodeAddition(tags, type);
            if (node != null && tags.size() > 0) {
                statsManager.addToTagStats(type, tags.keySet());
                Node tagsNode = tx.createNode();
                addProperties(tagsNode, tags);
                node.createRelationshipTo(tagsNode, OSMRelation.TAGS);
                tags.clear();
            }
        }

        @Override
        protected void addNodeGeometry(Node node, int gtype, Envelope bbox, int vertices) {
            if (node != null && bbox != null && vertices > 0) {
                if (gtype == GTYPE_GEOMETRY) gtype = vertices > 1 ? GTYPE_MULTIPOINT : GTYPE_POINT;
                Node geomNode = tx.createNode();
                geomNode.setProperty("gtype", gtype);
                geomNode.setProperty("vertices", vertices);
                geomNode.setProperty(PROP_BBOX, new double[]{bbox.getMinX(), bbox.getMaxX(), bbox.getMinY(), bbox.getMaxY()});
                node.createRelationshipTo(geomNode, OSMRelation.GEOM);
                statsManager.addGeomStats(gtype);
            }
        }

        @Override
        protected Node addNode(String name, Map<String, Object> properties, String indexKey) {
            Node node = tx.createNode();
            if (indexKey != null && properties.containsKey(indexKey)) {
                indexFor(name).add(node, indexKey, properties.get(indexKey));
                properties.put(indexKey, Long.parseLong(properties.get(indexKey).toString()));
            }
            addProperties(node, properties);
            return checkTx(node);
        }

        protected Node addNodeWithCheck(String name, Map<String, Object> properties, String indexKey) {
            Node node = null;
            Object indexValue = (indexKey == null) ? null : properties.get(indexKey);
            if (indexValue != null && (createdNodes + foundNodes < 100 || foundNodes > 10)) {
                node = indexFor(name).get(indexKey, properties.get(indexKey)).getSingle();
            }
            if (node == null) {
                node = tx.createNode();
                addProperties(node, properties);
                if (indexValue != null) {
                    indexFor(name).add(node, indexKey, (String) properties.get(indexKey));
                }
                createdNodes++;
                node = checkTx(node);
            } else {
                foundNodes++;
            }
            return node;
        }

        @Override
        protected void createRelationship(Node from, Node to, RelationshipType relType, LinkedHashMap<String, Object> relProps) {
            if (from != null & to != null) {
                Relationship rel = from.createRelationshipTo(to, relType);
                if (relProps != null && relProps.size() > 0) {
                    addProperties(rel, relProps);
                }
            }
        }

        @Override
        protected long getDatasetId() {
            return osm_dataset.getId();
        }

        @Override
        protected Node getSingleNode(String name, String string, Object value) {
            return indexFor(name).get(string, value).getSingle();
        }

        @Override
        protected Map<String, Object> getNodeProperties(Node node) {
            LinkedHashMap<String, Object> properties = new LinkedHashMap<String, Object>();
            for (String property : node.getPropertyKeys()) {
                properties.put(property, node.getProperty(property));
            }
            return properties;
        }

        @Override
        protected Node getOSMNode(long osmId, Node changesetNode) {
            if (currentChangesetNode != changesetNode || changesetNodes.isEmpty()) {
                currentChangesetNode = changesetNode;
                changesetNodes.clear();
                if (changesetNode != null) {
                    for (Relationship rel : changesetNode.getRelationships(Direction.INCOMING, OSMRelation.CHANGESET)) {
                        Node node = rel.getStartNode();
                        Long nodeOsmId = (Long) node.getProperty("node_osm_id", null);
                        if (nodeOsmId != null) {
                            changesetNodes.put(nodeOsmId, node);
                        }
                    }
                }
            }
            Node node = changesetNodes.get(osmId);
            if (node == null) {
                logNodeFoundFrom("node-index");
                return indexFor("node").get("node_osm_id", osmId).getSingle();
            } else {
                logNodeFoundFrom(INDEX_NAME_CHANGESET);
                return node;
            }
        }

        @Override
        protected void updateGeometryMetaDataFromMember(Node member, GeometryMetaData metaGeom, Map<String, Object> nodeProps) {
            for (Relationship rel : member.getRelationships(OSMRelation.GEOM)) {
                nodeProps = getNodeProperties(rel.getEndNode());
                metaGeom.checkSupportedGeometry((Integer) nodeProps.get("gtype"));
                metaGeom.expandToIncludeBBox(nodeProps);
            }
        }

        @Override
        protected void finish() {
            osm_dataset.setProperty("relationCount", (Integer) osm_dataset.getProperty("relationCount", 0) + relationCount);
            osm_dataset.setProperty("wayCount", (Integer) osm_dataset.getProperty("wayCount", 0) + wayCount);
            osm_dataset.setProperty("nodeCount", (Integer) osm_dataset.getProperty("nodeCount", 0) + nodeCount);
            osm_dataset.setProperty("poiCount", (Integer) osm_dataset.getProperty("poiCount", 0) + poiCount);
            osm_dataset.setProperty("changesetCount", (Integer) osm_dataset.getProperty("changesetCount", 0) + changesetCount);
            osm_dataset.setProperty("userCount", (Integer) osm_dataset.getProperty("userCount", 0) + userCount);
            successTx();
        }

        @Override
        protected Node createProxyNode() {
            return tx.createNode();
        }

        @Override
        protected Node getChangesetNode(Map<String, Object> nodeProps, Node userNode) {
            Object changesetObj = nodeProps.remove(INDEX_NAME_CHANGESET);
            if (changesetObj != null) {
                long changeset = Long.parseLong(changesetObj.toString());
                if (changeset != currentChangesetId) {
                    currentChangesetId = changeset;
                    NodeIndex.IndexHits result = indexFor(INDEX_NAME_CHANGESET).get(INDEX_NAME_CHANGESET, currentChangesetId);
                    if (result.size() > 0) {
                        currentChangesetNode = (Node) result.getSingle();
                    } else {
                        LinkedHashMap<String, Object> changesetProps = new LinkedHashMap<>();
                        changesetProps.put(INDEX_NAME_CHANGESET, currentChangesetId);
                        changesetProps.put("timestamp", nodeProps.get("timestamp"));
                        currentChangesetNode = addNode(INDEX_NAME_CHANGESET, changesetProps, INDEX_NAME_CHANGESET);
                        changesetCount++;
                        if (userNode != null) {
                            createRelationship(currentChangesetNode, userNode, OSMRelation.USER);
                        }
                    }
                    result.close();
                }
            } else {
                currentChangesetId = OSMWriter.UNKNOWN_CHANGESET;
                currentChangesetNode = null;
                missingChangesets++;
            }
            return currentChangesetNode;
        }

        @Override
        protected Node getUserNode(Map<String, Object> nodeProps) {
            try {
                long uid = Long.parseLong(nodeProps.remove("uid").toString());
                String name = nodeProps.remove(INDEX_NAME_USER).toString();
                if (uid != currentUserId) {
                    currentUserId = uid;
                    NodeIndex.IndexHits result = indexFor(INDEX_NAME_USER).get("uid", currentUserId);
                    if (result.size() > 0) {
                        currentUserNode = indexFor(INDEX_NAME_USER).get("uid", currentUserId).getSingle();
                    } else {
                        LinkedHashMap<String, Object> userProps = new LinkedHashMap<String, Object>();
                        userProps.put("uid", currentUserId);
                        userProps.put("name", name);
                        userProps.put("timestamp", nodeProps.get("timestamp"));
                        currentUserNode = addNode(INDEX_NAME_USER, userProps, "uid");
                        userCount++;
                        if (usersNode == null) {
                            usersNode = tx.createNode();
                            osm_dataset.createRelationshipTo(usersNode, OSMRelation.USERS);
                        }
                        usersNode.createRelationshipTo(currentUserNode, OSMRelation.OSM_USER);
                    }
                    result.close();
                }
            } catch (Exception e) {
                currentUserId = -1;
                currentUserNode = null;
                logMissingUser(nodeProps);
            }
            return currentUserNode;
        }

        public String toString() {
            return "OSMGraphWriter: DatabaseService[" + graphDb + "]:txInterval[" + this.txInterval + "]";
        }

    }

    public void importFile(GraphDatabaseService database, String dataset) throws IOException, XMLStreamException {
        importFile(database, dataset, false, 5000, false);
    }

    public void importFile(GraphDatabaseService database, String dataset, int txInterval, boolean relaxedTxFlush) throws IOException, XMLStreamException {
        importFile(database, dataset, false, txInterval, relaxedTxFlush);
    }

    public void importFile(GraphDatabaseService database, String dataset, boolean allPoints, int txInterval, boolean relaxedTxFlush) throws IOException, XMLStreamException {
        importFile(OSMWriter.fromGraphDatabase(database, stats, this, txInterval, relaxedTxFlush), dataset, allPoints, charset);
    }

    public static class CountedFileReader extends InputStreamReader {
        private long length = 0;
        private long charsRead = 0;

        public CountedFileReader(String path, Charset charset) throws FileNotFoundException {
            super(new FileInputStream(path), charset);
            this.length = (new File(path)).length();
        }

        public CountedFileReader(File file, Charset charset) throws FileNotFoundException {
            super(new FileInputStream(file), charset);
            this.length = file.length();
        }

        public long getCharsRead() {
            return charsRead;
        }

        public long getlength() {
            return length;
        }

        public double getProgress() {
            return length > 0 ? (double) charsRead / (double) length : 0;
        }

        public int getPercentRead() {
            return (int) (100.0 * getProgress());
        }

        public int read(char[] cbuf, int offset, int length)
                throws IOException {
            int read = super.read(cbuf, offset, length);
            if (read > 0) charsRead += read;
            return read;
        }
    }

    private int progress = 0;
    private long progressTime = 0;

    private void beginProgressMonitor(int length) {
        monitor.begin(length);
        progress = 0;
        progressTime = System.currentTimeMillis();
    }

    private void updateProgressMonitor(int currentProgress) {
        if (currentProgress > this.progress) {
            long time = System.currentTimeMillis();
            if (time - progressTime > 1000) {
                monitor.worked(currentProgress - progress);
                progress = currentProgress;
                progressTime = time;
            }
        }
    }

    private void endProgressMonitor() {
        monitor.done();
        progress = 0;
        progressTime = 0;
    }

    public void setCharset(Charset charset) {
        this.charset = charset;
    }

    public void importFile(OSMWriter<?> osmWriter, String dataset, boolean allPoints, Charset charset) throws IOException, XMLStreamException {
        log("Importing with osm-writer: " + osmWriter);
        osmWriter.getOrCreateOSMDataset(layerName);
        osm_dataset = osmWriter.getDatasetId();

        long startTime = System.currentTimeMillis();
        long[] times = new long[]{0L, 0L, 0L, 0L};
        javax.xml.stream.XMLInputFactory factory = javax.xml.stream.XMLInputFactory.newInstance();
        CountedFileReader reader = new CountedFileReader(dataset, charset);
        javax.xml.stream.XMLStreamReader parser = factory.createXMLStreamReader(reader);
        int countXMLTags = 0;
        beginProgressMonitor(100);
        setLogContext(dataset);
        boolean startedWays = false;
        boolean startedRelations = false;
        try {
            ArrayList<String> currentXMLTags = new ArrayList<String>();
            int depth = 0;
            Map<String, Object> wayProperties = null;
            ArrayList<Long> wayNodes = new ArrayList<Long>();
            Map<String, Object> relationProperties = null;
            ArrayList<Map<String, Object>> relationMembers = new ArrayList<Map<String, Object>>();
            LinkedHashMap<String, Object> currentNodeTags = new LinkedHashMap<String, Object>();
            while (true) {
                updateProgressMonitor(reader.getPercentRead());
                incrLogContext();
                int event = parser.next();
                if (event == javax.xml.stream.XMLStreamConstants.END_DOCUMENT) {
                    break;
                }
                switch (event) {
                    case javax.xml.stream.XMLStreamConstants.START_ELEMENT:
                        currentXMLTags.add(depth, parser.getLocalName());
                        String tagPath = currentXMLTags.toString();
                        if (tagPath.equals("[osm]")) {
                            osmWriter.setDatasetProperties(extractProperties(parser));
                        } else if (tagPath.equals("[osm, bounds]")) {
                            osmWriter.addOSMBBox(extractProperties(PROP_BBOX, parser));
                        } else if (tagPath.equals("[osm, node]")) {
                            // <node id="269682538" lat="56.0420950"
                            // lon="12.9693483" user="sanna" uid="31450"
                            // visible="true" version="1" changeset="133823"
                            // timestamp="2008-06-11T12:36:28Z"/>
                            boolean includeNode = true;
                            Map<String, Object> nodeProperties = extractProperties("node", parser);
                            if (filterEnvelope != null) {
                                includeNode = filterEnvelope.contains((Double) nodeProperties.get("lon"), (Double) nodeProperties.get("lat"));
                            }
                            if (includeNode) {
                                osmWriter.createOSMNode(nodeProperties);
                            }
                        } else if (tagPath.equals("[osm, way]")) {
                            // <way id="27359054" user="spull" uid="61533"
                            // visible="true" version="8" changeset="4707351"
                            // timestamp="2010-05-15T15:39:57Z">
                            if (!startedWays) {
                                startedWays = true;
                                times[0] = System.currentTimeMillis();
                                osmWriter.optimize();
                                times[1] = System.currentTimeMillis();
                            }
                            wayProperties = extractProperties("way", parser);
                            wayNodes.clear();
                        } else if (tagPath.equals("[osm, way, nd]")) {
                            Map<String, Object> properties = extractProperties(parser);
                            wayNodes.add(Long.parseLong(properties.get("ref").toString()));
                        } else if (tagPath.endsWith("tag]")) {
                            Map<String, Object> properties = extractProperties(parser);
                            currentNodeTags.put(properties.get("k").toString(),
                                    properties.get("v").toString());
                        } else if (tagPath.equals("[osm, relation]")) {
                            // <relation id="77965" user="Grillo" uid="13957"
                            // visible="true" version="24" changeset="5465617"
                            // timestamp="2010-08-11T19:25:46Z">
                            if (!startedRelations) {
                                startedRelations = true;
                                times[2] = System.currentTimeMillis();
                                osmWriter.optimize();
                                times[3] = System.currentTimeMillis();
                            }
                            relationProperties = extractProperties("relation",
                                    parser);
                            relationMembers.clear();
                        } else if (tagPath.equals("[osm, relation, member]")) {
                            relationMembers.add(extractProperties(parser));
                        }
                        if (startedRelations) {
                            if (countXMLTags < 10) {
                                debug("Starting tag at depth " + depth + ": "
                                        + currentXMLTags.get(depth) + " - "
                                        + currentXMLTags.toString());
                                for (int i = 0; i < parser.getAttributeCount(); i++) {
                                    debug("\t" + currentXMLTags.toString() + ": "
                                            + parser.getAttributeLocalName(i) + "["
                                            + parser.getAttributeNamespace(i) + ","
                                            + parser.getAttributePrefix(i) + ","
                                            + parser.getAttributeType(i) + ","
                                            + "] = " + parser.getAttributeValue(i));
                                }
                            }
                            countXMLTags++;
                        }
                        depth++;
                        break;
                    case javax.xml.stream.XMLStreamConstants.END_ELEMENT:
                        switch (currentXMLTags.toString()) {
                            case "[osm, node]":
                                osmWriter.addOSMNodeTags(allPoints, currentNodeTags);
                                break;
                            case "[osm, way]":
                                osmWriter.createOSMWay(wayProperties, wayNodes, currentNodeTags);
                                break;
                            case "[osm, relation]":
                                osmWriter.createOSMRelation(relationProperties, relationMembers, currentNodeTags);
                                break;
                        }
                        depth--;
                        currentXMLTags.remove(depth);
                        // log("Ending tag at depth "+depth+": "+currentTags.get(depth));
                        break;
                    default:
                        break;
                }
            }
        } finally {
            endProgressMonitor();
            parser.close();
            osmWriter.finish();
            this.osm_dataset = osmWriter.getDatasetId();
            this.missingChangesets = osmWriter.missingChangesets;
        }
        if (verboseLog) {
            describeTimes(startTime, times);
            osmWriter.describeMissing();
            osmWriter.describeLoaded();

            long stopTime = System.currentTimeMillis();
            log("info | Elapsed time in seconds: "
                    + (1.0 * (stopTime - startTime) / 1000.0));
            stats.dumpGeomStats();
            stats.printTagStats();
        }
    }

    private void describeTimes(long startTime, long[] times) {
        long endTime = System.currentTimeMillis();
        log("Completed load in " + (1.0 * (endTime - startTime) / 1000.0) + "s");
        log("\tImported nodes:  " + (1.0 * (times[0] - startTime) / 1000.0) + "s");
        log("\tOptimized index: " + (1.0 * (times[1] - times[0]) / 1000.0) + "s");
        log("\tImported ways:   " + (1.0 * (times[2] - times[1]) / 1000.0) + "s");
        log("\tOptimized index: " + (1.0 * (times[3] - times[2]) / 1000.0) + "s");
        log("\tImported rels:   " + (1.0 * (endTime - times[3]) / 1000.0) + "s");
    }

    private Map<String, Object> extractProperties(XMLStreamReader parser) {
        return extractProperties(null, parser);
    }

    private Map<String, Object> extractProperties(String name, XMLStreamReader parser) {
        // <node id="269682538" lat="56.0420950" lon="12.9693483" user="sanna"
        // uid="31450" visible="true" version="1" changeset="133823"
        // timestamp="2008-06-11T12:36:28Z"/>
        // <way id="27359054" user="spull" uid="61533" visible="true"
        // version="8" changeset="4707351" timestamp="2010-05-15T15:39:57Z">
        // <relation id="77965" user="Grillo" uid="13957" visible="true"
        // version="24" changeset="5465617" timestamp="2010-08-11T19:25:46Z">
        LinkedHashMap<String, Object> properties = new LinkedHashMap<String, Object>();
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            String prop = parser.getAttributeLocalName(i);
            String value = parser.getAttributeValue(i);
            if (name != null && prop.equals("id")) {
                prop = name + "_osm_id";
                name = null;
            }
            if (prop.equals("lat") || prop.equals("lon")) {
                properties.put(prop, Double.parseDouble(value));
            } else if (name != null && prop.equals("version")) {
                properties.put(prop, Integer.parseInt(value));
            } else if (prop.equals("visible")) {
                if (!value.equals("true") && !value.equals("1")) {
                    properties.put(prop, false);
                }
            } else if (prop.equals("timestamp")) {
                try {
                    Date timestamp = timestampFormat.parse(value);
                    properties.put(prop, timestamp.getTime());
                } catch (ParseException e) {
                    error("Error parsing timestamp", e);
                }
            } else {
                properties.put(prop, value);
            }
        }
        if (name != null) {
            properties.put("name", name);
        }
        return properties;
    }

    /**
     * Retrieves the direction of the given road, i.e. whether it is a one-way road from its start node,
     * a one-way road to its start node or a two-way road.
     *
     * @param wayProperties the property map of the road
     * @return BOTH if it's a two-way road, FORWARD if it's a one-way road from the start node,
     * or BACKWARD if it's a one-way road to the start node
     */
    public static RoadDirection getRoadDirection(Map<String, Object> wayProperties) {
        String oneway = (String) wayProperties.get("oneway");
        if (null != oneway) {
            if ("-1".equals(oneway)) return RoadDirection.BACKWARD;
            if ("1".equals(oneway) || "yes".equalsIgnoreCase(oneway) || "true".equalsIgnoreCase(oneway))
                return RoadDirection.FORWARD;
        }
        return RoadDirection.BOTH;
    }

    /**
     * Calculate correct distance between 2 points on Earth.
     *
     * @param latA
     * @param lonA
     * @param latB
     * @param lonB
     * @return distance in meters
     */
    public static double distance(double lonA, double latA, double lonB, double latB) {
        return WGS84.orthodromicDistance(lonA, latA, lonB, latB);
    }

    private void log(PrintStream out, String message) {
        if (logContext != null) {
            message = logContext + "[" + contextLine + "]: " + message;
        }
        out.println(message);
    }

    private void log(String message) {
        if (verboseLog) {
            log(System.out, message);
        }
    }

    private void debug(String message) {
        if (debugLog) {
            log(System.out, message);
        }
    }

    private void error(String message) {
        log(System.err, message);
    }

    private void error(String message, Exception e) {
        log(System.err, message);
        e.printStackTrace(System.err);
    }

    private String logContext = null;
    private int contextLine = 0;
    private boolean debugLog = false;
    private boolean verboseLog = true;

    // "2008-06-11T12:36:28Z"
    private DateFormat timestampFormat = new SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'");

    public void setDebug(boolean verbose) {
        this.debugLog = verbose;
        this.verboseLog |= verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verboseLog = verbose;
        this.debugLog &= verbose;
    }

    private void setLogContext(String context) {
        logContext = context;
        contextLine = 0;
    }

    private void incrLogContext() {
        contextLine++;
    }

    /**
     * This method allows for a console, command-line application for loading
     * one or more *.osm files into a new database.
     *
     * @param args , the database directory followed by one or more osm files
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: osmimporter databasedir osmfile <..osmfiles..>");
        } else {
            OSMImportManager importer = new OSMImportManager(args[0]);
            for (int i = 1; i < args.length; i++) {
                try {
                    importer.loadTestOsmData(args[i], 5000);
                } catch (Exception e) {
                    System.err.println("Error importing OSM file '" + args[i] + "': " + e);
                    e.printStackTrace();
                } finally {
                    importer.shutdown();
                }
            }
        }
    }

    private static class OSMImportManager {
        private DatabaseManagementService databases;
        private GraphDatabaseService graphDb;
        private File dbPath;
        private String databaseName = "neo4j";  // can only be something other than neo4j in enterprise edition

        public OSMImportManager(String path) {
            setDbPath(path);
        }

        public void setDbPath(String path) {
            dbPath = new File(path);
            if (dbPath.exists()) {
                if (!dbPath.isDirectory()) {
                    throw new RuntimeException("Database path is an existing file: " + dbPath.getAbsolutePath());
                }
            } else {
                dbPath.mkdirs();
            }
        }

        private void loadTestOsmData(String layerName, int commitInterval) throws Exception {
            String osmPath = layerName;
            System.out.println("\n=== Loading layer " + layerName + " from " + osmPath + " ===\n");
            long start = System.currentTimeMillis();
            OSMImporter importer = new OSMImporter(layerName);
            prepareDatabase(true);
            importer.importFile(graphDb, osmPath, false, commitInterval, true);
            importer.reIndex(graphDb, commitInterval);
            shutdown();
            System.out.println("=== Completed loading " + layerName + " in " + (System.currentTimeMillis() - start) / 1000.0 + " seconds ===");
        }

        private DatabaseLayout prepareLayout(boolean delete) throws IOException {
            Neo4jLayout homeLayout = Neo4jLayout.of(dbPath);
            DatabaseLayout databaseLayout = homeLayout.databaseLayout(databaseName);
            if (delete) {
                FileUtils.deleteRecursively(databaseLayout.databaseDirectory());
                FileUtils.deleteRecursively(databaseLayout.getTransactionLogsDirectory());
            }
            return databaseLayout;
        }

        private void prepareDatabase(boolean delete) throws IOException {
            shutdown();
            prepareLayout(delete);
            databases = new DatabaseManagementServiceBuilder(dbPath).build();
            graphDb = databases.database(databaseName);
        }

        protected void shutdown() {
            if (databases != null) {
                databases.shutdown();
                databases = null;
                graphDb = null;
            }
        }
    }
}
