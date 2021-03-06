/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.siddhi.extension.execution.reorder;

import io.siddhi.annotation.Example;
import io.siddhi.annotation.Extension;
import io.siddhi.annotation.Parameter;
import io.siddhi.annotation.ParameterOverload;
import io.siddhi.annotation.util.DataType;
import io.siddhi.core.config.SiddhiAppContext;
import io.siddhi.core.config.SiddhiQueryContext;
import io.siddhi.core.event.ComplexEvent;
import io.siddhi.core.event.ComplexEventChunk;
import io.siddhi.core.event.stream.MetaStreamEvent;
import io.siddhi.core.event.stream.StreamEvent;
import io.siddhi.core.event.stream.StreamEventCloner;
import io.siddhi.core.event.stream.holder.StreamEventClonerHolder;
import io.siddhi.core.event.stream.populater.ComplexEventPopulater;
import io.siddhi.core.exception.SiddhiAppCreationException;
import io.siddhi.core.executor.ConstantExpressionExecutor;
import io.siddhi.core.executor.ExpressionExecutor;
import io.siddhi.core.query.processor.ProcessingMode;
import io.siddhi.core.query.processor.Processor;
import io.siddhi.core.query.processor.SchedulingProcessor;
import io.siddhi.core.query.processor.stream.StreamProcessor;
import io.siddhi.core.util.Scheduler;
import io.siddhi.core.util.config.ConfigReader;
import io.siddhi.core.util.snapshot.state.State;
import io.siddhi.core.util.snapshot.state.StateFactory;
import io.siddhi.extension.execution.reorder.utils.WindowCoverage;
import io.siddhi.query.api.definition.AbstractDefinition;
import io.siddhi.query.api.definition.Attribute;
import org.apache.commons.math3.distribution.NormalDistribution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;


/**
 * The following code conducts reordering of an out-of-order event stream.
 * This implements the Alpha K-Slack based disorder handling algorithm which was originally
 * described in http://dl.acm.org/citation.cfm?doid=2675743.2771828
 */

@Extension(
        name = "akslack",
        namespace = "reorder",
        description = "Stream processor performs reordering of out-of-order events optimized for a given" +
                "parameter using [AQ-K-Slack algorithm](http://dl.acm.org/citation.cfm?doid=2675743.2771828). " +
                "This is best for reordering events on attributes those are used for aggregations." +
                "data .",
        parameters = {
                @Parameter(name = "timestamp",
                        description = "The event timestamp on which the events should be ordered.",
                        type = {DataType.LONG},
                        dynamic = true),
                @Parameter(name = "correlation.field",
                        description = "By monitoring the changes in this field Alpha K-Slack dynamically optimises " +
                                "its behavior. This field is used to calculate the runtime window coverage " +
                                "threshold, which represents the upper limit set for unsuccessfully " +
                                "handled late arrivals.",
                        type = {DataType.INT, DataType.FLOAT, DataType.LONG, DataType.DOUBLE},
                        dynamic = true),
                @Parameter(name = "batch.size",
                        description = "The parameter 'batch.size' denotes the number of events that should be " +
                                "considered in the calculation of an alpha value. This should be greater " +
                                "than or equal to 15.",
                        defaultValue = "`10,000`",
                        type = {DataType.LONG},
                        optional = true),
                @Parameter(name = "timeout",
                        description = "A timeout value in milliseconds, where the buffered events who are older " +
                                "than the given timeout period get flushed every second.",
                        defaultValue = "`-1` (timeout is infinite)",
                        type = {DataType.LONG},
                        optional = true),
                @Parameter(name = "max.k",
                        description = "The maximum K-Slack window threshold ('K' parameter).",
                        defaultValue = "`9,223,372,036,854,775,807` (The maximum Long value)",
                        type = {DataType.LONG},
                        optional = true),
                @Parameter(name = "discard.late.arrival",
                        description = "If set to `true` the processor would discarded the out-of-order " +
                                "events arriving later than the K-Slack window, and in otherwise it allows " +
                                "the late arrivals to proceed.",
                        defaultValue = "false",
                        type = {DataType.BOOL},
                        optional = true),
                @Parameter(name = "error.threshold",
                        description = "The error threshold to be applied in Alpha K-Slack algorithm. ",
                        defaultValue = "`0.03` (3%)",
                        type = {DataType.DOUBLE},
                        optional = true),
                @Parameter(name = "confidence.level",
                        description = "The confidence level to be applied in Alpha K-Slack algorithm.",
                        defaultValue = "`0.95` (95%)",
                        type = {DataType.DOUBLE},
                        optional = true)
        },
        parameterOverloads = {
                @ParameterOverload(parameterNames = {"timestamp", "correlation.field"}),
                @ParameterOverload(parameterNames = {"timestamp", "correlation.field", "batch.size"}),
                @ParameterOverload(parameterNames = {"timestamp", "correlation.field", "batch.size", "timeout"}),
                @ParameterOverload(parameterNames = {"timestamp", "correlation.field", "batch.size", "timeout",
                        "max.k"}),
                @ParameterOverload(parameterNames = {"timestamp", "correlation.field", "batch.size", "timeout",
                        "max.k", "discard.late.arrival"}),
                @ParameterOverload(parameterNames = {"timestamp", "correlation.field", "batch.size", "timeout",
                        "max.k", "discard.late.arrival", "error.threshold", "confidence.level"})
        },
        examples = @Example(
                syntax = "define stream StockStream (eventTime long, symbol string, volume long);\n\n" +
                        "@info(name = 'query1')\n" +
                        "from StockStream#reorder:akslack(eventTime, volume, 20)#window.time(5 min)\n" +
                        "select eventTime, symbol, sum(volume) as total\n" +
                        "insert into OutputStream;",
                description = "The query reorders events based on the 'eventTime' attribute value " +
                        "and optimises for aggregating 'volume' attribute considering " +
                        "last 20 events.")
)
public class AlphaKSlackExtension extends StreamProcessor<AlphaKSlackExtension.AlphaKSlackState>
        implements SchedulingProcessor {
    private ExpressionExecutor timestampExecutor;
    private ExpressionExecutor correlationFieldExecutor;
    private Long maxK = Long.MAX_VALUE;
    private Long timeoutDuration = -1L;
    private boolean discardFlag = false;
    private Scheduler scheduler;
    private ReentrantLock lock = new ReentrantLock();
    private Long batchSize = 10000L;
    private double errorThreshold = 0.03;
    private double confidenceLevel = 0.95;
    private double alpha = 1;
    private SiddhiAppContext siddhiAppContext;
    private WindowCoverage windowCoverage;
    private double criticalValue;
    private long l = 0;
    private long windowSize = 10000000000L;
    private boolean needScheduling;

    public AlphaKSlackExtension() {
    }

    @Override
    public List<Attribute> getReturnAttributes() {
        return new ArrayList<>();
    }

    @Override
    public ProcessingMode getProcessingMode() {
        return ProcessingMode.BATCH;
    }

    @Override
    public void start() {
        if (timeoutDuration != -1L) {
            AlphaKSlackState state = stateHolder.getState();
            try {
                if (state.lastScheduledTimestamp < 0) {
                    state.lastScheduledTimestamp = siddhiAppContext.getTimestampGenerator().currentTime() +
                            timeoutDuration;
                    scheduler.notifyAt(state.lastScheduledTimestamp);
                }
            } finally {
                stateHolder.returnState(state);
            }
        }
    }

    @Override
    public void stop() {
        //Do nothing
    }

    @Override
    protected void process(ComplexEventChunk<StreamEvent> streamEventChunk, Processor nextProcessor,
                           StreamEventCloner streamEventCloner, ComplexEventPopulater complexEventPopulater,
                           AlphaKSlackState state) {
        synchronized (state) {
            ComplexEventChunk<StreamEvent> complexEventChunk = new ComplexEventChunk<StreamEvent>(true);
            try {
                lock.lock();
                while (streamEventChunk.hasNext()) {
                    StreamEvent event = streamEventChunk.next();

                    if (event.getType() != ComplexEvent.Type.TIMER) {
                        streamEventChunk.remove();
                        long timestamp = (Long) timestampExecutor.execute(event);
                        state.timestampList.add(timestamp);
                        double correlationField;
                        switch (attributeExpressionExecutors[1].getReturnType()) {
                            case INT:
                                correlationField = (Integer) correlationFieldExecutor.execute(event);
                                break;
                            case LONG:
                                correlationField = (Long) correlationFieldExecutor.execute(event);
                                break;
                            case FLOAT:
                                correlationField = (Float) correlationFieldExecutor.execute(event);
                                break;
                            case DOUBLE:
                                correlationField = (Double) correlationFieldExecutor.execute(event);
                                break;
                            default:
                                //will not occur at all
                                correlationField = 0.0;

                        }
                        state.dataItemList.add(correlationField);
                        if (discardFlag) {
                            if (timestamp < state.lastSentTimestamp) {
                                continue;
                            }
                        }

                        if (needScheduling) {
                            long currentTime = this.siddhiAppContext.getTimestampGenerator().currentTime();
                            state.lastScheduledTimestamp = state.lastScheduledTimestamp +
                                    Math.round(Math.ceil((currentTime - state.lastScheduledTimestamp) / 1000.0)) * 1000;
                            scheduler.notifyAt(state.lastScheduledTimestamp);
                            needScheduling = false;
                        }

                        List<StreamEvent> eventList = state.primaryTreeMap.computeIfAbsent(timestamp,
                                k1 -> new ArrayList<>());
                        eventList.add(event);
                        state.counter += 1;
                        if (state.counter > batchSize) {
                            if (l == 0) {
                                alpha = calculateAlpha(windowCoverage.calculateWindowCoverageThreshold(criticalValue,
                                        state.dataItemList), 1, state);
                                l = Math.round(alpha * state.k);
                                if (l > state.k) {
                                    l = state.k;
                                }
                            } else {
                                alpha = calculateAlpha(windowCoverage.calculateWindowCoverageThreshold(criticalValue,
                                        state.dataItemList),
                                        windowCoverage.calculateRuntimeWindowCoverage(timestamp, state.timestampList,
                                                l, windowSize),
                                        state);
                                l = Math.round(alpha * state.k);
                                if (l > state.k) {
                                    l = state.k;
                                }
                            }
                            state.counter = 0;
                            state.dataItemList.clear();
                        }
                        if (timestamp > state.largestTimestamp) {
                            state.largestTimestamp = timestamp;
                            long minTimestamp = state.primaryTreeMap.firstKey();
                            long timeDifference = state.largestTimestamp - minTimestamp;
                            if (timeDifference > state.k) {
                                if (timeDifference < maxK) {
                                    state.k = Math.round(timeDifference * alpha);
                                } else {
                                    state.k = maxK;
                                }
                            }

                            Iterator<Map.Entry<Long, List<StreamEvent>>> entryIterator =
                                    state.primaryTreeMap.entrySet().iterator();
                            while (entryIterator.hasNext()) {
                                Map.Entry<Long, List<StreamEvent>> entry = entryIterator.next();
                                List<StreamEvent> list = state.secondaryTreeMap.get(entry.getKey());
                                if (list != null) {
                                    list.addAll(entry.getValue());
                                } else {
                                    state.secondaryTreeMap.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                                }
                            }
                            state.primaryTreeMap.clear();
                            entryIterator = state.secondaryTreeMap.entrySet().iterator();
                            while (entryIterator.hasNext()) {
                                Map.Entry<Long, List<StreamEvent>> entry = entryIterator.next();
                                if (entry.getKey() + state.k <= state.largestTimestamp) {
                                    entryIterator.remove();
                                    List<StreamEvent> timeEventList = entry.getValue();
                                    state.lastSentTimestamp = entry.getKey();

                                    for (StreamEvent aTimeEventList : timeEventList) {
                                        complexEventChunk.add(aTimeEventList);
                                    }
                                } else {
                                    break;
                                }
                            }
                        }
                    } else {
                        if (timeoutDuration != -1) {
                            if (state.secondaryTreeMap.size() > 0) {
                                for (Iterator<Map.Entry<Long, List<StreamEvent>>> iterator =
                                     state.secondaryTreeMap.entrySet().iterator(); iterator.hasNext(); ) {
                                    Map.Entry<Long, List<StreamEvent>> longListEntry = iterator.next();
                                    if (longListEntry.getKey() < timeoutDuration + event.getTimestamp()) {
                                        List<StreamEvent> timeEventList = longListEntry.getValue();

                                        for (StreamEvent aTimeEventList : timeEventList) {
                                            complexEventChunk.add(aTimeEventList);
                                        }
                                        iterator.remove();
                                    }
                                }
                            }

                            if (state.primaryTreeMap.size() > 0) {
                                for (Iterator<Map.Entry<Long, List<StreamEvent>>> iterator =
                                     state.primaryTreeMap.entrySet().iterator(); iterator.hasNext(); ) {
                                    Map.Entry<Long, List<StreamEvent>> longListEntry = iterator.next();
                                    if (longListEntry.getKey() < timeoutDuration + event.getTimestamp()) {
                                        List<StreamEvent> timeEventList = longListEntry.getValue();
                                        for (StreamEvent aTimeEventList : timeEventList) {
                                            complexEventChunk.add(aTimeEventList);
                                        }
                                        iterator.remove();
                                    }
                                }
                            }
                            if (state.secondaryTreeMap.size() > 0 || state.primaryTreeMap.size() > 0) {
                                state.lastScheduledTimestamp = state.lastScheduledTimestamp + 1000;
                                scheduler.notifyAt(state.lastScheduledTimestamp);
                                needScheduling = false;
                            } else {
                                needScheduling = true;
                            }
                        }
                    }
                }
            } catch (ArrayIndexOutOfBoundsException ec) {
                //This happens due to user specifying an invalid field index.
                throw new SiddhiAppCreationException("The very first parameter must be an " +
                        "Integer with a valid " +
                        " field index (0 to (fieldsLength-1)).");
            } finally {
                lock.unlock();
            }
            nextProcessor.process(complexEventChunk);
        }
    }

    @Override
    protected StateFactory<AlphaKSlackState> init(MetaStreamEvent metaStreamEvent,
                                                  AbstractDefinition abstractDefinition,
                                                  ExpressionExecutor[] expressionExecutors, ConfigReader configReader,
                                                  StreamEventClonerHolder streamEventClonerHolder,
                                                  boolean outputExpectsExpiredEvents, boolean findToBeExecuted,
                                                  SiddhiQueryContext siddhiQueryContext) {
        this.siddhiAppContext = siddhiQueryContext.getSiddhiAppContext();
        if (attributeExpressionLength > 8 || attributeExpressionLength < 2
                || attributeExpressionLength == 7) {
            throw new SiddhiAppCreationException("Number of expected input parameters " +
                    "are 2 to 6 or 8. But found " + attributeExpressionLength + " attributes.");
        }

        if (attributeExpressionExecutors.length >= 2) {
            if (attributeExpressionExecutors[0].getReturnType() == Attribute.Type.LONG) {
                timestampExecutor = attributeExpressionExecutors[0];
            } else {
                throw new SiddhiAppCreationException("Invalid parameter type found for " +
                        "the first argument of " +
                        " reorder:akslack() function. Required LONG, but "
                        + "found "
                        +
                        attributeExpressionExecutors[0].getReturnType());
            }

            switch (attributeExpressionExecutors[1].getReturnType()) {
                case INT:
                case LONG:
                case FLOAT:
                case DOUBLE:
                    correlationFieldExecutor = attributeExpressionExecutors[1];
                    break;
                case BOOL:
                case OBJECT:
                case STRING:
                    throw new SiddhiAppCreationException("Invalid parameter type found for " +
                            "the second argument of reorder:akslack() function. Required INT, " +
                            "FLOAT, DOUBLE, or LONG but found " + attributeExpressionExecutors[1].getReturnType());
            }
        }
        if (attributeExpressionExecutors.length >= 3) {
            if (attributeExpressionExecutors[2] instanceof ConstantExpressionExecutor) {
                if (attributeExpressionExecutors[2].getReturnType() == Attribute.Type.LONG) {
                    batchSize = (Long) ((ConstantExpressionExecutor)
                            attributeExpressionExecutors[2]).getValue();
                } else {
                    throw new SiddhiAppCreationException("Invalid parameter type found " +
                            "for the third argument of " +
                            " reorder:akslack() function. Required LONG, but"
                            + " found "
                            +
                            attributeExpressionExecutors[2].getReturnType());
                }
            } else {
                throw new SiddhiAppCreationException("Batch size parameter must be a constant.");
            }

        }
        if (attributeExpressionExecutors.length >= 4) {
            if (attributeExpressionExecutors[3] instanceof ConstantExpressionExecutor) {
                if (attributeExpressionExecutors[3].getReturnType() == Attribute.Type.LONG) {
                    timeoutDuration = (Long) ((ConstantExpressionExecutor)
                            attributeExpressionExecutors[3]).getValue();
                } else {
                    throw new SiddhiAppCreationException("Invalid parameter type found for " +
                            "the fourth argument of " +
                            " reorder:akslack() function. Required LONG, but"
                            + " found "
                            +
                            attributeExpressionExecutors[3].getReturnType());
                }
            } else {
                throw new SiddhiAppCreationException("timeoutDuration must be a constant");
            }
        }
        if (attributeExpressionExecutors.length >= 5) {
            if (attributeExpressionExecutors[4] instanceof ConstantExpressionExecutor) {
                if (attributeExpressionExecutors[4].getReturnType() == Attribute.Type.LONG) {
                    maxK = (Long) ((ConstantExpressionExecutor)
                            attributeExpressionExecutors[4]).getValue();
                    if (maxK == -1) {
                        maxK = Long.MAX_VALUE;
                    }
                } else {
                    throw new SiddhiAppCreationException("Invalid parameter type found " +
                            "for the fifth argument of " +
                            " reorder:akslack() function. Required LONG, but"
                            + " found "
                            +
                            attributeExpressionExecutors[4].getReturnType());
                }
            } else {
                throw new SiddhiAppCreationException("maxK must be a constant");
            }
        }
        if (attributeExpressionExecutors.length >= 6) {
            if (attributeExpressionExecutors[5] instanceof ConstantExpressionExecutor) {
                if (attributeExpressionExecutors[5].getReturnType() == Attribute.Type.BOOL) {
                    discardFlag = (Boolean) attributeExpressionExecutors[5].execute(null);
                } else {
                    throw new SiddhiAppCreationException("Invalid parameter type found " +
                            "for the sixth argument of " +
                            " reorder:akslack() function. Required BOOL, but"
                            + " found "
                            +
                            attributeExpressionExecutors[5].getReturnType());
                }
            } else {
                throw new SiddhiAppCreationException("discardFlag must be a constant");
            }
        }
        if (attributeExpressionExecutors.length == 8) {
            if ((attributeExpressionExecutors[6] instanceof ConstantExpressionExecutor) &&
                    attributeExpressionExecutors[7] instanceof ConstantExpressionExecutor) {
                if (attributeExpressionExecutors[6].getReturnType() == Attribute.Type.DOUBLE) {
                    errorThreshold = (Double) ((ConstantExpressionExecutor)
                            attributeExpressionExecutors[6]).getValue();
                } else {
                    throw new SiddhiAppCreationException("Invalid parameter type found " +
                            "for the seventh argument of " +
                            " reorder:akslack() function. Required DOUBLE, "
                            + "but found "
                            +
                            attributeExpressionExecutors[6].getReturnType());
                }
                if (attributeExpressionExecutors[7].getReturnType() == Attribute.Type.DOUBLE) {
                    confidenceLevel = (Double) ((ConstantExpressionExecutor)
                            attributeExpressionExecutors[7]).getValue();
                } else {
                    throw new SiddhiAppCreationException("Invalid parameter type found for " +
                            "the eighth argument of " +
                            " reorder:akslack() function. Required DOUBLE, "
                            + "but found "
                            + attributeExpressionExecutors[7].getReturnType());
                }
            } else {
                throw new SiddhiAppCreationException("errorThreshold and " +
                        "confidenceLevel must be constants");
            }
        }
        NormalDistribution actualDistribution = new NormalDistribution();
        criticalValue = Math.abs(actualDistribution.inverseCumulativeProbability
                ((1 - confidenceLevel) / 2));
        windowCoverage = new WindowCoverage(errorThreshold);

        return () -> new AlphaKSlackState();
    }

    @Override
    public Scheduler getScheduler() {
        return this.scheduler;
    }

    @Override
    public void setScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    private double calculateAlpha(double windowCoverageThreshold, double runtimeWindowCoverage,
                                  AlphaKSlackState state) {
        double error = windowCoverageThreshold - runtimeWindowCoverage;
        double deltaAlpha = (state.kp * error) + (state.kd * (error - state.previousError));
        double alpha = Math.abs(state.previousAlpha + deltaAlpha);
        state.previousError = error;
        state.previousAlpha = alpha;
        return alpha;
    }

    class AlphaKSlackState extends State {
        private Long k = 0L; //In the beginning the K is zero.
        private Long largestTimestamp = 0L; //Used to track the greatest timestamp of tuples seen so far.
        private Long lastSentTimestamp = -1L;
        private Long lastScheduledTimestamp = -1L;
        private double previousAlpha = 0;
        private Integer counter = 0;
        private double previousError = 0;
        private double kp = 0.5; // Weight configuration parameters
        private double kd = 0.8;
        private TreeMap<Long, List<StreamEvent>> primaryTreeMap;
        private TreeMap<Long, List<StreamEvent>> secondaryTreeMap;
        private List<Double> dataItemList = new ArrayList<Double>();
        private List<Long> timestampList = new ArrayList<Long>();

        public AlphaKSlackState() {
            primaryTreeMap = new TreeMap<>();
            secondaryTreeMap = new TreeMap<>();
        }

        @Override
        public boolean canDestroy() {
            return false;
        }

        @Override
        public Map<String, Object> snapshot() {
            Map<String, Object> state = new HashMap<>();
            state.put("k", k);
            state.put("largestTimestamp", largestTimestamp);
            state.put("lastSentTimestamp", lastSentTimestamp);
            state.put("lastScheduledTimestamp", lastScheduledTimestamp);
            state.put("previousAlpha", previousAlpha);
            state.put("counter", counter);
            state.put("previousError", previousError);
            state.put("kp", kp);
            state.put("kd", kd);
            state.put("primaryTreeMap", primaryTreeMap);
            state.put("secondaryTreeMap", secondaryTreeMap);
            state.put("dataItemList", dataItemList);
            state.put("timestampList", timestampList);
            return state;
        }

        @Override
        public void restore(Map<String, Object> state) {
            k = (Long) state.get("k");
            largestTimestamp = (Long) state.get("largestTimestamp");
            lastSentTimestamp = (Long) state.get("lastSentTimestamp");
            lastScheduledTimestamp = (Long) state.get("lastScheduledTimestamp");
            previousAlpha = (Double) state.get("previousAlpha");
            counter = (Integer) state.get("counter");
            previousError = (Double) state.get("previousError");
            kp = (Double) state.get("kp");
            kd = (Double) state.get("kd");
            primaryTreeMap = (TreeMap<Long, List<StreamEvent>>) state.get("primaryTreeMap");
            secondaryTreeMap = (TreeMap<Long, List<StreamEvent>>) state.get("secondaryTreeMap");
            dataItemList = (List<Double>) state.get("dataItemList");
            timestampList = (List<Long>) state.get("timestampList");
        }
    }
}
