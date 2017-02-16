/**
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/**
 * 
 */
package com.netflix.conductor.core.events;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.conductor.common.metadata.events.EventExecution;
import com.netflix.conductor.common.metadata.events.EventExecution.Status;
import com.netflix.conductor.common.metadata.events.EventHandler;
import com.netflix.conductor.common.metadata.events.EventHandler.Action;
import com.netflix.conductor.core.config.Configuration;
import com.netflix.conductor.core.events.queue.Message;
import com.netflix.conductor.core.events.queue.ObservableQueue;
import com.netflix.conductor.service.ExecutionService;
import com.netflix.conductor.service.MetadataService;

/**
 * @author Viren
 * Event Processor is used to dispatch actions based on the incoming events to execution queue.
 */
@Singleton
public class EventProcessor {

	private static Logger logger = LoggerFactory.getLogger(EventProcessor.class);
	
	private MetadataService ms;
	
	private ExecutionService es;
	
	private ActionProcessor ap;
	
	private Map<String, ObservableQueue> queuesMap = new ConcurrentHashMap<>();
	
	private ExecutorService executors;
	
	@Inject
	public EventProcessor(ExecutionService es, MetadataService ms, ActionProcessor ap, Configuration config) {
		this.es = es;
		this.ms = ms;
		this.ap = ap;

		int executorThreadCount = config.getIntProperty("workflow.event.processor.thread.count", 2);
		this.executors = Executors.newFixedThreadPool(executorThreadCount);
		
		refresh();
		Executors.newScheduledThreadPool(1).scheduleAtFixedRate(() -> refresh(), 60, 60, TimeUnit.SECONDS);
	}
	
	/**
	 * 
	 * @return Returns a map of queues which are active.  Key is event name and value is queue URI
	 */
	public Map<String, String> getQueues() {
		Map<String, String> queues = new HashMap<>();
		queuesMap.entrySet().stream().forEach(q -> queues.put(q.getKey(), q.getValue().getName()));
		return queues;
	}
	
	public Map<String, Map<String, Long>> getQueueSizes() {
		Map<String, Map<String, Long>> queues = new HashMap<>();
		queuesMap.entrySet().stream().forEach(q -> {
			Map<String, Long> size = new HashMap<>();
			size.put(q.getValue().getName(), q.getValue().size());
			queues.put(q.getKey(), size);
		});
		return queues;
	}
	
	private void refresh() {
		Set<String> events = ms.getEventHandlers().stream().map(eh -> eh.getEvent()).collect(Collectors.toSet());
		List<ObservableQueue> created = new LinkedList<>();
		events.stream().forEach(event -> queuesMap.computeIfAbsent(event, s -> {
			ObservableQueue q = EventQueues.getQueue(event);
			created.add(q);
			return q;
		}));
		if(!created.isEmpty()) {
			created.stream().filter(q -> q != null).forEach(queue -> listen(queue));	
		}
	}
	
	private void listen(ObservableQueue queue) {
		queue.observe().subscribe((Message msg) -> handle(queue, msg));
	}
	
	private void handle(ObservableQueue queue, Message msg) {
		
		try {
			
			String payload = msg.getPayload();
			logger.debug("Got Message: " + payload);
			
			
			String event = queue.getType() + ":" + queue.getName();
			List<EventHandler> handlers = ms.getEventHandlersForEvent(event, true);
			logger.debug("Handlers for the event {}, {}", handlers, event);
			
			List<Future<Void>> futures = new LinkedList<>();
			for(EventHandler handler : handlers) {
				int i = 0;
				List<Action> actions = handler.getActions();
				for(Action action : actions) {
					String id = msg.getId() + "_" + i++;
					
					EventExecution ee = new EventExecution(id, msg.getId());
					ee.setCreated(System.currentTimeMillis());
					ee.setEvent(handler.getEvent());
					ee.setName(handler.getName());
					ee.setAction(action.getAction());
					ee.setStatus(Status.IN_PROGRESS);
					if (es.addEventExecution(ee)) {
						Future<Void> future = execute(ee, action, payload);
						futures.add(future);
					} else {
						logger.warn("Duplicate delivery/execution? {}", id);
					}
				}
			}
			
			for (Future<Void> future : futures) {
				try {
					future.get();
				} catch (Exception e) {
					logger.error(e.getMessage(), e);
				}
			}
			
			queue.ack(Arrays.asList(msg));
			
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		}
	}

	private Future<Void> execute(EventExecution ee, Action action, String payload) {
		return executors.submit(()->{
			try {
				
				logger.debug("Executing {} with payload {}", action.getAction(), payload);
				Map<String, Object> output = ap.execute(action, payload);
				if(output != null) {
					ee.getOutput().putAll(output);
				}
				ee.setStatus(Status.COMPLETED);
				es.updateEventExecution(ee);
				
				return null;
			}catch(Exception e) {
				logger.error(e.getMessage(), e);
				ee.setStatus(Status.FAILED);
				ee.getOutput().put("exception", e.getMessage());
				return null;
			}
		});
	}
	
}
