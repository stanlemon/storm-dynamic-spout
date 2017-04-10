package com.salesforce.storm.spout.sideline.kafka;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.salesforce.storm.spout.sideline.FactoryManager;
import com.salesforce.storm.spout.sideline.KafkaMessage;
import com.salesforce.storm.spout.sideline.TupleMessageId;
import com.salesforce.storm.spout.sideline.config.SidelineSpoutConfig;
import com.salesforce.storm.spout.sideline.filter.StaticMessageFilter;
import com.salesforce.storm.spout.sideline.kafka.deserializer.Deserializer;
import com.salesforce.storm.spout.sideline.kafka.deserializer.Utf8StringDeserializer;
import com.salesforce.storm.spout.sideline.kafka.retryManagers.NeverRetryManager;
import com.salesforce.storm.spout.sideline.kafka.retryManagers.RetryManager;
import com.salesforce.storm.spout.sideline.mocks.MockTopologyContext;
import com.salesforce.storm.spout.sideline.persistence.PersistenceAdapter;
import com.salesforce.storm.spout.sideline.trigger.SidelineRequestIdentifier;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.storm.shade.com.google.common.base.Charsets;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Values;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class VirtualSpoutTest {

    /**
     * By default, no exceptions should be thrown.
     */
    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    /**
     * Verify that constructor args get set appropriately.
     */
    @Test
    public void testConstructor() {
        // Create inputs
        final Map expectedTopologyConfig = Maps.newHashMap();
        expectedTopologyConfig.put("Key1", "Value1");
        expectedTopologyConfig.put("Key2", "Value2");
        expectedTopologyConfig.put("Key3", "Value3");

        // Create a mock topology context
        final TopologyContext mockTopologyContext = new MockTopologyContext();

        // Create a factory manager
        final FactoryManager factoryManager = new FactoryManager(expectedTopologyConfig);

        // Create spout
        VirtualSpout virtualSpout = new VirtualSpout(expectedTopologyConfig, mockTopologyContext, factoryManager);

        // Verify things got set
        assertNotNull("TopologyConfig should be non-null", virtualSpout.getTopologyConfig());
        assertNotNull("TopologyContext should be non-null", virtualSpout.getTopologyContext());

        // Verify the config is correct (and not some empty map)
        assertEquals("Should have correct number of entries", expectedTopologyConfig.size(), virtualSpout.getTopologyConfig().size());
        assertEquals("Should have correct entries", expectedTopologyConfig, virtualSpout.getTopologyConfig());

        // Verify factory manager set
        assertNotNull("Should have non-null factory manager", virtualSpout.getFactoryManager());
        assertEquals("Should be our instance passed in", factoryManager, virtualSpout.getFactoryManager());

        // Verify the config is immutable and throws exception when you try to modify it
        expectedException.expect(UnsupportedOperationException.class);
        virtualSpout.getTopologyConfig().put("MyKey", "MyValue");
    }

    /**
     * Verify that getTopologyConfigItem() works as expected
     */
    @Test
    public void testGetTopologyConfigItem() {
        // Create inputs
        final Map expectedTopologyConfig = Maps.newHashMap();
        expectedTopologyConfig.put("Key1", "Value1");
        expectedTopologyConfig.put("Key2", "Value2");
        expectedTopologyConfig.put("Key3", "Value3");

        // Create a mock topology context
        final TopologyContext mockTopologyContext = new MockTopologyContext();

        // Create a factory manager
        final FactoryManager factoryManager = new FactoryManager(expectedTopologyConfig);

        // Create spout
        VirtualSpout virtualSpout = new VirtualSpout(expectedTopologyConfig, mockTopologyContext, factoryManager);

        // Verify things got set
        assertNotNull("TopologyConfig should be non-null", virtualSpout.getTopologyConfig());

        // Verify the config is correct (and not some empty map)
        assertEquals("Should have correct number of entries", expectedTopologyConfig.size(), virtualSpout.getTopologyConfig().size());
        assertEquals("Should have correct entries", expectedTopologyConfig, virtualSpout.getTopologyConfig());

        // Check each item
        assertEquals("Value1", virtualSpout.getTopologyConfigItem("Key1"));
        assertEquals("Value2", virtualSpout.getTopologyConfigItem("Key2"));
        assertEquals("Value3", virtualSpout.getTopologyConfigItem("Key3"));

        // Check a random key that doesn't exist
        assertNull(virtualSpout.getTopologyConfigItem("Random Key"));
    }

    /**
     * Test setter and getter
     * Note - Setter may go away in liu of being set by the topologyConfig.  getVirtualSpoutId() should remain tho.
     */
    @Test
    public void testSetAndGetConsumerId() {
        // Define input
        final String expectedConsumerId = "myConsumerId";

        // Create spout
        VirtualSpout virtualSpout = new VirtualSpout(Maps.newHashMap(), new MockTopologyContext(), new FactoryManager(Maps.newHashMap()));

        // Set it
        virtualSpout.setVirtualSpoutId(expectedConsumerId);

        // Verify it
        assertEquals("Got expected consumer id", expectedConsumerId, virtualSpout.getVirtualSpoutId());
    }

    /**
     * Test setter and getter
     */
    @Test
    public void testSetAndGetSidelineRequestId() {
        // Define input
        final SidelineRequestIdentifier expectedId = new SidelineRequestIdentifier();

        // Create spout
        VirtualSpout virtualSpout = new VirtualSpout(Maps.newHashMap(), new MockTopologyContext(), new FactoryManager(Maps.newHashMap()));

        // Defaults null
        assertNull("should be null", virtualSpout.getSidelineRequestIdentifier());

        // Set it
        virtualSpout.setSidelineRequestIdentifier(expectedId);

        // Verify it
        assertEquals("Got expected requext id", expectedId, virtualSpout.getSidelineRequestIdentifier());
    }

    /**
     * Test setter and getter.
     */
    @Test
    public void testSetAndGetStopRequested() {
        // Create spout
        VirtualSpout virtualSpout = new VirtualSpout(Maps.newHashMap(), new MockTopologyContext(), new FactoryManager(Maps.newHashMap()));

        // Should default to false
        assertFalse("Should default to false", virtualSpout.isStopRequested());

        // Set to true
        virtualSpout.requestStop();
        assertTrue("Should be true", virtualSpout.isStopRequested());
    }

    /**
     * Calling open() more than once should throw an exception.
     */
    @Test
    public void testCallingOpenTwiceThrowsException() {
        // Create test config
        final Map topologyConfig = getDefaultConfig();

        // Create mock topology context
        final TopologyContext mockTopologyContext = new MockTopologyContext();

        final List<PartitionInfo> partitions = Collections.singletonList(new PartitionInfo("foobar", 0, new Node(1, "localhost", 1234), new Node[]{}, new Node[]{}));

        // Create a mock SidelineConsumer
        final Consumer mockConsumer = mock(Consumer.class);

        // Create factory manager
        final FactoryManager factoryManager = new FactoryManager(topologyConfig);

        // Create spout
        final VirtualSpout virtualSpout = new VirtualSpout(topologyConfig, mockTopologyContext, factoryManager, mockConsumer, null, null);
        virtualSpout.setVirtualSpoutId("MyConsumerId");

        // Call it once.
        virtualSpout.open();

        // Validate that open() on SidelineConsumer is called once.
        verify(mockConsumer, times(1)).open(eq(null));

        // Set expected exception
        expectedException.expect(IllegalStateException.class);
        virtualSpout.open();
    }

    /**
     * Validate that Open behaves like we expect.
     */
    @Test
    public void testOpen() {
        // Create test config
        final Map topologyConfig = getDefaultConfig();

        // Create mock topology context
        final TopologyContext mockTopologyContext = new MockTopologyContext();

        final List<PartitionInfo> partitions = Collections.singletonList(new PartitionInfo("foobar", 0, new Node(1, "localhost", 1234), new Node[]{}, new Node[]{}));

        // Create a mock SidelineConsumer
        final Consumer mockConsumer = mock(Consumer.class);

        // Create a mock Deserializer
        Deserializer mockDeserializer = mock(Deserializer.class);
        RetryManager mockRetryManager = mock(RetryManager.class);

        // Create factory manager
        final FactoryManager mockFactoryManager = createMockFactoryManager(mockDeserializer, mockRetryManager);

        // Create spout
        final VirtualSpout virtualSpout = new VirtualSpout(topologyConfig, mockTopologyContext, mockFactoryManager, mockConsumer, null, null);
        virtualSpout.setVirtualSpoutId("MyConsumerId");

        // Call open
        virtualSpout.open();

        // Validate that we asked factory manager for a deserializer
        verify(mockFactoryManager, times(1)).createNewDeserializerInstance();

        // Validate that we asked factory manager for a failed msg retry manager
        verify(mockFactoryManager, times(1)).createNewFailedMsgRetryManagerInstance();

        // Validate we called open on the RetryManager
        verify(mockRetryManager, times(1)).open(topologyConfig);

        // Validate that open() on SidelineConsumer is called once.
        verify(mockConsumer, times(1)).open(eq(null));
    }

    /**
     * Tests when you call nextTuple() and the underlying consumer.nextRecord() returns null,
     * then nextTuple() should also return null.
     */
    @Test
    public void testNextTupleWhenConsumerReturnsNull() {
        // Define some inputs
        final ConsumerRecord<byte[], byte[]> expectedConsumerRecord = null;

        // Create test config
        final Map topologyConfig = getDefaultConfig();

        // Create topology context
        final TopologyContext mockTopologyContext = new MockTopologyContext();

        // Create a mock SidelineConsumer
        final Consumer mockConsumer = mock(Consumer.class);

        // Create factory manager
        final FactoryManager factoryManager = new FactoryManager(topologyConfig);

        // When nextRecord() is called on the mockSidelineConsumer, we need to return a value
        when(mockConsumer.nextRecord()).thenReturn(expectedConsumerRecord);

        // Create spout & open
        VirtualSpout virtualSpout = new VirtualSpout(topologyConfig, mockTopologyContext, factoryManager, mockConsumer, null, null);
        virtualSpout.setVirtualSpoutId("MyConsumerId");
        virtualSpout.open();

        // Call nextTuple()
        KafkaMessage result = virtualSpout.nextTuple();

        // Verify its null
        assertNull("Should be null",  result);

        // Verify ack is never called on underlying mock sideline consumer
        verify(mockConsumer, never()).commitOffset(any(), anyLong());
        verify(mockConsumer, never()).commitOffset(any());
    }

    /**
     * Tests what happens when you call nextTuple(), and the underlying deserializer fails to
     * deserialize (returns null), then nextTuple() should return null.
     */
    @Test
    public void testNextTupleWhenSerializerFailsToDeserialize() {
        // Define a deserializer that always returns null
        final Deserializer nullDeserializer = new Deserializer() {
            @Override
            public Values deserialize(String topic, int partition, long offset, byte[] key, byte[] value) {
                return null;
            }

            @Override
            public Fields getOutputFields() {
                return new Fields();
            }
        };

        // Define some inputs
        final String expectedTopic = "MyTopic";
        final int expectedPartition = 3;
        final long expectedOffset = 434323L;
        final String expectedKey = "MyKey";
        final String expectedValue = "MyValue";
        final byte[] expectedKeyBytes = expectedKey.getBytes(Charsets.UTF_8);
        final byte[] expectedValueBytes = expectedValue.getBytes(Charsets.UTF_8);
        final ConsumerRecord<byte[], byte[]> expectedConsumerRecord = new ConsumerRecord<>(expectedTopic, expectedPartition, expectedOffset, expectedKeyBytes, expectedValueBytes);

        // Create test config
        final Map topologyConfig = getDefaultConfig();

        // Create topology context
        final TopologyContext mockTopologyContext = new MockTopologyContext();

        // Create factory manager
        final FactoryManager mockFactoryManager = createMockFactoryManager(nullDeserializer, null);

        // Create a mock SidelineConsumer
        final Consumer mockConsumer = mock(Consumer.class);
        when(mockConsumer.getCurrentState()).thenReturn(ConsumerState.builder().build());

        // When nextRecord() is called on the mockSidelineConsumer, we need to return a value
        when(mockConsumer.nextRecord()).thenReturn(expectedConsumerRecord);

        // Create spout & open
        VirtualSpout virtualSpout = new VirtualSpout(topologyConfig, mockTopologyContext, mockFactoryManager, mockConsumer, null, null);
        virtualSpout.setVirtualSpoutId("MyConsumerId");
        virtualSpout.open();

        // Call nextTuple()
        KafkaMessage result = virtualSpout.nextTuple();

        // Verify its null
        assertNull("Should be null",  result);

        // Verify ack was called on the tuple
        verify(mockConsumer, times(1)).commitOffset(new TopicPartition(expectedTopic, expectedPartition), expectedOffset);
    }

    /**
     * Validates what happens when a message is pulled from the underlying kafka consumer, but it is filtered
     * out by the filter chain.  nextTuple() should return null.
     */
    @Test
    public void testNextTupleReturnsNullWhenFiltered() {
        // Define some inputs
        final String expectedTopic = "MyTopic";
        final int expectedPartition = 3;
        final long expectedOffset = 434323L;
        final String expectedConsumerId = "MyConsumerId";
        final String expectedKey = "MyKey";
        final String expectedValue = "MyValue";
        final byte[] expectedKeyBytes = expectedKey.getBytes(Charsets.UTF_8);
        final byte[] expectedValueBytes = expectedValue.getBytes(Charsets.UTF_8);
        final ConsumerRecord<byte[], byte[]> expectedConsumerRecord = new ConsumerRecord<>(expectedTopic, expectedPartition, expectedOffset, expectedKeyBytes, expectedValueBytes);

        // Create test config
        final Map topologyConfig = getDefaultConfig();

        // Create topology context
        final TopologyContext mockTopologyContext = new MockTopologyContext();

        // Create factory manager
        final FactoryManager factoryManager = new FactoryManager(topologyConfig);

        // Create a mock SidelineConsumer
        final Consumer mockConsumer = mock(Consumer.class);
        when(mockConsumer.getCurrentState()).thenReturn(ConsumerState.builder().build());

        // When nextRecord() is called on the mockSidelineConsumer, we need to return a value
        when(mockConsumer.nextRecord()).thenReturn(expectedConsumerRecord);

        final StaticMessageFilter filterStep = new StaticMessageFilter();

        // Create spout & open
        VirtualSpout virtualSpout = new VirtualSpout(
            topologyConfig,
            mockTopologyContext,
            factoryManager,
            mockConsumer,
            null, null
        );
        virtualSpout.getFilterChain().addStep(new SidelineRequestIdentifier(), filterStep);
        virtualSpout.setVirtualSpoutId(expectedConsumerId);
        virtualSpout.open();

        // Call nextTuple()
        KafkaMessage result = virtualSpout.nextTuple();

        // Check result
        assertNull("Should be null", result);

        // Verify ack was called on the tuple
        verify(mockConsumer, times(1)).commitOffset(new TopicPartition(expectedTopic, expectedPartition), expectedOffset);
    }

    /**
     * Validate what happens if everything works as expected, its deserialized properly, its not filtered.
     */
    @Test
    public void testNextTuple() {
        // Define some inputs
        final String expectedTopic = "MyTopic";
        final int expectedPartition = 3;
        final long expectedOffset = 434323L;
        final String expectedConsumerId = "MyConsumerId";
        final String expectedKey = "MyKey";
        final String expectedValue = "MyValue";
        final byte[] expectedKeyBytes = expectedKey.getBytes(Charsets.UTF_8);
        final byte[] expectedValueBytes = expectedValue.getBytes(Charsets.UTF_8);
        final ConsumerRecord<byte[], byte[]> expectedConsumerRecord = new ConsumerRecord<>(expectedTopic, expectedPartition, expectedOffset, expectedKeyBytes, expectedValueBytes);

        // Define expected result
        final KafkaMessage expectedKafkaMessage = new KafkaMessage(new TupleMessageId(expectedTopic, expectedPartition, expectedOffset, expectedConsumerId), new Values(expectedKey, expectedValue));

        // Create test config
        final Map topologyConfig = getDefaultConfig();

        // Create topology context
        final TopologyContext mockTopologyContext = new MockTopologyContext();

        // Create factory manager
        final FactoryManager factoryManager = new FactoryManager(topologyConfig);

        // Create a mock SidelineConsumer
        Consumer mockConsumer = mock(Consumer.class);

        // When nextRecord() is called on the mockSidelineConsumer, we need to return a value
        when(mockConsumer.nextRecord()).thenReturn(expectedConsumerRecord);

        // Create spout & open
        VirtualSpout virtualSpout = new VirtualSpout(
            topologyConfig,
            mockTopologyContext,
            factoryManager,
            mockConsumer,
            null, null);
        virtualSpout.setVirtualSpoutId(expectedConsumerId);
        virtualSpout.open();

        // Call nextTuple()
        KafkaMessage result = virtualSpout.nextTuple();

        // Check result
        assertNotNull("Should not be null", result);

        // Validate it
        assertEquals("Found expected topic", expectedTopic, result.getTopic());
        assertEquals("Found expected partition", expectedPartition, result.getPartition());
        assertEquals("Found expected offset", expectedOffset, result.getOffset());
        assertEquals("Found expected values", new Values(expectedKey, expectedValue), result.getValues());
        assertEquals("Got expected KafkaMessage", expectedKafkaMessage, result);
    }

    /**
     * 1. publish a bunch of messages to a topic with a single partition.
     * 2. create a VirtualSideLineSpout where we explicitly define an ending offset less than the total msgs published
     * 3. Consume from the Spout (call nextTuple())
     * 4. Ensure that we stop getting tuples back after we exceed the ending state offset.
     */
    @Test
    public void testNextTupleIgnoresMessagesThatHaveExceededEndingStatePositionSinglePartition() {
        // Define some variables
        final long endingOffset = 4444L;
        final int partition = 4;
        final String topic = "MyTopic";

        // Define before offset
        final long beforeOffset = (endingOffset - 100);
        final long afterOffset = (endingOffset + 100);

        // Create a ConsumerRecord who's offset is BEFORE the ending offset, this should pass
        final ConsumerRecord<byte[], byte[]> consumerRecordBeforeEnd = new ConsumerRecord<>(topic, partition, beforeOffset, "before-key".getBytes(Charsets.UTF_8), "before-value".getBytes(Charsets.UTF_8));

        // This ConsumerRecord is EQUAL to the limit, and thus should pass.
        final ConsumerRecord<byte[], byte[]> consumerRecordEqualEnd = new ConsumerRecord<>(topic, partition, endingOffset, "equal-key".getBytes(Charsets.UTF_8), "equal-value".getBytes(Charsets.UTF_8));

        // These two should exceed the limit (since its >) and nothing should be returned.
        final ConsumerRecord<byte[], byte[]> consumerRecordAfterEnd = new ConsumerRecord<>(topic, partition, afterOffset, "after-key".getBytes(Charsets.UTF_8), "after-value".getBytes(Charsets.UTF_8));
        final ConsumerRecord<byte[], byte[]> consumerRecordAfterEnd2 = new ConsumerRecord<>(topic, partition, afterOffset + 1, "after-key2".getBytes(Charsets.UTF_8), "after-value2".getBytes(Charsets.UTF_8));

        // Define expected results returned
        final KafkaMessage expectedKafkaMessageBeforeEndingOffset = new KafkaMessage(new TupleMessageId(topic, partition, beforeOffset, "ConsumerId"), new Values("before-key", "before-value"));
        final KafkaMessage expectedKafkaMessageEqualEndingOffset = new KafkaMessage(new TupleMessageId(topic, partition, endingOffset, "ConsumerId"), new Values("equal-key", "equal-value"));

        // Defining our Ending State
        final ConsumerState endingState = ConsumerState.builder()
            .withPartition(new TopicPartition(topic, partition), endingOffset)
            .build();

        // Create test config
        final Map topologyConfig = getDefaultConfig();

        // Create topology context
        final TopologyContext mockTopologyContext = new MockTopologyContext();

        // Create factory manager
        final FactoryManager factoryManager = new FactoryManager(topologyConfig);

        // Create a mock SidelineConsumer
        Consumer mockConsumer = mock(Consumer.class);
        when(mockConsumer.getCurrentState()).thenReturn(ConsumerState.builder().build());

        // When nextRecord() is called on the mockSidelineConsumer, we need to return our values in order.
        when(mockConsumer.nextRecord()).thenReturn(consumerRecordBeforeEnd, consumerRecordEqualEnd, consumerRecordAfterEnd, consumerRecordAfterEnd2);

        // Create spout & open
        VirtualSpout virtualSpout = new VirtualSpout(
            topologyConfig,
            mockTopologyContext,
            factoryManager,
            mockConsumer,
            null, endingState);
        virtualSpout.setVirtualSpoutId("ConsumerId");
        virtualSpout.open();

        // Call nextTuple(), this should return our entry BEFORE the ending offset
        KafkaMessage result = virtualSpout.nextTuple();

        // Check result
        assertNotNull("Should not be null because the offset is under the limit.", result);

        // Validate it
        assertEquals("Found expected topic", topic, result.getTopic());
        assertEquals("Found expected partition", partition, result.getPartition());
        assertEquals("Found expected offset", beforeOffset, result.getOffset());
        assertEquals("Found expected values", new Values("before-key", "before-value"), result.getValues());
        assertEquals("Got expected KafkaMessage", expectedKafkaMessageBeforeEndingOffset, result);

        // Call nextTuple(), this offset should be equal to our ending offset
        // Equal to the end offset should still get emitted.
        result = virtualSpout.nextTuple();

        // Check result
        assertNotNull("Should not be null because the offset is under the limit.", result);

        // Validate it
        assertEquals("Found expected topic", topic, result.getTopic());
        assertEquals("Found expected partition", partition, result.getPartition());
        assertEquals("Found expected offset", endingOffset, result.getOffset());
        assertEquals("Found expected values", new Values("equal-key", "equal-value"), result.getValues());
        assertEquals("Got expected KafkaMessage", expectedKafkaMessageEqualEndingOffset, result);

        // Call nextTuple(), this offset should be greater than our ending offset
        // and thus should return null.
        result = virtualSpout.nextTuple();

        // Check result
        assertNull("Should be null because the offset is greater than the limit.", result);

        // Call nextTuple(), again the offset should be greater than our ending offset
        // and thus should return null.
        result = virtualSpout.nextTuple();

        // Check result
        assertNull("Should be null because the offset is greater than the limit.", result);

        // Validate unsubscribed was called on our mock sidelineConsumer
        // Right now this is called twice... unsure if that is an issue. I don't think it is.
        verify(mockConsumer, times(2)).unsubscribeTopicPartition(eq(new TopicPartition(topic, partition)));

        // Validate that we never called ack on the tuples that were filtered because they exceeded the max offset
        verify(mockConsumer, times(0)).commitOffset(new TopicPartition(topic, partition), afterOffset);
        verify(mockConsumer, times(0)).commitOffset(new TopicPartition(topic, partition), afterOffset + 1);
    }

    /**
     * This test does the following:
     *
     * 1. Call nextTuple() -
     *  a. the first time RetryManager should return null, saying it has no failed tuples to replay
     *  b. sideline consumer should return a consumer record, and it should be returned by nextTuple()
     * 2. Call fail() with the message previously returned from nextTuple().
     * 2. Call nextTuple()
     *  a. This time RetryManager should return the failed tuple
     * 3. Call nextTuple()
     *  a. This time RetryManager should return null, saying it has no failed tuples to replay.
     *  b. sideline consumer should return a new consumer record.
     */
    @Test
    public void testCallingFailOnTupleWhenItShouldBeRetriedItGetsRetried() {
        // This is the record coming from the consumer.
        final String expectedTopic = "MyTopic";
        final int expectedPartition = 3;
        final long expectedOffset = 434323L;
        final String expectedConsumerId = "MyConsumerId";
        final String expectedKey = "MyKey";
        final String expectedValue = "MyValue";
        final byte[] expectedKeyBytes = expectedKey.getBytes(Charsets.UTF_8);
        final byte[] expectedValueBytes = expectedValue.getBytes(Charsets.UTF_8);
        final ConsumerRecord<byte[], byte[]> expectedConsumerRecord = new ConsumerRecord<>(expectedTopic, expectedPartition, expectedOffset, expectedKeyBytes, expectedValueBytes);

        // Define expected result
        final KafkaMessage expectedKafkaMessage = new KafkaMessage(new TupleMessageId(expectedTopic, expectedPartition, expectedOffset, expectedConsumerId), new Values(expectedKey, expectedValue));

        // This is a second record coming from the consumer
        final long unexpectedOffset = expectedOffset + 2L;
        final String unexpectedKey = "NotMyKey";
        final String unexpectedValue = "NotMyValue";
        final byte[] unexpectedKeyBytes = unexpectedKey.getBytes(Charsets.UTF_8);
        final byte[] unexpectedValueBytes = unexpectedValue.getBytes(Charsets.UTF_8);
        final ConsumerRecord<byte[], byte[]> unexpectedConsumerRecord = new ConsumerRecord<>(expectedTopic, expectedPartition, unexpectedOffset, unexpectedKeyBytes, unexpectedValueBytes);

        // Define unexpected result
        final KafkaMessage unexpectedKafkaMessage = new KafkaMessage(new TupleMessageId(expectedTopic, expectedPartition, unexpectedOffset, expectedConsumerId), new Values(unexpectedKey, unexpectedValue));

        final Map topologyConfig = getDefaultConfig();

        // Create topology context
        final TopologyContext mockTopologyContext = new MockTopologyContext();

        // Create a mock SidelineConsumer
        Consumer mockConsumer = mock(Consumer.class);

        // When nextRecord() is called on the mockSidelineConsumer, we need to return a value
        when(mockConsumer.nextRecord()).thenReturn(expectedConsumerRecord, unexpectedConsumerRecord);

        // Create a mock RetryManager
        RetryManager mockRetryManager = mock(RetryManager.class);

        // First time its called, it should return null.
        // The 2nd time it should return our tuple Id.
        // The 3rd time it should return null again.
        when(mockRetryManager.nextFailedMessageToRetry()).thenReturn(null, expectedKafkaMessage.getTupleMessageId(), null);

        // Create factory manager
        final FactoryManager mockFactoryManager = createMockFactoryManager(null, mockRetryManager);

        // Create spout & open
        VirtualSpout virtualSpout = new VirtualSpout(
            topologyConfig,
            mockTopologyContext,
            mockFactoryManager,
            mockConsumer,
            null, null);
        virtualSpout.setVirtualSpoutId(expectedConsumerId);
        virtualSpout.open();

        // Call nextTuple()
        KafkaMessage result = virtualSpout.nextTuple();

        // Verify we asked the failed message manager, but got nothing back
        verify(mockRetryManager, times(1)).nextFailedMessageToRetry();

        // Check result
        assertNotNull("Should not be null", result);

        // Validate it
        assertEquals("Found expected topic", expectedTopic, result.getTopic());
        assertEquals("Found expected partition", expectedPartition, result.getPartition());
        assertEquals("Found expected offset", expectedOffset, result.getOffset());
        assertEquals("Found expected values", new Values(expectedKey, expectedValue), result.getValues());
        assertEquals("Got expected KafkaMessage", expectedKafkaMessage, result);

        // Now call fail on this
        final TupleMessageId failedMessageId = result.getTupleMessageId();

        // Retry manager should retry this tuple.
        when(mockRetryManager.retryFurther(failedMessageId)).thenReturn(true);

        // failed on retry manager shouldn't have been called yet
        verify(mockRetryManager, never()).failed(anyObject());

        // call fail on our message id
        virtualSpout.fail(failedMessageId);

        // Verify failed calls
        verify(mockRetryManager, times(1)).failed(failedMessageId);

        // Call nextTuple, we should get our failed tuple back.
        result = virtualSpout.nextTuple();

        // verify we got the tuple from failed manager
        verify(mockRetryManager, times(2)).nextFailedMessageToRetry();

        // Check result
        assertNotNull("Should not be null", result);

        // Validate it
        assertEquals("Found expected topic", expectedTopic, result.getTopic());
        assertEquals("Found expected partition", expectedPartition, result.getPartition());
        assertEquals("Found expected offset", expectedOffset, result.getOffset());
        assertEquals("Found expected values", new Values(expectedKey, expectedValue), result.getValues());
        assertEquals("Got expected KafkaMessage", expectedKafkaMessage, result);

        // And call nextTuple() one more time, this time failed manager should return null
        // and sideline consumer returns our unexpected result
        // Call nextTuple, we should get our failed tuple back.
        result = virtualSpout.nextTuple();

        // verify we got the tuple from failed manager
        verify(mockRetryManager, times(3)).nextFailedMessageToRetry();

        // Check result
        assertNotNull("Should not be null", result);

        // Validate it
        assertEquals("Found expected topic", expectedTopic, result.getTopic());
        assertEquals("Found expected partition", expectedPartition, result.getPartition());
        assertEquals("Found expected offset", unexpectedOffset, result.getOffset());
        assertEquals("Found expected values", new Values(unexpectedKey, unexpectedValue), result.getValues());
        assertEquals("Got expected KafkaMessage", unexpectedKafkaMessage, result);
    }

    /**
     * Test calling fail with null, it should just silently drop it.
     */
    @Test
    public void testFailWithNull() {
        // Create mock failed msg retry manager
        final RetryManager mockRetryManager = mock(RetryManager.class);

        // Create test config
        final Map topologyConfig = getDefaultConfig();

        // Create topology context
        final TopologyContext mockTopologyContext = new MockTopologyContext();

        // Create factory manager
        final FactoryManager mockFactoryManager = createMockFactoryManager(null, mockRetryManager);

        // Create a mock SidelineConsumer
        Consumer mockConsumer = mock(Consumer.class);

        // Create spout
        VirtualSpout virtualSpout = new VirtualSpout(
            topologyConfig,
            mockTopologyContext,
            mockFactoryManager,
            mockConsumer,
            null, null);
        virtualSpout.setVirtualSpoutId("MyConsumerId");
        virtualSpout.open();

        // Call ack with null, nothing should explode.
        virtualSpout.fail(null);

        // No interactions w/ our mocks
        verify(mockRetryManager, never()).retryFurther(anyObject());
        verify(mockRetryManager, never()).acked(anyObject());
        verify(mockRetryManager, never()).failed(anyObject());
        verify(mockConsumer, never()).commitOffset(anyObject(), anyLong());
    }

    /**
     * Call fail() with invalid msg type should throw an exception.
     */
    @Test
    public void testFailWithInvalidMsgIdObject() {
        // Create test config
        final Map topologyConfig = getDefaultConfig();

        // Create topology context
        final TopologyContext mockTopologyContext = new MockTopologyContext();

        // Create factory manager
        final FactoryManager factoryManager = new FactoryManager(topologyConfig);

        // Create a mock SidelineConsumer
        Consumer mockConsumer = mock(Consumer.class);

        // Create spout
        VirtualSpout virtualSpout = new VirtualSpout(
            topologyConfig,
            mockTopologyContext,
            factoryManager,
            mockConsumer,
            null, null);
        virtualSpout.setVirtualSpoutId("MyConsumerId");
        virtualSpout.open();

        // Call ack with a string object, it should throw an exception.
        expectedException.expect(IllegalArgumentException.class);
        virtualSpout.fail("This is a String!");
    }

    /**
     * Test calling ack with null, it should just silently drop it.
     */
    @Test
    public void testAckWithNull() {
        // Create mock Failed msg retry manager
        final RetryManager mockRetryManager = mock(RetryManager.class);

        // Create test config
        final Map topologyConfig = getDefaultConfig();

        // Create topology context
        final TopologyContext mockTopologyContext = new MockTopologyContext();

        // Create factory manager
        final FactoryManager mockFactoryManager = createMockFactoryManager(null, mockRetryManager);

        // Create a mock SidelineConsumer
        Consumer mockConsumer = mock(Consumer.class);

        // Create spout
        VirtualSpout virtualSpout = new VirtualSpout(
            topologyConfig,
            mockTopologyContext,
            mockFactoryManager,
            mockConsumer,
            null, null);
        virtualSpout.setVirtualSpoutId("MyConsumerId");
        virtualSpout.open();

        // Call ack with null, nothing should explode.
        virtualSpout.ack(null);

        // No interactions w/ our mock sideline consumer for committing offsets
        verify(mockConsumer, never()).commitOffset(any(TopicPartition.class), anyLong());
        verify(mockConsumer, never()).commitOffset(any(ConsumerRecord.class));
        verify(mockRetryManager, never()).acked(anyObject());
    }

    /**
     * Call ack() with invalid msg type should throw an exception.
     */
    @Test
    public void testAckWithInvalidMsgIdObject() {
        // Create test config
        final Map topologyConfig = getDefaultConfig();

        // Create topology context
        final TopologyContext mockTopologyContext = new MockTopologyContext();

        // Create factory manager
        final FactoryManager factoryManager = new FactoryManager(topologyConfig);

        // Create a mock SidelineConsumer
        Consumer mockConsumer = mock(Consumer.class);

        // Create spout
        VirtualSpout virtualSpout = new VirtualSpout(
            topologyConfig,
            mockTopologyContext,
            factoryManager,
            mockConsumer,
            null, null);
        virtualSpout.setVirtualSpoutId("MyConsumerId");
        virtualSpout.open();

        // Call ack with a string object, it should throw an exception.
        expectedException.expect(IllegalArgumentException.class);
        virtualSpout.ack("This is my String!");
    }

    /**
     * Test calling ack, ensure it passes the commit command to its internal consumer
     */
    @Test
    public void testAck() {
        // Define our msgId
        final String expectedTopicName = "MyTopic";
        final int expectedPartitionId = 33;
        final long expectedOffset = 313376L;
        final TupleMessageId tupleMessageId = new TupleMessageId(expectedTopicName, expectedPartitionId, expectedOffset, "RandomConsumer");

        // Create inputs
        final Map topologyConfig = getDefaultConfig();
        final TopologyContext mockTopologyContext = new MockTopologyContext();
        final RetryManager mockRetryManager = mock(RetryManager.class);

        // Create factory manager
        final FactoryManager mockFactoryManager = createMockFactoryManager(null, mockRetryManager);

        // Create a mock SidelineConsumer
        Consumer mockConsumer = mock(Consumer.class);
        when(mockConsumer.getCurrentState()).thenReturn(ConsumerState.builder().build());

        // Create spout
        VirtualSpout virtualSpout = new VirtualSpout(
            topologyConfig,
            mockTopologyContext,
            mockFactoryManager,
            mockConsumer,
            null, null);
        virtualSpout.setVirtualSpoutId("MyConsumerId");
        virtualSpout.open();

        // Never called yet
        verify(mockConsumer, never()).commitOffset(anyObject(), anyLong());
        verify(mockRetryManager, never()).acked(anyObject());

        // Call ack with a string object, it should throw an exception.
        virtualSpout.ack(tupleMessageId);

        // Verify mock gets called with appropriate arguments
        verify(mockConsumer, times(1)).commitOffset(eq(new TopicPartition(expectedTopicName, expectedPartitionId)), eq(expectedOffset));

        // Gets acked on the failed retry manager
        verify(mockRetryManager, times(1)).acked(tupleMessageId);
    }

    /**
     * Test calling this method when no defined endingState.  It should default to always
     * return false in that case.
     */
    @Test
    public void testDoesMessageExceedEndingOffsetWithNoEndingStateDefined() {
        // Create test config
        final Map topologyConfig = getDefaultConfig();

        // Create topology context
        final TopologyContext mockTopologyContext = new MockTopologyContext();

        // Create factory manager
        final FactoryManager factoryManager = new FactoryManager(topologyConfig);

        // Create a mock SidelineConsumer
        Consumer mockConsumer = mock(Consumer.class);

        // Create spout
        VirtualSpout virtualSpout = new VirtualSpout(
            topologyConfig,
            mockTopologyContext,
            factoryManager,
            mockConsumer,
            null, null);
        virtualSpout.setVirtualSpoutId("MyConsumerId");
        virtualSpout.open();

        // Create our test TupleMessageId
        final String expectedTopic = "MyTopic";
        final int expectedPartition = 1;
        final long expectedOffset = 31332L;
        final String consumerId = "MyConsumerId";
        final TupleMessageId tupleMessageId = new TupleMessageId(expectedTopic, expectedPartition, expectedOffset, consumerId);

        // Call our method & validate.
        final boolean result = virtualSpout.doesMessageExceedEndingOffset(tupleMessageId);
        assertFalse("Should always be false", result);
    }

    /**
     * Test calling this method with a defined endingState, and the TupleMessageId's offset is equal to it,
     * it should return false.
     */
    @Test
    public void testDoesMessageExceedEndingOffsetWhenItEqualsEndingOffset() {
        // Create inputs
        final Map topologyConfig = getDefaultConfig();
        final TopologyContext mockTopologyContext = new MockTopologyContext();
        final Consumer mockConsumer = mock(Consumer.class);

        // Create our test TupleMessageId
        final String expectedTopic = "MyTopic";
        final int expectedPartition = 1;
        final long expectedOffset = 31332L;
        final String consumerId = "MyConsumerId";
        final TupleMessageId tupleMessageId = new TupleMessageId(expectedTopic, expectedPartition, expectedOffset, consumerId);

        // Define our endingState with a position equal to our TupleMessageId
        final ConsumerState endingState = ConsumerState.builder()
            .withPartition(new TopicPartition(expectedTopic, expectedPartition), expectedOffset)
            .build();

        // Create factory manager
        final FactoryManager factoryManager = new FactoryManager(topologyConfig);

        // Create spout passing in ending state.
        VirtualSpout virtualSpout = new VirtualSpout(
            topologyConfig,
            mockTopologyContext,
            factoryManager,
            mockConsumer,
            null, endingState);
        virtualSpout.setVirtualSpoutId("MyConsumerId");
        virtualSpout.open();

        // Call our method & validate.
        final boolean result = virtualSpout.doesMessageExceedEndingOffset(tupleMessageId);
        assertFalse("Should be false", result);
    }

    /**
     * Test calling this method with a defined endingState, and the TupleMessageId's offset is beyond it.
     */
    @Test
    public void testDoesMessageExceedEndingOffsetWhenItDoesExceedEndingOffset() {
        // Create inputs
        final Map topologyConfig = getDefaultConfig();
        final TopologyContext mockTopologyContext = new MockTopologyContext();
        final Consumer mockConsumer = mock(Consumer.class);

        // Create our test TupleMessageId
        final String expectedTopic = "MyTopic";
        final int expectedPartition = 1;
        final long expectedOffset = 31332L;
        final String consumerId = "MyConsumerId";
        final TupleMessageId tupleMessageId = new TupleMessageId(expectedTopic, expectedPartition, expectedOffset, consumerId);

        // Define our endingState with a position less than our TupleMessageId
        final ConsumerState endingState = ConsumerState.builder()
            .withPartition(new TopicPartition(expectedTopic, expectedPartition), (expectedOffset - 100))
            .build();

        // Create factory manager
        final FactoryManager factoryManager = new FactoryManager(topologyConfig);

        // Create spout passing in ending state.
        VirtualSpout virtualSpout = new VirtualSpout(
            topologyConfig,
            mockTopologyContext,
            factoryManager,
            mockConsumer,
            null, endingState);
        virtualSpout.setVirtualSpoutId("MyConsumerId");
        virtualSpout.open();

        // Call our method & validate.
        final boolean result = virtualSpout.doesMessageExceedEndingOffset(tupleMessageId);
        assertTrue("Should be true", result);
    }

    /**
     * Test calling this method with a defined endingState, and the TupleMessageId's offset is before it.
     */
    @Test
    public void testDoesMessageExceedEndingOffsetWhenItDoesNotExceedEndingOffset() {
        // Create inputs
        final Map topologyConfig = getDefaultConfig();
        final TopologyContext mockTopologyContext = new MockTopologyContext();
        final Consumer mockConsumer = mock(Consumer.class);

        // Create our test TupleMessageId
        final String expectedTopic = "MyTopic";
        final int expectedPartition = 1;
        final long expectedOffset = 31332L;
        final String consumerId = "MyConsumerId";
        final TupleMessageId tupleMessageId = new TupleMessageId(expectedTopic, expectedPartition, expectedOffset, consumerId);

        // Define our endingState with a position greater than than our TupleMessageId
        final ConsumerState endingState = ConsumerState.builder()
            .withPartition(new TopicPartition(expectedTopic, expectedPartition), (expectedOffset + 100))
            .build();

        // Create factory manager
        final FactoryManager factoryManager = new FactoryManager(topologyConfig);

        // Create spout passing in ending state.
        VirtualSpout virtualSpout = new VirtualSpout(
            topologyConfig,
            mockTopologyContext,
            factoryManager,
            mockConsumer,
            null, endingState);
        virtualSpout.setVirtualSpoutId("MyConsumerId");
        virtualSpout.open();

        // Call our method & validate.
        final boolean result = virtualSpout.doesMessageExceedEndingOffset(tupleMessageId);
        assertFalse("Should be false", result);
    }

    /**
     * Test calling this method with a defined endingState, and then send in a TupleMessageId
     * associated with a partition that doesn't exist in the ending state.  It should throw
     * an illegal state exception.
     */
    @Test
    public void testDoesMessageExceedEndingOffsetForAnInvalidPartition() {
        // Create inputs
        final Map topologyConfig = getDefaultConfig();
        final TopologyContext mockTopologyContext = new MockTopologyContext();
        final Consumer mockConsumer = mock(Consumer.class);

        // Create our test TupleMessageId
        final String expectedTopic = "MyTopic";
        final int expectedPartition = 1;
        final long expectedOffset = 31332L;
        final String consumerId = "MyConsumerId";
        final TupleMessageId tupleMessageId = new TupleMessageId(expectedTopic, expectedPartition, expectedOffset, consumerId);

        // Define our endingState with a position greater than than our TupleMessageId, but on a different partition
        final ConsumerState endingState = ConsumerState.builder()
            .withPartition(new TopicPartition(expectedTopic, expectedPartition + 1), (expectedOffset + 100))
            .build();

        // Create factory manager
        final FactoryManager factoryManager = new FactoryManager(topologyConfig);

        // Create spout passing in ending state.
        VirtualSpout virtualSpout = new VirtualSpout(
            topologyConfig,
            mockTopologyContext,
            factoryManager,
            mockConsumer,
            null, endingState);
        virtualSpout.setVirtualSpoutId("MyConsumerId");
        virtualSpout.open();

        // Call our method & validate exception is thrown
        expectedException.expect(IllegalStateException.class);
        virtualSpout.doesMessageExceedEndingOffset(tupleMessageId);
    }

    /**
     * This test uses a mock to validate when you call unsubsubscribeTopicPartition() that it passes the argument
     * to its underlying consumer, and passes back the right result value from that call.
     */
    @Test
    public void testUnsubscribeTopicPartition() {
        final boolean expectedResult = true;

        // Create inputs
        final Map topologyConfig = getDefaultConfig();
        final TopologyContext mockTopologyContext = new MockTopologyContext();

        // Create a mock SidelineConsumer
        Consumer mockConsumer = mock(Consumer.class);
        when(mockConsumer.unsubscribeTopicPartition(any(TopicPartition.class))).thenReturn(expectedResult);

        // Create factory manager
        final FactoryManager factoryManager = new FactoryManager(topologyConfig);

        // Create spout
        VirtualSpout virtualSpout = new VirtualSpout(
            topologyConfig,
            mockTopologyContext,
            factoryManager,
            mockConsumer,
            null, null);
        virtualSpout.setVirtualSpoutId("MyConsumerId");
        virtualSpout.open();

        // Create our test TupleMessageId
        final String expectedTopic = "MyTopic";
        final int expectedPartition = 1;
        final TopicPartition topicPartition = new TopicPartition(expectedTopic, expectedPartition);

        // Call our method & validate.
        final boolean result = virtualSpout.unsubscribeTopicPartition(topicPartition);
        assertEquals("Got expected result from our method", expectedResult, result);

        // Validate mock call
        verify(mockConsumer, times(1)).unsubscribeTopicPartition(eq(topicPartition));
    }

    /**
     * Test calling close, verifies what happens if the completed flag is false.
     */
    @Test
    public void testCloseWithCompletedFlagSetToFalse() throws NoSuchFieldException, IllegalAccessException {
        // Create inputs
        final Map topologyConfig = getDefaultConfig();
        final TopologyContext mockTopologyContext = new MockTopologyContext();
        final SidelineRequestIdentifier sidelineRequestId = new SidelineRequestIdentifier();

        // Create a mock SidelineConsumer
        Consumer mockConsumer = mock(Consumer.class);

        // Create a mock PersistanceManager & associate with SidelineConsumer.
        PersistenceAdapter mockPersistenceAdapter = mock(PersistenceAdapter.class);
        when(mockConsumer.getPersistenceAdapter()).thenReturn(mockPersistenceAdapter);

        // Create factory manager
        final FactoryManager factoryManager = new FactoryManager(topologyConfig);

        // Create spout
        VirtualSpout virtualSpout = new VirtualSpout(
            topologyConfig,
            mockTopologyContext,
            factoryManager,
            mockConsumer,
            null, null);
        virtualSpout.setVirtualSpoutId("MyConsumerId");
        virtualSpout.setSidelineRequestIdentifier(sidelineRequestId);
        virtualSpout.open();

        // Mark sure is completed field is set to false before calling close
        Field isCompletedField = virtualSpout.getClass().getDeclaredField("isCompleted");
        isCompletedField.setAccessible(true);
        isCompletedField.set(virtualSpout, false);

        // Verify close hasn't been called yet.
        verify(mockConsumer, never()).close();

        // Call close
        virtualSpout.close();

        // Verify close was called, and state was flushed
        verify(mockConsumer, times(1)).flushConsumerState();
        verify(mockConsumer, times(1)).close();

        // But we never called remove consumer state.
        verify(mockConsumer, never()).removeConsumerState();

        // Never remove sideline request state
        verify(mockPersistenceAdapter, never()).clearSidelineRequest(anyObject(), anyInt());
    }

    /**
     * Test calling close, verifies what happens if the completed flag is true.
     * Verifies what happens if SidelineRequestIdentifier is set.
     */
    @Test
    public void testCloseWithCompletedFlagSetToTrue() throws NoSuchFieldException, IllegalAccessException {
        // Create inputs
        final Map topologyConfig = getDefaultConfig();
        final TopologyContext mockTopologyContext = new MockTopologyContext();
        final SidelineRequestIdentifier sidelineRequestId = new SidelineRequestIdentifier();

        // Create a mock SidelineConsumer
        Consumer mockConsumer = mock(Consumer.class);

        // Create a mock PersistanceManager & associate with SidelineConsumer.
        PersistenceAdapter mockPersistenceAdapter = mock(PersistenceAdapter.class);
        when(mockConsumer.getPersistenceAdapter()).thenReturn(mockPersistenceAdapter);

        // Create factory manager
        final FactoryManager factoryManager = new FactoryManager(topologyConfig);

        ConsumerState.ConsumerStateBuilder startingStateBuilder = ConsumerState.builder();
        startingStateBuilder.withPartition(new TopicPartition("foobar", 0), 1L);
        ConsumerState startingState = startingStateBuilder.build();

        // Create spout
        VirtualSpout virtualSpout = new VirtualSpout(
        topologyConfig,
        mockTopologyContext,
        factoryManager,
        mockConsumer,
        startingState,
        null
        );
        virtualSpout.setVirtualSpoutId("MyConsumerId");
        virtualSpout.setSidelineRequestIdentifier(sidelineRequestId);
        virtualSpout.open();

        // Mark sure is completed field is set to true before calling close
        Field isCompletedField = virtualSpout.getClass().getDeclaredField("isCompleted");
        isCompletedField.setAccessible(true);
        isCompletedField.set(virtualSpout, true);

        // Verify close hasn't been called yet.
        verify(mockConsumer, never()).close();

        // Call close
        virtualSpout.close();

        // Verify close was called, and state was cleared
        verify(mockConsumer, times(1)).removeConsumerState();
        verify(mockPersistenceAdapter, times(1)).clearSidelineRequest(eq(sidelineRequestId), eq(0));
        verify(mockConsumer, times(1)).close();

        // But we never called flush consumer state.
        verify(mockConsumer, never()).flushConsumerState();
    }

    /**
     * Test calling close, verifies what happens if the completed flag is true.
     * Verifies what happens if SidelineRequestIdentifier is null.
     */
    @Test
    public void testCloseWithCompletedFlagSetToTrueNoSidelineREquestIdentifier() throws NoSuchFieldException, IllegalAccessException {
        // Create inputs
        final Map topologyConfig = getDefaultConfig();
        final TopologyContext mockTopologyContext = new MockTopologyContext();

        // Create a mock SidelineConsumer
        Consumer mockConsumer = mock(Consumer.class);

        // Create a mock PersistanceManager & associate with SidelineConsumer.
        PersistenceAdapter mockPersistenceAdapter = mock(PersistenceAdapter.class);
        when(mockConsumer.getPersistenceAdapter()).thenReturn(mockPersistenceAdapter);

        // Create factory manager
        final FactoryManager factoryManager = new FactoryManager(topologyConfig);

        // Create spout
        VirtualSpout virtualSpout = new VirtualSpout(
            topologyConfig,
            mockTopologyContext,
            factoryManager,
            mockConsumer,
            null, null);
        virtualSpout.setVirtualSpoutId("MyConsumerId");
        virtualSpout.open();

        // Mark sure is completed field is set to true before calling close
        Field isCompletedField = virtualSpout.getClass().getDeclaredField("isCompleted");
        isCompletedField.setAccessible(true);
        isCompletedField.set(virtualSpout, true);

        // Verify close hasn't been called yet.
        verify(mockConsumer, never()).close();

        // Call close
        virtualSpout.close();

        // Verify close was called, and state was cleared
        verify(mockConsumer, times(1)).removeConsumerState();
        verify(mockPersistenceAdapter, never()).clearSidelineRequest(anyObject(), anyInt());
        verify(mockConsumer, times(1)).close();

        // But we never called flush consumer state.
        verify(mockConsumer, never()).flushConsumerState();
    }

    /**
     * This test does the following:
     *
     * 1. Call nextTuple() -
     *  a. the first time RetryManager should return null, saying it has no failed tuples to replay
     *  b. sideline consumer should return a consumer record, and it should be returned by nextTuple()
     * 2. Call fail() with the message previously returned from nextTuple().
     * 2. Call nextTuple()
     *  a. This time RetryManager should return the failed tuple
     * 3. Call nextTuple()
     *  a. This time RetryManager should return null, saying it has no failed tuples to replay.
     *  b. sideline consumer should return a new consumer record.
     */
    @Test
    public void testCallingFailCallsAckOnWhenItShouldNotBeRetried() {
        // This is the record coming from the consumer.
        final String expectedTopic = "MyTopic";
        final int expectedPartition = 3;
        final long expectedOffset = 434323L;
        final String expectedConsumerId = "MyConsumerId";
        final String expectedKey = "MyKey";
        final String expectedValue = "MyValue";
        final byte[] expectedKeyBytes = expectedKey.getBytes(Charsets.UTF_8);
        final byte[] expectedValueBytes = expectedValue.getBytes(Charsets.UTF_8);
        final ConsumerRecord<byte[], byte[]> expectedConsumerRecord = new ConsumerRecord<>(expectedTopic, expectedPartition, expectedOffset, expectedKeyBytes, expectedValueBytes);

        // Define expected result
        final KafkaMessage expectedKafkaMessage = new KafkaMessage(new TupleMessageId(expectedTopic, expectedPartition, expectedOffset, expectedConsumerId), new Values(expectedKey, expectedValue));

        // Create test config
        final Map topologyConfig = getDefaultConfig();

        // Create mock topology context
        final TopologyContext mockTopologyContext = new MockTopologyContext();

        // Create a mock SidelineConsumer
        Consumer mockConsumer = mock(Consumer.class);

        // Create a mock RetryManager
        RetryManager mockRetryManager = mock(RetryManager.class);

        // Create factory manager
        final FactoryManager mockFactoryManager = createMockFactoryManager(null, mockRetryManager);

        // Create spout & open
        VirtualSpout virtualSpout = new VirtualSpout(
            topologyConfig,
            mockTopologyContext,
            mockFactoryManager,
            mockConsumer,
            null, null);
        virtualSpout.setVirtualSpoutId(expectedConsumerId);
        virtualSpout.open();

        // Now call fail on this
        final TupleMessageId failedMessageId = expectedKafkaMessage.getTupleMessageId();

        // Retry manager should retry this tuple.
        when(mockRetryManager.retryFurther(failedMessageId)).thenReturn(false);

        // call fail on our message id
        virtualSpout.fail(failedMessageId);

        // Verify since this wasn't retried, it gets acked both by the consumer and the retry manager.
        verify(mockRetryManager, times(1)).acked(failedMessageId);
        verify(mockConsumer, times(1)).commitOffset(failedMessageId.getTopicPartition(), failedMessageId.getOffset());
    }

    /**
     * Tests that calling getCurrentState() is based down to the
     * sidelineConsumer appropriately.
     */
    @Test
    public void testGetCurrentState() {
        // Create inputs
        final Map topologyConfig = getDefaultConfig();
        final TopologyContext mockTopologyContext = new MockTopologyContext();
        final RetryManager mockRetryManager = mock(RetryManager.class);

        // Create a mock SidelineConsumer
        Consumer mockConsumer = mock(Consumer.class);

        // Create factory manager
        final FactoryManager mockFactoryManager = createMockFactoryManager(null, mockRetryManager);

        // Create spout & open
        VirtualSpout virtualSpout = new VirtualSpout(
            topologyConfig,
            mockTopologyContext,
            mockFactoryManager,
            mockConsumer,
            null, null);
        virtualSpout.setVirtualSpoutId("MyConsumerId");
        virtualSpout.open();

        final ConsumerState expectedConsumerState = ConsumerState
            .builder()
            .withPartition(new TopicPartition("myTopic", 0), 200L)
            .build();

        // Setup our mock to return expected value
        when(mockConsumer.getCurrentState()).thenReturn(expectedConsumerState);

        // Call get current state.
        final ConsumerState result = virtualSpout.getCurrentState();

        // Verify mock interactions
        verify(mockConsumer, times(1)).getCurrentState();

        // Verify result
        assertNotNull("result should not be null", result);
        assertEquals("Should be our expected instance", expectedConsumerState, result);
    }

    /**
     * Utility method to generate a standard config map.
     */
    private Map getDefaultConfig() {
        final Map defaultConfig = Maps.newHashMap();
        defaultConfig.put(SidelineSpoutConfig.KAFKA_BROKERS, Lists.newArrayList("localhost:9092"));
        defaultConfig.put(SidelineSpoutConfig.KAFKA_TOPIC, "MyTopic");
        defaultConfig.put(SidelineSpoutConfig.CONSUMER_ID_PREFIX, "TestPrefix");
        defaultConfig.put(SidelineSpoutConfig.PERSISTENCE_ZK_ROOT, "/sideline-spout-test");
        defaultConfig.put(SidelineSpoutConfig.PERSISTENCE_ZK_SERVERS, Lists.newArrayList("localhost:21811"));
        defaultConfig.put(SidelineSpoutConfig.DESERIALIZER_CLASS, Utf8StringDeserializer.class.getName());

        return SidelineSpoutConfig.setDefaults(defaultConfig);
    }

    /**
     * Utility method for creating a mock factory manager.
     */
    private FactoryManager createMockFactoryManager(Deserializer deserializer, RetryManager retryManager) {
        // Create our mock
        FactoryManager factoryManager = mock(FactoryManager.class);

        // If a mocked deserializer not passed in
        if (deserializer == null) {
            // Default to utf8
            deserializer = new Utf8StringDeserializer();
        }
        when(factoryManager.createNewDeserializerInstance()).thenReturn(deserializer);

        // If a mocked failed msg retry manager isn't passed in
        if (retryManager == null) {
            retryManager = new NeverRetryManager();
        }
        when(factoryManager.createNewFailedMsgRetryManagerInstance()).thenReturn(retryManager);

        return factoryManager;
    }
}