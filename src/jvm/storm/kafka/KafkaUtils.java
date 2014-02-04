package storm.kafka;

import backtype.storm.metric.api.IMetric;
import kafka.api.FetchRequest;
import kafka.api.FetchRequestBuilder;
import kafka.api.PartitionOffsetRequestInfo;
import kafka.common.TopicAndPartition;
import kafka.javaapi.FetchResponse;
import kafka.javaapi.OffsetRequest;
import kafka.javaapi.consumer.SimpleConsumer;
import kafka.javaapi.message.ByteBufferMessageSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import storm.kafka.trident.IBrokerReader;
import storm.kafka.trident.StaticBrokerReader;
import storm.kafka.trident.ZkBrokerReader;

import java.net.ConnectException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;


public class KafkaUtils {

    public static final Logger LOG = LoggerFactory.getLogger(KafkaUtils.class);
    private static final int NO_OFFSET = -5;


    public static IBrokerReader makeBrokerReader(Map stormConf, KafkaConfig conf) {
        if (conf.hosts instanceof StaticHosts) {
            return new StaticBrokerReader(((StaticHosts) conf.hosts).getPartitionInformation());
        } else {
            return new ZkBrokerReader(stormConf, conf.topic, (ZkHosts) conf.hosts);
        }
    }

    public static long getOffset(SimpleConsumer consumer, String topic, int partition, long startOffsetTime) {
        TopicAndPartition topicAndPartition = new TopicAndPartition(topic, partition);
        Map<TopicAndPartition, PartitionOffsetRequestInfo> requestInfo = new HashMap<TopicAndPartition, PartitionOffsetRequestInfo>();
        requestInfo.put(topicAndPartition, new PartitionOffsetRequestInfo(startOffsetTime, 1));
        OffsetRequest request = new OffsetRequest(
                requestInfo, kafka.api.OffsetRequest.CurrentVersion(), consumer.clientId());

        long[] offsets = consumer.getOffsetsBefore(request).offsets(topic, partition);
        if (offsets.length > 0) {
            return offsets[0];
        } else {
            return NO_OFFSET;
        }
    }

    public static class KafkaOffsetMetric implements IMetric {
        Map<Partition, Long> _partitionToOffset = new HashMap<Partition, Long>();
        Set<Partition> _partitions;
        String _topic;
        DynamicPartitionConnections _connections;

        public KafkaOffsetMetric(String topic, DynamicPartitionConnections connections) {
            _topic = topic;
            _connections = connections;
        }

        public void setLatestEmittedOffset(Partition partition, long offset) {
            _partitionToOffset.put(partition, offset);
        }

        @Override
        public Object getValueAndReset() {
            try {
                long totalSpoutLag = 0;
                long totalLatestTimeOffset = 0;
                long totalLatestEmittedOffset = 0;
                HashMap ret = new HashMap();
                if (_partitions != null && _partitions.size() == _partitionToOffset.size()) {
                    for (Map.Entry<Partition, Long> e : _partitionToOffset.entrySet()) {
                        Partition partition = e.getKey();
                        SimpleConsumer consumer = _connections.getConnection(partition);
                        if (consumer == null) {
                            LOG.warn("partitionToOffset contains partition not found in _connections. Stale partition data?");
                            return null;
                        }
                        long latestTimeOffset = getOffset(consumer, _topic, partition.partition, kafka.api.OffsetRequest.LatestTime());
                        if (latestTimeOffset == 0) {
                            LOG.warn("No data found in Kafka Partition " + partition.getId());
                            return null;
                        }
                        long latestEmittedOffset = e.getValue();
                        long spoutLag = latestTimeOffset - latestEmittedOffset;
                        ret.put(partition.getId() + "/" + "spoutLag", spoutLag);
                        ret.put(partition.getId() + "/" + "latestTime", latestTimeOffset);
                        ret.put(partition.getId() + "/" + "latestEmittedOffset", latestEmittedOffset);
                        totalSpoutLag += spoutLag;
                        totalLatestTimeOffset += latestTimeOffset;
                        totalLatestEmittedOffset += latestEmittedOffset;
                    }
                    ret.put("totalSpoutLag", totalSpoutLag);
                    ret.put("totalLatestTime", totalLatestTimeOffset);
                    ret.put("totalLatestEmittedOffset", totalLatestEmittedOffset);
                    return ret;
                } else {
                    LOG.info("Metrics Tick: Not enough data to calculate spout lag.");
                }
            } catch (Throwable t) {
                LOG.warn("Metrics Tick: Exception when computing kafkaOffset metric.", t);
            }
            return null;
        }

        public void refreshPartitions(Set<Partition> partitions) {
            _partitions = partitions;
            Iterator<Partition> it = _partitionToOffset.keySet().iterator();
            while (it.hasNext()) {
                if (!partitions.contains(it.next())) {
                    it.remove();
                }
            }
        }
    }

    public static ByteBufferMessageSet fetchMessages(KafkaConfig config, SimpleConsumer consumer, Partition partition, long offset) {
        ByteBufferMessageSet msgs = null;
        String topic = config.topic;
        int partitionId = partition.partition;
        for (int errors = 0; errors < 2 && msgs == null; errors++) {
            FetchRequestBuilder builder = new FetchRequestBuilder();
            FetchRequest fetchRequest = builder.addFetch(topic, partitionId, offset, config.fetchSizeBytes).
                    clientId(config.clientId).build();
            FetchResponse fetchResponse;
            try {
                fetchResponse = consumer.fetch(fetchRequest);
            } catch (Exception e) {
                if (e instanceof ConnectException) {
                    throw new FailedFetchException(e);
                } else {
                    throw new RuntimeException(e);
                }
            }
            if (fetchResponse.hasError()) {
                KafkaError error = KafkaError.getError(fetchResponse.errorCode(topic, partitionId));
                if (error.equals(KafkaError.OFFSET_OUT_OF_RANGE) && config.useStartOffsetTimeIfOffsetOutOfRange && errors == 0) {
                    long startOffset = getOffset(consumer, topic, partitionId, config.startOffsetTime);
                    LOG.warn("Got fetch request with offset out of range: [" + offset + "]; " +
                            "retrying with default start offset time from configuration. " +
                            "configured start offset time: [" + config.startOffsetTime + "] offset: [" + startOffset + "]");
                    offset = startOffset;
                } else {
                    String message = "Error fetching data from [" + partition + "] for topic [" + topic + "]: [" + error + "]";
                    LOG.error(message);
                    throw new FailedFetchException(message);
                }
            } else {
                msgs = fetchResponse.messageSet(topic, partitionId);
            }
        }
        return msgs;
    }
}
