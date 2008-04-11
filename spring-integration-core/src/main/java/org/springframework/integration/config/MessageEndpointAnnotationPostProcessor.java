/*
 * Copyright 2002-2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.config;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.OrderComparator;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.integration.ConfigurationException;
import org.springframework.integration.adapter.DefaultTargetAdapter;
import org.springframework.integration.adapter.MethodInvokingSource;
import org.springframework.integration.adapter.MethodInvokingTarget;
import org.springframework.integration.adapter.PollingSourceAdapter;
import org.springframework.integration.annotation.Aggregator;
import org.springframework.integration.annotation.CompletionStrategy;
import org.springframework.integration.annotation.Concurrency;
import org.springframework.integration.annotation.DefaultOutput;
import org.springframework.integration.annotation.Handler;
import org.springframework.integration.annotation.MessageEndpoint;
import org.springframework.integration.annotation.Polled;
import org.springframework.integration.annotation.Router;
import org.springframework.integration.annotation.Splitter;
import org.springframework.integration.bus.MessageBus;
import org.springframework.integration.channel.ChannelRegistryAware;
import org.springframework.integration.channel.MessageChannel;
import org.springframework.integration.channel.SimpleChannel;
import org.springframework.integration.endpoint.ConcurrencyPolicy;
import org.springframework.integration.endpoint.DefaultMessageEndpoint;
import org.springframework.integration.handler.AbstractMessageHandlerAdapter;
import org.springframework.integration.handler.MessageHandler;
import org.springframework.integration.handler.MessageHandlerChain;
import org.springframework.integration.handler.config.DefaultMessageHandlerCreator;
import org.springframework.integration.handler.config.MessageHandlerCreator;
import org.springframework.integration.message.Message;
import org.springframework.integration.router.AggregatingMessageHandler;
import org.springframework.integration.router.CompletionStrategyAdapter;
import org.springframework.integration.router.config.AggregatorMessageHandlerCreator;
import org.springframework.integration.router.config.RouterMessageHandlerCreator;
import org.springframework.integration.router.config.SplitterMessageHandlerCreator;
import org.springframework.integration.scheduling.PollingSchedule;
import org.springframework.integration.scheduling.Subscription;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * A {@link BeanPostProcessor} implementation that generates endpoints for
 * classes annotated with {@link MessageEndpoint @MessageEndpoint}.
 * 
 * @author Mark Fisher
 * @author Marius Bogoevici
 */
public class MessageEndpointAnnotationPostProcessor implements BeanPostProcessor, InitializingBean {

	private final Log logger = LogFactory.getLog(this.getClass());

	private final Map<Class<? extends Annotation>, MessageHandlerCreator> handlerCreators = new ConcurrentHashMap<Class<? extends Annotation>, MessageHandlerCreator>();

	private final MessageBus messageBus;


	public MessageEndpointAnnotationPostProcessor(MessageBus messageBus) {
		Assert.notNull(messageBus, "'messageBus' must not be null");
		this.messageBus = messageBus;
	}


	public void setCustomHandlerCreators(Map<Class<? extends Annotation>, MessageHandlerCreator> customHandlerCreators) {
		for (Map.Entry<Class<? extends Annotation>, MessageHandlerCreator> entry : customHandlerCreators.entrySet()) {
			this.handlerCreators.put(entry.getKey(), entry.getValue());
		}
	}

	public void afterPropertiesSet() {
		this.handlerCreators.put(Handler.class, new DefaultMessageHandlerCreator());
		this.handlerCreators.put(Router.class, new RouterMessageHandlerCreator());
		this.handlerCreators.put(Splitter.class, new SplitterMessageHandlerCreator());
		this.handlerCreators.put(Aggregator.class, new AggregatorMessageHandlerCreator(messageBus));
	}

	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		Class<?> beanClass = this.getBeanClass(bean);
		MessageEndpoint endpointAnnotation = AnnotationUtils.findAnnotation(beanClass, MessageEndpoint.class);
		if (endpointAnnotation == null) {
			return bean;
		}
		if (bean instanceof ChannelRegistryAware) {
			((ChannelRegistryAware) bean).setChannelRegistry(this.messageBus);
		}
		String defaultOutputChannelName = endpointAnnotation.defaultOutput();
		MessageHandlerChain handlerChain = this.createHandlerChain(bean, defaultOutputChannelName);
		DefaultMessageEndpoint endpoint = new DefaultMessageEndpoint(handlerChain);
		this.configureInput(bean, beanName, endpointAnnotation, endpoint);
		if (StringUtils.hasText(defaultOutputChannelName)) {
			endpoint.setDefaultOutputChannelName(defaultOutputChannelName);
		}
		else {
			this.configureDefaultOutput(bean, beanName, endpoint);
		}
		Concurrency concurrencyAnnotation = AnnotationUtils.findAnnotation(beanClass, Concurrency.class);
		if (concurrencyAnnotation != null) {
			ConcurrencyPolicy concurrencyPolicy = new ConcurrencyPolicy(concurrencyAnnotation.coreSize(),
					concurrencyAnnotation.maxSize());
			concurrencyPolicy.setKeepAliveSeconds(concurrencyAnnotation.keepAliveSeconds());
			concurrencyPolicy.setQueueCapacity(concurrencyAnnotation.queueCapacity());
			endpoint.setConcurrencyPolicy(concurrencyPolicy);
		}
		if (endpoint.getHandler() == null) {
			endpoint.setHandler(new MessageHandler() {
				public Message<?> handle(Message<?> message) {
					return message;
				}
			});
		}
		this.configureCompletionStrategy(bean, endpoint);
		this.messageBus.registerEndpoint(beanName + "-endpoint", endpoint);
		return bean;
	}

	private void configureInput(final Object bean, final String beanName, MessageEndpoint annotation,
			final DefaultMessageEndpoint endpoint) {
		String channelName = annotation.input();
		if (StringUtils.hasText(channelName)) {
			Subscription subscription = new Subscription(channelName);
			endpoint.setSubscription(subscription);
		}
		ReflectionUtils.doWithMethods(this.getBeanClass(bean), new ReflectionUtils.MethodCallback() {
			public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
				Annotation annotation = AnnotationUtils.getAnnotation(method, Polled.class);
				if (annotation != null) {
					Polled polledAnnotation = (Polled) annotation;
					int period = polledAnnotation.period();
					long initialDelay = polledAnnotation.initialDelay();
					boolean fixedRate = polledAnnotation.fixedRate();
					MethodInvokingSource<Object> source = new MethodInvokingSource<Object>();
					source.setObject(bean);
					source.setMethod(method.getName());
					PollingSourceAdapter<Object> adapter = new PollingSourceAdapter<Object>(source);
					MessageChannel channel = new SimpleChannel();
					adapter.setChannel(channel);
					adapter.setPeriod(period);
					String channelName = beanName + "-inputChannel";
					messageBus.registerChannel(channelName, channel);
					messageBus.registerSourceAdapter(beanName + "-sourceAdapter", adapter);
					PollingSchedule schedule = new PollingSchedule(period);
					schedule.setInitialDelay(initialDelay);
					schedule.setFixedRate(fixedRate);
					Subscription subscription = new Subscription(channel, schedule);
					endpoint.setSubscription(subscription);
				}
			}
		});
	}

	private void configureDefaultOutput(final Object bean, final String beanName, final DefaultMessageEndpoint endpoint) {
		ReflectionUtils.doWithMethods(this.getBeanClass(bean), new ReflectionUtils.MethodCallback() {
			boolean foundDefaultOutput = false;
			public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
				Annotation annotation = AnnotationUtils.getAnnotation(method, DefaultOutput.class);
				if (annotation != null) {
					if (foundDefaultOutput) {
						throw new ConfigurationException("only one @DefaultOutput allowed per endpoint");
					}
					MethodInvokingTarget<Object> target = new MethodInvokingTarget<Object>();
					target.setObject(bean);
					target.setMethod(method.getName());
					target.afterPropertiesSet();
					DefaultTargetAdapter<Object> adapter = new DefaultTargetAdapter<Object>(target);
					MessageHandler handler = endpoint.getHandler();
					if (handler == null) {
						endpoint.setHandler(adapter);
					}
					else if (handler instanceof MessageHandlerChain) {
						((MessageHandlerChain) handler).add(adapter);
					}
					else {
						MessageHandlerChain chain = new MessageHandlerChain();
						chain.add(handler);
						chain.add(adapter);
					}
					foundDefaultOutput = true;
					return;
				}
			}
		});
	}

	private void configureCompletionStrategy(final Object bean, final DefaultMessageEndpoint endpoint) {
		ReflectionUtils.doWithMethods(bean.getClass(), new ReflectionUtils.MethodCallback() {
			public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
				Annotation annotation = AnnotationUtils.getAnnotation(method, CompletionStrategy.class);
				if (annotation != null) {
					final MessageHandler endpointHandler = endpoint.getHandler();
					AggregatingMessageHandler aggregatingMessageHandler = null;
					if (endpointHandler != null) {
						if (endpointHandler instanceof MessageHandlerChain) {
							for (MessageHandler handlerInChain : ((MessageHandlerChain) endpointHandler).getHandlers()) {
								if (handlerInChain instanceof AggregatingMessageHandler) {
									aggregatingMessageHandler = (AggregatingMessageHandler) handlerInChain;
									break;
								}
							}
						}
						else if (endpointHandler instanceof AggregatingMessageHandler) {
							aggregatingMessageHandler = (AggregatingMessageHandler) endpointHandler;
						}
					}
					if (aggregatingMessageHandler == null) {
						throw new ConfigurationException(
								"@CompletionStrategy supported only when @Aggregator is present");
					}
					else {
						aggregatingMessageHandler.setCompletionStrategy(new CompletionStrategyAdapter(bean, method));
					}
				}
			}
		});
	}

	@SuppressWarnings("unchecked")
	private MessageHandlerChain createHandlerChain(final Object bean, final String defaultOutputChannelName) {
		final List<MessageHandler> handlers = new ArrayList<MessageHandler>();
		ReflectionUtils.doWithMethods(this.getBeanClass(bean), new ReflectionUtils.MethodCallback() {
			public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
				Annotation[] annotations = AnnotationUtils.getAnnotations(method);
				for (Annotation annotation : annotations) {
					if (isHandlerAnnotation(annotation)) {
						Map<String, Object> attributes = AnnotationUtils.getAnnotationAttributes(annotation);
						attributes.put(AbstractMessageHandlerAdapter.DEFAULT_OUTPUT_CHANNEL_NAME_KEY, defaultOutputChannelName);
						MessageHandlerCreator handlerCreator = handlerCreators.get(annotation.annotationType());
						if (handlerCreator == null) {
							if (logger.isWarnEnabled()) {
								logger.warn("No handler creator has been registered for handler annotation '"
										+ annotation.annotationType() + "'");
							}
						}
						else {
							MessageHandler handler = handlerCreator.createHandler(bean, method, attributes);
							if (handler instanceof ChannelRegistryAware) {
								((ChannelRegistryAware) handler).setChannelRegistry(messageBus);
							}
							if (handler instanceof InitializingBean) {
								try {
									((InitializingBean) handler).afterPropertiesSet();
								}
								catch (Exception e) {
									throw new ConfigurationException("failed to create handler", e);
								}
							}
							if (handler != null) {
								handlers.add(handler);
							}
						}
					}
				}
			}
		});
		if (handlers.size() > 0) {
			MessageHandlerChain handlerChain = new MessageHandlerChain();
			Collections.sort(handlers, new OrderComparator());
			for (MessageHandler handler : handlers) {
				handlerChain.add(handler);
			}
			return handlerChain;
		}
		return null;
	}

	private Class<?> getBeanClass(Object bean) {
		return AopUtils.getTargetClass(bean);
	}

	private boolean isHandlerAnnotation(Annotation annotation) {
		return annotation.annotationType().equals(Handler.class)
				|| annotation.annotationType().isAnnotationPresent(Handler.class)
				|| this.handlerCreators.keySet().contains(annotation.annotationType());
	}

}
