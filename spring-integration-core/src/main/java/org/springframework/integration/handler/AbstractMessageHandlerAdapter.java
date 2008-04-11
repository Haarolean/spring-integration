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

package org.springframework.integration.handler;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.Ordered;
import org.springframework.integration.message.GenericMessage;
import org.springframework.integration.message.Message;
import org.springframework.integration.message.MessageHeader;
import org.springframework.integration.message.MessageMapper;
import org.springframework.util.Assert;

/**
 * Base implementation of the {@link MessageHandler} interface that creates an
 * invoker for the specified method and target object. It also accepts an
 * implementation of the {@link MessageMapper} strategy which it exposes to
 * subclasses for converting the {@link Message} to an object. Likewise, if the
 * method has a non-null return value, a reply message will be generated by the
 * mapper.
 * 
 * @author Mark Fisher
 */
public abstract class AbstractMessageHandlerAdapter<T> implements MessageHandler, Ordered, InitializingBean {

	public static final String DEFAULT_OUTPUT_CHANNEL_NAME_KEY = "defaultOutputChannelName";


	protected final Log logger = LogFactory.getLog(this.getClass());

	private volatile T object;

	private volatile String methodName;

	private volatile HandlerMethodInvoker<T> invoker;

	private volatile int order = Integer.MAX_VALUE;

	private volatile boolean initialized;

	private final Object lifecycleMonitor = new Object();


	public void setObject(T object) {
		Assert.notNull(object, "'object' must not be null");
		this.object = object;
	}

	protected Object getObject() {
		return this.object;
	}

	public void setMethodName(String methodName) {
		Assert.notNull(methodName, "'methodName' must not be null");
		this.methodName = methodName;
	}

	public void setOrder(int order) {
		this.order = order;
	}

	public int getOrder() {
		return this.order;
	}

	public final void afterPropertiesSet() {
		this.validate();
		synchronized (this.lifecycleMonitor) {
			if (this.initialized) {
				return;
			}
			this.invoker = new HandlerMethodInvoker<T>(this.object, this.methodName);
			this.initialized = true;
		}
		this.initialize();
	}

	public final Message<?> handle(Message<?> message) {
		if (!this.initialized) {
			this.afterPropertiesSet();
		}
		Object result = this.doHandle(message, invoker);
		if (result != null) {
			Message<?> reply = (result instanceof Message) ? (Message<?>) result :
					this.createReplyMessage(result, message.getHeader());
			Object correlationId = reply.getHeader().getCorrelationId();
			if (correlationId == null) {
				Object orginalCorrelationId = message.getHeader().getCorrelationId();
				reply.getHeader().setCorrelationId((orginalCorrelationId != null) ?
						orginalCorrelationId : message.getId());
			}
			return reply;
		}
		return null;
	}

	/**
	 * Subclasses may override this method to provide validation upon initialization.
	 */
	protected void validate() {
	}

	/**
	 * Subclasses may override this method to provide additional initialization.
	 */
	protected void initialize() {
	}

	protected Message<?> createReplyMessage(Object payload, MessageHeader originalMessageHeader) {
		return new GenericMessage(payload, originalMessageHeader);
	}

	/**
	 * Subclasses must implement this method. The invoker has been created for
	 * the provided target object and method. May return an object of type
	 * {@link Message}, else rely on the message mapper to convert.
	 */
	protected abstract Object doHandle(Message<?> message, HandlerMethodInvoker<T> invoker);

}
