package com.elasticm2m.frameworks.aws.dynamodb;

import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.tuple.Tuple;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.elasticm2m.frameworks.common.base.ElasticBaseRichBolt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.name.Named;

import java.util.List;
import java.util.Map;

public class DynamoDBWriter extends ElasticBaseRichBolt {

    private AWSCredentialsProvider credentialsProvider;
    private DynamoDB dynamoDB;
    private String tableName;
    private boolean logTuple = false;
    private Table table;
    private String primaryKeyProperty;
    private String[] paths;
    private String pkeyName;
    private String rangeKeyProperty;
    private String[] rangePaths;
    private String rangePkeyName;

    @Inject
    public void setTableName(@Named("dynamodb-table-name") String tableName) {
        this.tableName = tableName;
    }

    @Inject
    public void setPrimaryKeyProperty(@Named("primary-key-property") String primaryKeyProperty) {
        this.primaryKeyProperty = primaryKeyProperty;
    }

    @Inject(optional = true)
    public void setRangeKeyProperty(@Named("range-key-property") String rangeKeyProperty) {
        this.rangeKeyProperty = rangeKeyProperty;
    }

    @Inject
    public void setLogTupple(@Named("log-tuple") Boolean logTuple) {
        this.logTuple = logTuple;
    }

    @Override
    public void prepare(Map conf, TopologyContext topologyContext, OutputCollector collector) {
        super.prepare(conf, topologyContext, collector);

        logger.info("DynamoDB Writer: Table Name = " + tableName);

        credentialsProvider = new DefaultAWSCredentialsProviderChain();

        if (credentialsProvider == null) {
            dynamoDB = new DynamoDB(new AmazonDynamoDBClient());
        } else {
            dynamoDB = new DynamoDB(new AmazonDynamoDBClient(credentialsProvider));
        }
        table = dynamoDB.getTable(tableName);
        if ( null == table ) {
            logger.error("Table " + tableName + " does not exist");
//            throw new IllegalStateException("Table " + tableName + " does not exist");
        }
        if ( null == primaryKeyProperty ) {
            logger.error("PrimaryKeyProperty is not specified");
//            throw new IllegalStateException("PrimaryKeyProperty is not specified");
        }
        List<KeySchemaElement> keys = table.describe().getKeySchema();
        paths = primaryKeyProperty.split("\\.");
        pkeyName = paths[ paths.length - 1 ];
        String realKeyName = keys.get(0).getAttributeName();
        if ( !realKeyName.equals(pkeyName) ) {
            String msg = new StringBuilder()
                    .append("Table ").append(tableName)
                    .append(" does not have a primary key named [").append(pkeyName).append("]")
                    .append(" instead is is called [").append(realKeyName).append("]").toString();
            logger.info(msg);
//            throw new IllegalStateException(msg);
        }
        if ( null != rangeKeyProperty ) {
            rangePaths = rangeKeyProperty.split("\\.");
            rangePkeyName = rangePaths[ rangePaths.length - 1 ];
            if (keys.size() < 2 ) {
                logger.info("Range key supplied, but table " + tableName + " does not have a range key");
//                throw new IllegalStateException("Range key supplied, but table " + tableName + " does not have a range key");
            }
            else {
                String realRangeKeyName = keys.get(1).getAttributeName();
                if (!realRangeKeyName.equals(rangePkeyName)) {
                    String msg = new StringBuilder()
                            .append("Table ").append(tableName)
                            .append(" does not have a range key named [").append(rangePkeyName).append("]")
                            .append(" instead is is called [").append(realRangeKeyName).append("]").toString();
                    logger.info(msg);
//                    throw new IllegalStateException(msg);
                }
            }
        }
    }

    protected JsonNode pathFor(JsonNode node, String p[], int idex) {
        if (idex < p.length) {
            return pathFor(node.path(p[idex]), p, ++idex); // recurse
        }
        else {
            return node;
        }
    }

    protected JsonNode pathFor(JsonNode node, String p[]) {
        return pathFor(node, p, 0);
    }

    @Override
    public void execute(Tuple tuple) {
        try {
            String body = tuple.getString(1);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readValue(body, JsonNode.class);
            Item item = rangeKeyProperty != null ?
                    new Item()
                            .withPrimaryKey(pkeyName,  pathFor(root, paths).asText(),
                                    rangePkeyName, pathFor(root, rangePaths).asText())
                            .withJSON("document", body):
                    new Item()
                            .withPrimaryKey(pkeyName,  pathFor(root, paths).asText())
                            .withJSON("document", body);

            table.putItem(item);

            if (logTuple) {
                logger.info(body);
            } else {
                logger.debug("Published record to DynamoDB");
            }

            collector.ack(tuple);
        } catch (Exception ex) {
            logger.error("Error writing the entity to DynamoDB:", ex);
            collector.fail(tuple);
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer ofd) {
    }
}
