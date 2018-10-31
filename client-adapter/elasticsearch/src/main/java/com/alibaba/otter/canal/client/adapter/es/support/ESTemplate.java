package com.alibaba.otter.canal.client.adapter.es.support;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.index.reindex.UpdateByQueryAction;
import org.elasticsearch.index.reindex.UpdateByQueryRequestBuilder;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import com.alibaba.otter.canal.client.adapter.es.config.ESSyncConfig;
import com.alibaba.otter.canal.client.adapter.es.config.ESSyncConfig.ESMapping;
import com.alibaba.otter.canal.client.adapter.es.config.SchemaItem;
import com.alibaba.otter.canal.client.adapter.es.config.SchemaItem.FieldItem;

public class ESTemplate {

    private static final Logger logger         = LoggerFactory.getLogger(ESTemplate.class);

    private static final int    MAX_BATCH_SIZE = 1000;

    private TransportClient     transportClient;

    public ESTemplate(TransportClient transportClient){
        this.transportClient = transportClient;
    }

    public void setTransportClient(TransportClient transportClient) {
        this.transportClient = transportClient;
    }

    /**
     * 插入数据
     * 
     * @param esSyncConfig
     * @param pkVal
     * @param esFieldData
     * @return
     */
    public boolean insert(ESSyncConfig esSyncConfig, Object pkVal, Map<String, Object> esFieldData) {
        BulkRequestBuilder bulkRequestBuilder = transportClient.prepareBulk();
        ESMapping mapping = esSyncConfig.getEsMapping();
        if (mapping.get_id() != null) {
            bulkRequestBuilder
                .add(transportClient.prepareIndex(mapping.get_index(), mapping.get_type(), pkVal.toString())
                    .setSource(esFieldData));
        } else {
            SearchResponse response = transportClient.prepareSearch(mapping.get_index())
                .setTypes(mapping.get_type())
                .setQuery(QueryBuilders.termQuery(mapping.getPk(), pkVal))
                .setSize(MAX_BATCH_SIZE)
                .get();
            for (SearchHit hit : response.getHits()) {
                bulkRequestBuilder
                    .add(transportClient.prepareDelete(mapping.get_index(), mapping.get_type(), hit.getId()));
            }
            bulkRequestBuilder
                .add(transportClient.prepareIndex(mapping.get_index(), mapping.get_type()).setSource(esFieldData));
        }
        return commitBulkRequest(bulkRequestBuilder);
    }

    /**
     * 根据主键更新数据
     * 
     * @param esSyncConfig
     * @param pkVal
     * @param esFieldData
     * @return
     */
    public boolean update(ESSyncConfig esSyncConfig, Object pkVal, Map<String, Object> esFieldData) {
        BulkRequestBuilder bulkRequestBuilder = transportClient.prepareBulk();
        append4Update(bulkRequestBuilder, esSyncConfig, pkVal, esFieldData);
        return commitBulkRequest(bulkRequestBuilder);
    }

    /**
     * 结合 addBulkRequest4Update 批量更新
     * 
     * @param consumer
     * @return
     */
    public boolean updateBatch(Consumer<BulkRequestBuilder> consumer) {
        BulkRequestBuilder bulkRequestBuilder = transportClient.prepareBulk();
        consumer.accept(bulkRequestBuilder);
        return commitBulkRequest(bulkRequestBuilder);
    }

    public void append4Update(BulkRequestBuilder bulkRequestBuilder, ESSyncConfig esSyncConfig, Object pkVal,
                              Map<String, Object> esFieldData) {
        ESMapping mapping = esSyncConfig.getEsMapping();
        if (mapping.get_id() != null) {
            bulkRequestBuilder
                .add(transportClient.prepareUpdate(mapping.get_index(), mapping.get_type(), pkVal.toString())
                    .setDoc(esFieldData));
        } else {
            SearchResponse response = transportClient.prepareSearch(mapping.get_index())
                .setTypes(mapping.get_type())
                .setQuery(QueryBuilders.termQuery(mapping.getPk(), pkVal))
                .setSize(MAX_BATCH_SIZE)
                .get();
            for (SearchHit hit : response.getHits()) { // 理论上只有一条
                bulkRequestBuilder
                    .add(transportClient.prepareUpdate(mapping.get_index(), mapping.get_type(), hit.getId())
                        .setDoc(esFieldData));
            }
        }
    }

    /**
     * update by query
     *
     * @param esSyncConfig
     * @param queryBuilder
     * @param esFieldData
     * @return
     */
    private boolean updateByQuery(ESSyncConfig esSyncConfig, QueryBuilder queryBuilder,
                                  Map<String, Object> esFieldData) {
        return updateByQuery(esSyncConfig, queryBuilder, esFieldData, 0);
    }

    private boolean updateByQuery(ESSyncConfig esSyncConfig, QueryBuilder queryBuilder, Map<String, Object> esFieldData,
                                  int counter) {
        if (CollectionUtils.isEmpty(esFieldData)) {
            return true;
        }

        ESMapping mapping = esSyncConfig.getEsMapping();

        StringBuilder sb = new StringBuilder();
        esFieldData.forEach((key, value) -> {
            if (value instanceof Map) {
                HashMap mapValue = (HashMap) value;
                if (mapValue.containsKey("lon") && mapValue.containsKey("lat") && mapValue.size() == 2) {
                    sb.append("ctx._source")
                        .append("['")
                        .append(key)
                        .append("']")
                        .append(" = [")
                        .append(mapValue.get("lon"))
                        .append(", ")
                        .append(mapValue.get("lat"))
                        .append("];");
                } else {
                    logger.warn("Unsupported object type for script_update");
                }
            } else if (value instanceof String) {
                sb.append("ctx._source")
                    .append("['")
                    .append(key)
                    .append("']")
                    .append(" = '")
                    .append(value)
                    .append("';");
            } else {
                sb.append("ctx._source").append("['").append(key).append("']").append(" = ").append(value).append(";");
            }
        });
        String scriptLine = sb.toString();
        if (logger.isTraceEnabled()) {
            logger.trace(scriptLine);
        }

        UpdateByQueryRequestBuilder updateByQuery = UpdateByQueryAction.INSTANCE.newRequestBuilder(transportClient);
        updateByQuery.source(mapping.get_index())
            .abortOnVersionConflict(false)
            .filter(queryBuilder)
            .script(new Script(ScriptType.INLINE, "painless", scriptLine, Collections.emptyMap()));

        BulkByScrollResponse response = updateByQuery.get();
        if (logger.isTraceEnabled()) {
            logger.trace("updateByQuery response: {}", response.getStatus());
        }
        if (!CollectionUtils.isEmpty(response.getSearchFailures())) {
            logger.error("script update_for_search has search error: " + response.getBulkFailures());
            return false;
        }

        if (!CollectionUtils.isEmpty(response.getBulkFailures())) {
            logger.error("script update_for_search has update error: " + response.getBulkFailures());
            return false;
        }

        if (response.getStatus().getVersionConflicts() > 0) {
            if (counter >= 3) {
                logger.error("第 {} 次执行updateByQuery, 依旧存在版本冲突，不再继续重试。", counter);
                return false;
            }
            logger.warn("本次updateByQuery存在版本冲突，准备重新执行...");
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                // ignore
            }
            return updateByQuery(esSyncConfig, queryBuilder, esFieldData, ++counter);
        }

        return true;
    }

    /**
     * 通过主键删除数据
     *
     * @param esSyncConfig
     * @param pkVal
     * @return
     */
    public boolean delete(ESSyncConfig esSyncConfig, Object pkVal) {
        BulkRequestBuilder bulkRequestBuilder = transportClient.prepareBulk();
        ESMapping mapping = esSyncConfig.getEsMapping();
        if (mapping.get_id() != null) {
            bulkRequestBuilder
                .add(transportClient.prepareDelete(mapping.get_index(), mapping.get_type(), pkVal.toString()));
        } else {
            SearchResponse response = transportClient.prepareSearch(mapping.get_index())
                .setTypes(mapping.get_type())
                .setQuery(QueryBuilders.termQuery(mapping.getPk(), pkVal))
                .setSize(MAX_BATCH_SIZE)
                .get();
            for (SearchHit hit : response.getHits()) {
                bulkRequestBuilder
                    .add(transportClient.prepareDelete(mapping.get_index(), mapping.get_type(), hit.getId()));
            }
        }
        return commitBulkRequest(bulkRequestBuilder);
    }

    /**
     * 批量提交
     *
     * @param bulkRequestBuilder
     * @return
     */
    private static boolean commitBulkRequest(BulkRequestBuilder bulkRequestBuilder) {
        if (bulkRequestBuilder.numberOfActions() > 0) {
            BulkResponse response = bulkRequestBuilder.execute().actionGet();
            if (response.hasFailures()) {
                for (BulkItemResponse itemResponse : response.getItems()) {
                    if (!itemResponse.isFailed()) {
                        continue;
                    }

                    if (itemResponse.getFailure().getStatus() == RestStatus.NOT_FOUND) {
                        logger.warn(itemResponse.getFailureMessage());
                    } else {
                        logger.error("ES 同步数据错误 {}", itemResponse.getFailureMessage());
                    }
                }
            }

            return !response.hasFailures();
        }
        return true;
    }

    // public Object getRSDataValue(ESMapping mapping, ResultSet rs, String
    // fieldName) throws SQLException {
    // String esType = getEsType(mapping, fieldName);
    // Object value = rs.getObject(fieldName);
    // if (value instanceof Boolean) {
    // // 判断es类型
    // if (!"boolean".equals(esType)) {
    // value = rs.getByte(fieldName);
    // }
    // }
    // return value;
    // }
    //
    // public Object getDataValue(ESMapping mapping, Map<String, Object> data,
    // String fieldName) {
    // String esType = getEsType(mapping, fieldName);
    // Object value = data.get(fieldName);
    // if (value instanceof Byte) {
    // if ("boolean".equals(esType)) {
    // value = ((Byte) value).intValue() != 0;
    // }
    // }
    // return value;
    // }

    // public Object convertType(ESMapping mapping, String fieldName, Object val) {
    // // 从mapping中获取类型来转换
    // String esType = getEsType(mapping, fieldName);
    // return ESSyncUtil.typeConvert(val, esType);
    // }

    public Object getESDataFromRS(ESMapping mapping, ResultSet resultSet,
                                  Map<String, Object> esFieldData) throws SQLException {
        SchemaItem schemaItem = mapping.getSchemaItem();
        String idFieldName = mapping.get_id() == null ? mapping.getPk() : mapping.get_id();
        Object resultIdVal = null;
        for (FieldItem fieldItem : schemaItem.getSelectFields().values()) {
            String fieldName = Util.cleanColumn(fieldItem.getColumnItems().iterator().next().getColumnName());
            String esType = getEsType(mapping, fieldName);

            Object value = resultSet.getObject(fieldName);
            if (value instanceof Boolean) {
                // 判断es类型
                if (!"boolean".equals(esType)) {
                    value = resultSet.getByte(fieldName);
                }
            }

            if (fieldItem.getFieldName().equals(idFieldName)) {
                resultIdVal = ESSyncUtil.typeConvert(value, esType);
            }

            if (!fieldItem.getFieldName().equals(mapping.get_id()) && !mapping.getSkips().contains(fieldName)) {
                esFieldData.put(fieldName, ESSyncUtil.typeConvert(value, esType));
            }
        }
        return resultIdVal;
    }

    // public Object getIdValFromDmlData(ESMapping mapping, Map<String, Object>
    // dmlData) {
    // SchemaItem schemaItem = mapping.getSchemaItem();
    // String idFieldName = mapping.get_id() == null ? mapping.getPk() :
    // mapping.get_id();
    //
    // for (SchemaItem.FieldItem fieldItem : schemaItem.getSelectFields().values())
    // {
    // if (fieldItem.getFieldName().equals(idFieldName)) {
    // String fieldName =
    // Util.cleanColumn(fieldItem.getColumnItems().iterator().next().getColumnName());
    // String esType = getEsType(mapping, fieldName);
    // Object value = dmlData.get(fieldName);
    // if (value instanceof Byte) {
    // if ("boolean".equals(esType)) {
    // value = ((Byte) value).intValue() != 0;
    // }
    // }
    //
    // return ESSyncUtil.typeConvert(value, esType);
    // }
    // }
    // return null;
    // }

    /**
     * 将dml的data转换为es的data
     *
     * @param mapping 配置mapping
     * @param dmlData dml data
     * @param esFieldData es data
     * @return 返回 id 值
     */
    public Object getESDataFromDmlData(ESMapping mapping, Map<String, Object> dmlData,
                                       Map<String, Object> esFieldData) {
        SchemaItem schemaItem = mapping.getSchemaItem();
        String idFieldName = mapping.get_id() == null ? mapping.getPk() : mapping.get_id();
        Object resultIdVal = null;
        for (FieldItem fieldItem : schemaItem.getSelectFields().values()) {
            String fieldName = Util.cleanColumn(fieldItem.getColumnItems().iterator().next().getColumnName());
            String esType = getEsType(mapping, fieldName);
            Object value = dmlData.get(fieldName);
            if (value instanceof Byte) {
                if ("boolean".equals(esType)) {
                    value = ((Byte) value).intValue() != 0;
                }
            }

            if (fieldItem.getFieldName().equals(idFieldName)) {
                resultIdVal = ESSyncUtil.typeConvert(value, esType);
            }

            if (!fieldItem.getFieldName().equals(mapping.get_id()) && !mapping.getSkips().contains(fieldName)) {
                esFieldData.put(fieldName, ESSyncUtil.typeConvert(value, esType));
            }
        }
        return resultIdVal;
    }

    /**
     * es 字段类型本地缓存
     */
    private static ConcurrentMap<String, Map<String, String>> esFieldTypes = new ConcurrentHashMap<>();

    /**
     * 获取es mapping中的属性类型
     *
     * @param mapping mapping配置
     * @param fieldName 属性名
     * @return 类型
     */
    @SuppressWarnings("unchecked")
    private String getEsType(ESMapping mapping, String fieldName) {
        String key = mapping.get_index() + "-" + mapping.get_type();
        Map<String, String> fieldType = esFieldTypes.get(key);
        if (fieldType == null) {
            ImmutableOpenMap<String, MappingMetaData> mappings;
            try {
                mappings = transportClient.admin()
                    .cluster()
                    .prepareState()
                    .execute()
                    .actionGet()
                    .getState()
                    .getMetaData()
                    .getIndices()
                    .get(mapping.get_index())
                    .getMappings();
            } catch (NullPointerException e) {
                throw new IllegalArgumentException("Not found the mapping info of index: " + mapping.get_index());
            }
            MappingMetaData mappingMetaData = mappings.get(mapping.get_type());
            if (mappingMetaData == null) {
                throw new IllegalArgumentException("Not found the mapping info of index: " + mapping.get_index());
            }

            fieldType = new LinkedHashMap<>();

            Map<String, Object> sourceMap = mappingMetaData.getSourceAsMap();
            Map<String, Object> esMapping = (Map<String, Object>) sourceMap.get("properties");
            for (Map.Entry<String, Object> entry : esMapping.entrySet()) {
                Map<String, Object> value = (Map<String, Object>) entry.getValue();
                if (value.containsKey("properties")) {
                    fieldType.put(entry.getKey(), "object");
                } else {
                    fieldType.put(entry.getKey(), (String) value.get("type"));
                }
            }
            esFieldTypes.put(key, fieldType);
        }

        return fieldType.get(fieldName);
    }
}
