/**
 * This file is part of Graylog.
 *
 * Graylog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog2.indexer.indices;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.WriteConsistencyLevel;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthStatus;
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest;
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest;
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesResponse;
import org.elasticsearch.action.admin.indices.close.CloseIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsResponse;
import org.elasticsearch.action.admin.indices.flush.FlushRequest;
import org.elasticsearch.action.admin.indices.get.GetIndexRequest;
import org.elasticsearch.action.admin.indices.get.GetIndexResponse;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingResponse;
import org.elasticsearch.action.admin.indices.open.OpenIndexRequest;
import org.elasticsearch.action.admin.indices.optimize.OptimizeRequest;
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsRequest;
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsResponse;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.elasticsearch.action.admin.indices.stats.IndexStats;
import org.elasticsearch.action.admin.indices.stats.IndicesStatsRequest;
import org.elasticsearch.action.admin.indices.stats.IndicesStatsResponse;
import org.elasticsearch.action.admin.indices.stats.ShardStats;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.IndicesAdminClient;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.common.collect.UnmodifiableIterator;
import org.elasticsearch.common.hppc.cursors.ObjectObjectCursor;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.graylog2.configuration.ElasticsearchConfiguration;
import org.graylog2.indexer.IndexMapping;
import org.graylog2.indexer.IndexNotFoundException;
import org.graylog2.plugin.indexer.retention.IndexManagement;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;

@Singleton
public class Indices implements IndexManagement {

    private static final Logger LOG = LoggerFactory.getLogger(Indices.class);

    private final Client c;
    private final ElasticsearchConfiguration configuration;
    private final IndexMapping indexMapping;

    @Inject
    public Indices(Client client, ElasticsearchConfiguration configuration, IndexMapping indexMapping) {
        this.c = client;
        this.configuration = configuration;
        this.indexMapping = indexMapping;
    }

    public void move(String source, String target) {
        QueryBuilder qb = matchAllQuery();

        SearchResponse scrollResp = c.prepareSearch(source)
                .setSearchType(SearchType.SCAN)
                .setScroll(new TimeValue(10000))
                .setQuery(qb)
                .setSize(350).execute().actionGet();

        while (true) {
            scrollResp = c.prepareSearchScroll(scrollResp.getScrollId()).setScroll(new TimeValue(60000)).execute().actionGet();

            // No more hits.
            if (scrollResp.getHits().hits().length == 0) {
                break;
            }

            final BulkRequestBuilder request = c.prepareBulk();
            for (SearchHit hit : scrollResp.getHits()) {
                Map<String, Object> doc = hit.getSource();
                String id = (String) doc.remove("_id");

                request.add(manualIndexRequest(target, doc, id).request());
            }

            request.setConsistencyLevel(WriteConsistencyLevel.ONE);

            if (request.numberOfActions() > 0) {
                BulkResponse response = c.bulk(request.request()).actionGet();

                LOG.info("Moving index <{}> to <{}>: Bulk indexed {} messages, took {} ms, failures: {}",
                         source,
                         target,
                         response.getItems().length,
                         response.getTookInMillis(),
                         response.hasFailures());

                if (response.hasFailures()) {
                    throw new RuntimeException("Failed to move a message. Check your indexer log.");
                }
            }
        }

    }

    public void delete(String indexName) {
        c.admin().indices().delete(new DeleteIndexRequest(indexName)).actionGet();
    }

    public void close(String indexName) {
        c.admin().indices().close(new CloseIndexRequest(indexName)).actionGet();
    }

    public long numberOfMessages(String indexName) throws IndexNotFoundException {
        Map<String, IndexStats> indices = getAll();
        IndexStats index = indices.get(indexName);

        if (index == null) {
            throw new IndexNotFoundException();
        }

        return index.getPrimaries().getDocs().getCount();
    }

    public Map<String, IndexStats> getAll() {
        ActionFuture<IndicesStatsResponse> isr = c.admin().indices().stats(new IndicesStatsRequest().all());

        return isr.actionGet().getIndices();
    }

    public String allIndicesAlias() {
        return configuration.getIndexPrefix() + "_*";
    }

    public boolean exists(String index) {
        ActionFuture<IndicesExistsResponse> existsFuture = c.admin().indices().exists(new IndicesExistsRequest(index));
        return existsFuture.actionGet().isExists();
    }

    public boolean aliasExists(String alias) {
        return c.admin().indices().aliasesExist(new GetAliasesRequest(alias)).actionGet().exists();
    }

    @Nullable
    public String aliasTarget(String alias) {
        final IndicesAdminClient indicesAdminClient = c.admin().indices();

        final GetAliasesRequest request = indicesAdminClient.prepareGetAliases(alias).request();
        final GetAliasesResponse response = indicesAdminClient.getAliases(request).actionGet();

        // The ES return value of this has an awkward format: The first key of the hash is the target index. Thanks.
        return response.getAliases().isEmpty() ? null : response.getAliases().keysIt().next();
    }

    public boolean create(String indexName) {
        final Map<String, String> keywordLowercase = ImmutableMap.of(
                "tokenizer", "keyword",
                "filter", "lowercase");
        final Map<String, Object> settings = ImmutableMap.of(
                "number_of_shards", configuration.getShards(),
                "number_of_replicas", configuration.getReplicas(),
                "index.analysis.analyzer.analyzer_keyword", keywordLowercase);

        final CreateIndexRequest cir = c.admin().indices().prepareCreate(indexName).setSettings(settings).request();
        if (!c.admin().indices().create(cir).actionGet().isAcknowledged()) {
            return false;
        }

        final Map<String, Object> messageMapping = indexMapping.messageMapping(configuration.getAnalyzer());
        final PutMappingResponse messageMappingResponse =
                indexMapping.createMapping(indexName, IndexMapping.TYPE_MESSAGE, messageMapping).actionGet();
        final Map<String, Object> metaMapping = indexMapping.metaMapping();
        final PutMappingResponse metaMappingResponse =
                indexMapping.createMapping(indexName, IndexMapping.TYPE_INDEX_RANGE, metaMapping).actionGet();

        return messageMappingResponse.isAcknowledged() && metaMappingResponse.isAcknowledged();
    }

    public Set<String> getAllMessageFields() {
        Set<String> fields = Sets.newHashSet();

        ClusterStateRequest csr = new ClusterStateRequest().blocks(true).nodes(true).indices(allIndicesAlias());
        ClusterState cs = c.admin().cluster().state(csr).actionGet().getState();

        for (ObjectObjectCursor<String, IndexMetaData> m : cs.getMetaData().indices()) {
            try {
                MappingMetaData mmd = m.value.mapping(IndexMapping.TYPE_MESSAGE);
                if (mmd == null) {
                    // There is no mapping if there are no messages in the index.
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> mapping = (Map<String, Object>) mmd.getSourceAsMap().get("properties");

                fields.addAll(mapping.keySet());
            } catch (Exception e) {
                LOG.error("Error while trying to get fields of <" + m.index + ">", e);
            }
        }

        return fields;
    }

    private IndexRequestBuilder manualIndexRequest(String index, Map<String, Object> doc, String id) {
        final IndexRequestBuilder b = new IndexRequestBuilder(c);
        b.setIndex(index);
        b.setId(id);
        b.setSource(doc);
        b.setOpType(IndexRequest.OpType.INDEX);
        b.setType(IndexMapping.TYPE_MESSAGE);
        b.setConsistencyLevel(WriteConsistencyLevel.ONE);

        return b;
    }

    public void setReadOnly(String index) {
        ImmutableSettings.Builder sb = ImmutableSettings.builder();

        // http://www.elasticsearch.org/guide/reference/api/admin-indices-update-settings/
        sb.put("index.blocks.write", true); // Block writing.
        sb.put("index.blocks.read", false); // Allow reading.
        sb.put("index.blocks.metadata", false); // Allow getting metadata.

        c.admin().indices().updateSettings(new UpdateSettingsRequest(index).settings(sb.build())).actionGet();
    }

    public boolean isReadOnly(String index) {
        final GetSettingsRequest request = c.admin().indices().prepareGetSettings(index).request();
        final GetSettingsResponse response = c.admin().indices().getSettings(request).actionGet();

        return response.getIndexToSettings().get(index).getAsBoolean("index.blocks.write", false);
    }

    public void setReadWrite(String index) {
        Settings settings = ImmutableSettings.builder()
                .put("index.blocks.write", false)
                .put("index.blocks.read", false)
                .put("index.blocks.metadata", false)
                .build();

        final UpdateSettingsRequest request = c.admin().indices().prepareUpdateSettings(index)
                .setSettings(settings)
                .request();
        c.admin().indices().updateSettings(request).actionGet();
    }

    public void flush(String index) {
        FlushRequest flush = new FlushRequest(index);
        flush.force(true); // Just flushes. Even if it is not necessary.

        c.admin().indices().flush(new FlushRequest(index).force(true)).actionGet();
    }

    public void reopenIndex(String index) {
        // Mark this index as re-opened. It will never be touched by retention.
        UpdateSettingsRequest settings = new UpdateSettingsRequest(index);
        settings.settings(Collections.<String, Object>singletonMap("graylog2_reopened", true));
        c.admin().indices().updateSettings(settings).actionGet();

        // Open index.
        c.admin().indices().open(new OpenIndexRequest(index)).actionGet();
    }

    public boolean isReopened(String indexName) {
        ClusterState clusterState = c.admin().cluster().state(new ClusterStateRequest()).actionGet().getState();
        IndexMetaData metaData = clusterState.getMetaData().getIndices().get(indexName);

        if (metaData == null) {
            return false;
        }

        return checkForReopened(metaData);
    }

    protected Boolean checkForReopened(IndexMetaData metaData) {
        return metaData.getSettings().getAsBoolean("index.graylog2_reopened", false);
    }

    public Set<String> getClosedIndices() {
        final Set<String> closedIndices = Sets.newHashSet();

        ClusterStateRequest csr = new ClusterStateRequest()
                .nodes(false)
                .routingTable(false)
                .blocks(false)
                .metaData(true);

        ClusterState state = c.admin().cluster().state(csr).actionGet().getState();

        UnmodifiableIterator<IndexMetaData> it = state.getMetaData().getIndices().valuesIt();

        while (it.hasNext()) {
            IndexMetaData indexMeta = it.next();
            // Only search in our indices.
            if (!indexMeta.getIndex().startsWith(configuration.getIndexPrefix())) {
                continue;
            }
            if (indexMeta.getState().equals(IndexMetaData.State.CLOSE)) {
                closedIndices.add(indexMeta.getIndex());
            }
        }
        return closedIndices;
    }

    public Set<String> getReopenedIndices() {
        final Set<String> reopenedIndices = Sets.newHashSet();

        ClusterStateRequest csr = new ClusterStateRequest()
                .nodes(false)
                .routingTable(false)
                .blocks(false)
                .metaData(true);

        ClusterState state = c.admin().cluster().state(csr).actionGet().getState();

        UnmodifiableIterator<IndexMetaData> it = state.getMetaData().getIndices().valuesIt();

        while (it.hasNext()) {
            IndexMetaData indexMeta = it.next();
            // Only search in our indices.
            if (!indexMeta.getIndex().startsWith(configuration.getIndexPrefix())) {
                continue;
            }
            if (checkForReopened(indexMeta)) {
                reopenedIndices.add(indexMeta.getIndex());
            }
        }
        return reopenedIndices;
    }

    public IndexStatistics getIndexStats(String index) {
        final IndexStatistics stats = new IndexStatistics();
        try {
            IndicesStatsResponse indicesStatsResponse = c.admin().indices().stats(new IndicesStatsRequest().all()).actionGet();
            IndexStats indexStats = indicesStatsResponse.getIndex(index);

            if (indexStats == null) {
                return null;
            }
            stats.setPrimaries(indexStats.getPrimaries());
            stats.setTotal(indexStats.getTotal());

            for (ShardStats shardStats : indexStats.getShards()) {
                stats.addShardRouting(shardStats.getShardRouting());
            }
        } catch (ElasticsearchException e) {
            return null;
        }
        return stats;
    }

    public boolean cycleAlias(String aliasName, String targetIndex) {
        return c.admin().indices().prepareAliases()
                .addAlias(targetIndex, aliasName)
                .execute().actionGet().isAcknowledged();
    }

    public boolean cycleAlias(String aliasName, String targetIndex, String oldIndex) {
        return c.admin().indices().prepareAliases()
                .removeAlias(oldIndex, aliasName)
                .addAlias(targetIndex, aliasName)
                .execute().actionGet().isAcknowledged();
    }

    public void optimizeIndex(String index) {
        // http://www.elasticsearch.org/guide/reference/api/admin-indices-optimize/
        final OptimizeRequest or = new OptimizeRequest(index)
                .maxNumSegments(configuration.getIndexOptimizationMaxNumSegments())
                .onlyExpungeDeletes(false)
                .flush(true);

        // Using a specific timeout to override the global Elasticsearch request timeout
        c.admin().indices().optimize(or).actionGet(1L, TimeUnit.HOURS);
    }

    public ClusterHealthStatus waitForRecovery(String index) {
        return waitForStatus(index, ClusterHealthStatus.YELLOW);
    }

    public ClusterHealthStatus waitForStatus(String index, ClusterHealthStatus clusterHealthStatus) {
        final ClusterHealthRequest request = c.admin().cluster().prepareHealth(index)
                .setWaitForStatus(clusterHealthStatus)
                .request();

        LOG.debug("Waiting until index health status of index {} is {}", index, clusterHealthStatus);

        final ClusterHealthResponse response = c.admin().cluster().health(request).actionGet(5L, TimeUnit.MINUTES);
        return response.getStatus();
    }

    @Nullable
    public DateTime indexCreationDate(String index) {
        final GetIndexRequest indexRequest = c.admin().indices().prepareGetIndex()
                .addFeatures(GetIndexRequest.Feature.SETTINGS)
                .addIndices(index)
                .request();
        try {
            final GetIndexResponse response = c.admin().indices()
                    .getIndex(indexRequest).actionGet();
            final Settings settings = response.settings().get(index);
            if (settings == null) {
                return null;
            }
            return new DateTime(settings.getAsLong("creation_date", 0L), DateTimeZone.UTC);
        } catch (ElasticsearchException e) {
            LOG.warn("Unable to read creation_date for index " + index, e.getRootCause());
            return null;
        }
    }
}
