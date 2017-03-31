package com.salesforce.storm.spout.sideline.mocks;

import com.google.common.collect.Maps;
import org.apache.storm.metric.api.IMetric;
import org.apache.storm.task.TopologyContext;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class MockTopologyContext extends TopologyContext {

    public Map<String, IMetric> mockRegisteredMetrics = Maps.newHashMap();
    public int taskId = 0;
    public int taskIndex = 0;
    public List<Integer> componentTasks = Collections.singletonList(1);

    public MockTopologyContext() {
        super(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public <T extends IMetric> T registerMetric(String name, T metric, int timeBucketSizeInSecs) {
        mockRegisteredMetrics.put(name, metric);
        return metric;
    }

    @Override
    public IMetric getRegisteredMetricByName(String name) {
        return mockRegisteredMetrics.get(name);
    }

    @Override
    public int getThisTaskId() {
        return taskId;
    }

    public String getThisComponentId() {
        return "Mock";
    }

    public List<Integer> getComponentTasks(String componentId) {
        return componentTasks;
    }

    public int getThisTaskIndex() {
        return taskIndex;
    }
}
