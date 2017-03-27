package com.salesforce.storm.spout.sideline;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.salesforce.storm.spout.sideline.config.SidelineSpoutConfig;
import com.salesforce.storm.spout.sideline.filter.FilterChainStep;
import com.salesforce.storm.spout.sideline.filter.NegatingFilterChainStep;
import com.salesforce.storm.spout.sideline.kafka.ConsumerState;
import com.salesforce.storm.spout.sideline.kafka.VirtualSidelineSpout;
import com.salesforce.storm.spout.sideline.metrics.MetricsRecorder;
import com.salesforce.storm.spout.sideline.persistence.PersistenceManager;
import com.salesforce.storm.spout.sideline.persistence.SidelinePayload;
import com.salesforce.storm.spout.sideline.trigger.SidelineRequestIdentifier;
import com.salesforce.storm.spout.sideline.trigger.SidelineRequest;
import com.salesforce.storm.spout.sideline.trigger.SidelineType;
import com.salesforce.storm.spout.sideline.trigger.StartingTrigger;
import com.salesforce.storm.spout.sideline.trigger.StoppingTrigger;
import com.salesforce.storm.spout.sideline.tupleBuffer.TupleBuffer;
import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichSpout;
import org.apache.storm.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Spout instance.
 */
public class SidelineSpout extends BaseRichSpout {
    // Logging
    private static final Logger logger = LoggerFactory.getLogger(SidelineSpout.class);

    /**
     * The Topology configuration map.
     */
    private Map topologyConfig;

    /**
     * Spout's output collector, for emitting tuples out into the topology.
     */
    private SpoutOutputCollector outputCollector;

    /**
     * The Topology Context object.
     */
    private TopologyContext topologyContext;

    /**
     * Our internal Coordinator.  This manages all Virtual Spouts as well
     * as handles routing emitted, acked, and failed tuples between this SidelineSpout instance
     * and the appropriate Virtual Spouts.
     */
    private SpoutCoordinator coordinator;

    /**
     * TODO: Lemon - Add some descriptions to these guys.
     */
    private StartingTrigger startingTrigger;
    private StoppingTrigger stoppingTrigger;

    /**
     * This is our main Virtual Spout instance which consumes from the configured topic.
     * TODO: Do we need access to this here?  Could this be moved into the Coordinator?
     */
    private VirtualSidelineSpout fireHoseSpout;

    /**
     * Manages creating implementation instances.
     */
    private final FactoryManager factoryManager;

    /**
     * Stores state about starting/stopping sideline requests.
     */
    private PersistenceManager persistenceManager;

    /**
     * For collecting metrics.
     */
    private transient MetricsRecorder metricsRecorder;
    private transient Map<String, Long> emitCountMetrics;
    private long emitCounter = 0L;

    /**
     * Determines which output stream to emit tuples out.
     * Gets set during open().
     */
    private String outputStreamId = null;

    /**
     * Constructor to create our SidelineSpout.
     * @TODO this method arguments may change to an actual SidelineSpoutConfig object instead of a generic map?
     *
     * @param topologyConfig - Our configuration.
     */
    public SidelineSpout(Map topologyConfig) {
        // Save off config, injecting appropriate default values for anything not explicitly configured.
        this.topologyConfig = Collections.unmodifiableMap(SidelineSpoutConfig.setDefaults(topologyConfig));

        // Create our factory manager, which must be serializable.
        factoryManager = new FactoryManager(getTopologyConfig());
    }

    /**
     * Set a starting trigger on the spout for starting a sideline request.
     * @param startingTrigger An implementation of a starting trigger
     */
    public void setStartingTrigger(StartingTrigger startingTrigger) {
        this.startingTrigger = startingTrigger;
    }

    /**
     * Set a trigger on the spout for stopping a sideline request.
     * @param stoppingTrigger An implementation of a stopping trigger
     */
    public void setStoppingTrigger(StoppingTrigger stoppingTrigger) {
        this.stoppingTrigger = stoppingTrigger;
    }

    /**
     * Starts a sideline request.
     * @param sidelineRequest A representation of the request that is being started
     */
    public SidelineRequestIdentifier startSidelining(SidelineRequest sidelineRequest) {
        logger.info("Received START sideline request");

        final SidelineRequestIdentifier id = new SidelineRequestIdentifier();

        // Store the offset that this request was made at, when the sideline stops we will begin processing at
        // this offset
        final ConsumerState startingState = fireHoseSpout.getCurrentState();

        // Store in request manager
        persistenceManager.persistSidelineRequestState(
            SidelineType.START,
            id,
            sidelineRequest,
            startingState,
            null
        );

        // Add our new filter steps
        fireHoseSpout.getFilterChain().addSteps(id, sidelineRequest.steps);

        // Update start count metric
        metricsRecorder.count(getClass(), "start-sideline", 1L);

        return id;
    }

    /**
     * Stops a sideline request.
     * @param sidelineRequest A representation of the request that is being stopped
     */
    public void stopSidelining(SidelineRequest sidelineRequest) {
        final SidelineRequestIdentifier id = fireHoseSpout.getFilterChain().findSteps(sidelineRequest.steps);

        if (id == null) {
            logger.error(
                "Received STOP sideline request, but I don't actually have any filter chain steps for it! Make sure you check that your filter implements an equals() method. {} {}",
                sidelineRequest.steps,
                fireHoseSpout.getFilterChain().getSteps()
            );
            return;
        }

        logger.info("Received STOP sideline request");

        List<FilterChainStep> negatedSteps = new ArrayList<>();

        List<FilterChainStep> steps = fireHoseSpout.getFilterChain().removeSteps(id);

        for (FilterChainStep step : steps) {
            negatedSteps.add(new NegatingFilterChainStep(step));
        }

        // This is the state that the VirtualSidelineSpout should start with
        final ConsumerState startingState = persistenceManager.retrieveSidelineRequest(id).startingState;

        // This is the state that the VirtualSidelineSpout should end with
        final ConsumerState endingState = fireHoseSpout.getCurrentState();

        // Persist the side line request state with the new negated version of the steps.
        persistenceManager.persistSidelineRequestState(
            SidelineType.STOP,
            id,
            // TODO: Lemon - Is this a bug?  We're persisting the non-negated steps here!
            // TODO: Lemon - When we resume we have to remember to negate them.... is that what we want to do?
            // TODO: Lemon - See my TODO in open() about this...
            new SidelineRequest(steps), // Persist a new request with the negated steps
            startingState,
            endingState
        );

        // Generate our virtualSpoutId using the payload id.
        final String virtualSpoutId = generateVirtualSpoutId(id.toString());

        // This info is repeated in VirtualSidelineSpout.open(), not needed here.
        logger.debug("Starting VirtualSidelineSpout with starting state {}", startingState);
        logger.debug("Starting VirtualSidelineSpout with ending state {}", endingState);

        final VirtualSidelineSpout spout = new VirtualSidelineSpout(
            topologyConfig,
            topologyContext,
            factoryManager,
            metricsRecorder,
            // Starting offset of the sideline request
            startingState,
            // When the sideline request ends
            endingState
        );
        spout.setVirtualSpoutId(virtualSpoutId);
        spout.setSidelineRequestIdentifier(id);
        spout.getFilterChain().addSteps(id, negatedSteps);

        getCoordinator().addSidelineSpout(spout);

        // Update stop count metric
        metricsRecorder.count(getClass(), "stop-sideline", 1L);
    }

    /**
     * Open is called once the SidelineSpout instance has been deployed to the Storm cluster
     * and is ready to get to work.
     *
     * @param topologyConfig - The Storm Topology configuration.
     * @param topologyContext - The Storm Topology context.
     * @param spoutOutputCollector - The output collector to emit tuples via.
     */
    @Override
    public void open(Map topologyConfig, TopologyContext topologyContext, SpoutOutputCollector spoutOutputCollector) {
        // Save references.
        this.topologyConfig = Tools.immutableCopy(SidelineSpoutConfig.setDefaults(topologyConfig));
        this.topologyContext = topologyContext;
        this.outputCollector = spoutOutputCollector;

        // Initialize Metrics Collection
        metricsRecorder = getFactoryManager().createNewMetricsRecorder();
        metricsRecorder.open(getTopologyConfig(), getTopologyContext());

        // TODO: LEMON - should this be removed?
        // TODO: LEMON - can SidelineTriggerProxy be replaced with an interface?
        if (startingTrigger != null) {
            startingTrigger.setSidelineSpout(new SpoutTriggerProxy(this));
        }

        // TODO: LEMON - should this be removed?
        // TODO: LEMON - can SidelineTriggerProxy be replaced with an interface?
        if (stoppingTrigger != null) {
            stoppingTrigger.setSidelineSpout(new SpoutTriggerProxy(this));
        }

        // Ensure a consumer id prefix has been correctly set.
        if (Strings.isNullOrEmpty((String) getTopologyConfigItem(SidelineSpoutConfig.CONSUMER_ID_PREFIX))) {
            throw new IllegalStateException("Missing required configuration: " + SidelineSpoutConfig.CONSUMER_ID_PREFIX);
        }

        // Create and open() persistence manager passing appropriate configuration.
        persistenceManager = getFactoryManager().createNewPersistenceManagerInstance();
        persistenceManager.open(getTopologyConfig());

        // Create the main spout for the topic, we'll dub it the 'firehose'
        fireHoseSpout = new VirtualSidelineSpout(
                getTopologyConfig(),
                getTopologyContext(),
                getFactoryManager(),
                metricsRecorder);
        fireHoseSpout.setVirtualSpoutId(generateVirtualSpoutId("main"));

        // Create TupleBuffer
        final TupleBuffer tupleBuffer = getFactoryManager().createNewTupleBufferInstance();
        tupleBuffer.open(getTopologyConfig());

        // Create Spout Coordinator.
        coordinator = new SpoutCoordinator(
            // Our main firehose spout instance.
            fireHoseSpout,

            // Our metrics recorder.
            metricsRecorder,

            // Our TupleBuffer/Queue Implementation.
            tupleBuffer
        );

        // Call open on coordinator.
        getCoordinator().open(getTopologyConfig());

        // TODO: LEMON - We should build the full payload here rather than individual requests later on
        final List<SidelineRequestIdentifier> existingRequestIds = persistenceManager.listSidelineRequests();
        logger.info("Found {} existing sideline requests that need to be resumed", existingRequestIds.size());

        for (SidelineRequestIdentifier id : existingRequestIds) {
            final SidelinePayload payload = persistenceManager.retrieveSidelineRequest(id);

            // Resuming a start request means we apply the previous filter chain to the fire hose
            if (payload.type.equals(SidelineType.START)) {
                logger.info("Resuming START sideline {} {}", payload.id, payload.request.steps);

                fireHoseSpout.getFilterChain().addSteps(
                    payload.id,
                    payload.request.steps
                );
            }

            // Resuming a stopped request means we spin up a new sideline spout
            if (payload.type.equals(SidelineType.STOP)) {
                // TODO: refactor this and stopSidelining() method to de-duplicate code.
                logger.info("Resuming STOP sideline {} {}", payload.id, payload.request.steps);

                // Generate our virtualSpoutId using the payload id.
                final String virtualSpoutId = generateVirtualSpoutId(payload.id.toString());

                // TODO: Lemon - Bug - What if we've previously processed this virtual spout before...?
                // If we tell it start at the payloads start... it won't resume where it left off.
                // Lets hack this in for now..is there a better way?
                PersistenceManager persistenceManager = getFactoryManager().createNewPersistenceManagerInstance();
                persistenceManager.open(getTopologyConfig());
                ConsumerState startingState = persistenceManager.retrieveConsumerState(virtualSpoutId);
                persistenceManager.close();

                // If our starting state is null or empty
                if (startingState == null || startingState.isEmpty()) {
                    // THen we have no previously stored state, so use the payload state
                    startingState = payload.startingState;
                }

                // Create spout instance.
                final VirtualSidelineSpout spout = new VirtualSidelineSpout(
                    getTopologyConfig(),
                    getTopologyContext(),
                    getFactoryManager(),
                    metricsRecorder,
                    startingState,
                    payload.endingState
                );
                spout.setVirtualSpoutId(virtualSpoutId);
                spout.setSidelineRequestIdentifier(id);

                // Add the request's filter steps
                // TODO: But we never persisted the negated steps?  So we have to negate them here.
                // TODO: Is this a bug? see my TODO note in stopSidelining() method.
                // TODO: Note - Steviep added this next loop to negate steps, was not here previously
                final List<FilterChainStep> negatedSteps = Lists.newArrayList();
                for (FilterChainStep step : payload.request.steps) {
                    negatedSteps.add(new NegatingFilterChainStep(step));
                }
                spout.getFilterChain().addSteps(payload.id, negatedSteps);

                // Now pass the new "resumed" spout over to the coordinator to open and run
                getCoordinator().addSidelineSpout(spout);
            }
        }

        // TODO: LEMON - shouldn't this be always not null, can we remove this?
        if (startingTrigger != null) {
            startingTrigger.open(getTopologyConfig());
        }

        // TODO: LEMON - shouldn't this be always not null, can we remove this?
        if (stoppingTrigger != null) {
            stoppingTrigger.open(getTopologyConfig());
        }

        // For emit metrics
        emitCountMetrics = Maps.newHashMap();
    }

    @Override
    public void nextTuple() {
        /**
         * Ask the SpoutCoordinator for the next message that should be emitted.
         * If it returns null, then there's nothing new to emit!
         * If a KafkaMessage object is returned, it contains the appropriately
         * mapped MessageId and Values for the tuple that should be emitted.
         */
        final KafkaMessage kafkaMessage = getCoordinator().nextMessage();
        if (kafkaMessage == null) {
            // Nothing new to emit!
            return;
        }

        // Emit tuple via the output collector.
        getOutputCollector().emit(getOutputStreamId(), kafkaMessage.getValues(), kafkaMessage.getTupleMessageId());

        // Update emit count metric for SidelineSpout
        metricsRecorder.count(getClass(), "emit", 1L);

        // Update emit count metric for VirtualSidelineSpout this tuple originated from
        metricsRecorder.count(VirtualSidelineSpout.class, kafkaMessage.getTupleMessageId().getSrcVirtualSpoutId() + ".emit", 1);

        // Everything below is temporary emit metrics for debugging.

        // Update / Display emit metrics
        final String srcId = kafkaMessage.getTupleMessageId().getSrcVirtualSpoutId();
        if (!emitCountMetrics.containsKey(srcId)) {
            emitCountMetrics.put(srcId, 1L);
        } else {
            emitCountMetrics.put(srcId, emitCountMetrics.get(srcId) + 1L);
        }
        emitCounter++;
        if (emitCounter >= 5_000_000L) {
            for (Map.Entry<String, Long> entry : emitCountMetrics.entrySet()) {
                logger.info("Emit Count on {} => {}", entry.getKey(), entry.getValue());
            }
            emitCountMetrics.clear();
            emitCounter = 0;
        }

        // End temp debugging logs
    }

    /**
     * Declare the output fields and stream id.
     * @param declarer The output field declarer
     */
    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        // Handles both explicitly defined and default stream definitions.
        declarer.declareStream(getOutputStreamId(), factoryManager.createNewDeserializerInstance().getOutputFields());
    }

    /**
     * Called to close up shop and end this instance.
     */
    @Override
    public void close() {
        logger.info("Stopping the coordinator and closing all spouts");

        if (getCoordinator() != null) {
            getCoordinator().close();
            coordinator = null;
        }

        if (startingTrigger != null) {
            startingTrigger.close();
        }

        if (stoppingTrigger != null) {
            stoppingTrigger.close();
        }
    }

    /**
     * Currently a no-op.  We could make this pause things in the coordinator.
     */
    @Override
    public void activate() {
        logger.info("Activating spout");
    }

    /**
     * Currently a no-op.  We could make this un-pause things in the coordinator.
     */
    @Override
    public void deactivate() {
        logger.info("Deactivate spout");
    }

    /**
     * Called for a Tuple MessageId when the tuple has been fully processed.
     * @param id - the tuple's message id.
     */
    @Override
    public void ack(Object id) {
        // Cast to appropriate object type
        final TupleMessageId tupleMessageId = (TupleMessageId) id;

        // Ack the tuple via the coordinator
        getCoordinator().ack(tupleMessageId);

        // Update ack count metric
        metricsRecorder.count(getClass(), "ack", 1L);

        // Update ack count metric for VirtualSidelineSpout this tuple originated from
        metricsRecorder.count(VirtualSidelineSpout.class, tupleMessageId.getSrcVirtualSpoutId() + ".ack", 1);
    }

    /**
     * Called for a Tuple MessageId when the tuple has failed during processing.
     * @param id - The failed tuple's message id.
     */
    @Override
    public void fail(Object id) {
        // Cast to appropriate object type
        final TupleMessageId tupleMessageId = (TupleMessageId) id;

        // Fail the tuple via the coordinator
        getCoordinator().fail(tupleMessageId);

        // Update fail count metric
        metricsRecorder.count(getClass(), "fail", 1L);

        // Update ack count metric for VirtualSidelineSpout this tuple originated from
        metricsRecorder.count(VirtualSidelineSpout.class, tupleMessageId.getSrcVirtualSpoutId() + ".fail", 1);
    }

    /**
     * @return - the Storm topology config map.
     */
    private Map getTopologyConfig() {
        return topologyConfig;
    }

    /**
     * Utility method to get a specific entry in the Storm topology config map.
     * @param key - the configuration item to retrieve
     * @return - the configuration item's value.
     */
    private Object getTopologyConfigItem(final String key) {
        return getTopologyConfig().get(key);
    }

    /**
     * @return - The Storm topology context.
     */
    private TopologyContext getTopologyContext() {
        return topologyContext;
    }

    /**
     * @return - The factory manager instance.
     */
    private FactoryManager getFactoryManager() {
        return factoryManager;
    }

    /**
     * @return - The spout's output collector.
     */
    private SpoutOutputCollector getOutputCollector() {
        return outputCollector;
    }

    /**
     * @return - The virtual spout coordinator.
     */
    SpoutCoordinator getCoordinator() {
        return coordinator;
    }

    /**
     * @return - returns the stream that tuples will be emitted out.
     */
    String getOutputStreamId() {
        if (outputStreamId == null) {
            if (topologyConfig == null) {
                throw new IllegalStateException("Missing required configuration!  SidelineSpoutConfig not defined!");
            }
            outputStreamId = (String) getTopologyConfigItem(SidelineSpoutConfig.OUTPUT_STREAM_ID);
            if (Strings.isNullOrEmpty(outputStreamId)) {
                outputStreamId = Utils.DEFAULT_STREAM_ID;
            }
        }
        return outputStreamId;
    }

    // Grab our ConsumerId prefix from the config, append the task index.  This will probably cause problems
    // if you decrease the number of instances of the spout.
    private String generateVirtualSpoutId(String optionalPostfix) {
        // Also prefixed with our configured prefix
        String newId = (String) getTopologyConfigItem(SidelineSpoutConfig.CONSUMER_ID_PREFIX);

        // If we have an optional postfix
        if (!Strings.isNullOrEmpty(optionalPostfix)) {
            // append it
            newId += "-" + optionalPostfix;
        }
        // Always append the task index
        newId += "-" + getTopologyContext().getThisTaskIndex();

        // return it
        return newId;
    }
}
