/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.servlet.handler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;

import kotlin.reflect.KFunction;
import kotlin.reflect.jvm.ReflectJvmMapping;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.KotlinDetector;
import org.springframework.core.MethodIntrospector;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Abstract base class for {@link HandlerMapping} implementations that define
 * a mapping between a request and a {@link HandlerMethod}.
 *
 * <p>For each registered handler method, a unique mapping is maintained with
 * subclasses defining the details of the mapping type {@code <T>}.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.1
 * @param <T> the mapping for a {@link HandlerMethod} containing the conditions
 * needed to match the handler method to an incoming request.
 */
public abstract class AbstractHandlerMethodMapping<T> extends AbstractHandlerMapping implements InitializingBean {

	/**
	 * Bean name prefix for target beans behind scoped proxies. Used to exclude those
	 * targets from handler method detection, in favor of the corresponding proxies.
	 * <p>We're not checking the autowire-candidate status here, which is how the
	 * proxy target filtering problem is being handled at the autowiring level,
	 * since autowire-candidate may have been turned to {@code false} for other
	 * reasons, while still expecting the bean to be eligible for handler methods.
	 * <p>Originally defined in {@link org.springframework.aop.scope.ScopedProxyUtils}
	 * but duplicated here to avoid a hard dependency on the spring-aop module.
	 */
	private static final String SCOPED_TARGET_NAME_PREFIX = "scopedTarget.";

	private static final HandlerMethod PREFLIGHT_AMBIGUOUS_MATCH =
			new HandlerMethod(new EmptyHandler(), ClassUtils.getMethod(EmptyHandler.class, "handle"));

	private static final CorsConfiguration ALLOW_CORS_CONFIG = new CorsConfiguration();

	static {
		ALLOW_CORS_CONFIG.addAllowedOrigin("*");
		ALLOW_CORS_CONFIG.addAllowedMethod("*");
		ALLOW_CORS_CONFIG.addAllowedHeader("*");
		ALLOW_CORS_CONFIG.setAllowCredentials(true);
	}


	private boolean detectHandlerMethodsInAncestorContexts = false;
    //***Mapping 命名策略***
	@Nullable
	private HandlerMethodMappingNamingStrategy<T> namingStrategy;
	//***Mapping 注册类***
	private final MappingRegistry mappingRegistry = new MappingRegistry();


	/**
	 * Whether to detect handler methods in beans in ancestor ApplicationContexts.
	 * <p>Default is "false": Only beans in the current ApplicationContext are
	 * considered, i.e. only in the context that this HandlerMapping itself
	 * is defined in (typically the current DispatcherServlet's context).
	 * <p>Switch this flag on to detect handler beans in ancestor contexts
	 * (typically the Spring root WebApplicationContext) as well.
	 * @see #getCandidateBeanNames()
	 */
	public void setDetectHandlerMethodsInAncestorContexts(boolean detectHandlerMethodsInAncestorContexts) {
		this.detectHandlerMethodsInAncestorContexts = detectHandlerMethodsInAncestorContexts;
	}

	/**
	 * Configure the naming strategy to use for assigning a default name to every
	 * mapped handler method.
	 * <p>The default naming strategy is based on the capital letters of the
	 * class name followed by "#" and then the method name, e.g. "TC#getFoo"
	 * for a class named TestController with method getFoo.
	 */
	public void setHandlerMethodMappingNamingStrategy(HandlerMethodMappingNamingStrategy<T> namingStrategy) {
		this.namingStrategy = namingStrategy;
	}

	/**
	 * Return the configured naming strategy or {@code null}.
	 */
	@Nullable
	public HandlerMethodMappingNamingStrategy<T> getNamingStrategy() {
		return this.namingStrategy;
	}

	/**
	 * Return a (read-only) map with all mappings and HandlerMethod's.
	 */
	public Map<T, HandlerMethod> getHandlerMethods() {
		this.mappingRegistry.acquireReadLock();
		try {
			return Collections.unmodifiableMap(this.mappingRegistry.getMappings());
		}
		finally {
			this.mappingRegistry.releaseReadLock();
		}
	}

	/**
	 * Return the handler methods for the given mapping name.
	 * @param mappingName the mapping name
	 * @return a list of matching HandlerMethod's or {@code null}; the returned
	 * list will never be modified and is safe to iterate.
	 * @see #setHandlerMethodMappingNamingStrategy
	 */
	@Nullable
	public List<HandlerMethod> getHandlerMethodsForMappingName(String mappingName) {
		return this.mappingRegistry.getHandlerMethodsByMappingName(mappingName);
	}

	/**
	 * Return the internal mapping registry. Provided for testing purposes.
	 */
	MappingRegistry getMappingRegistry() {
		return this.mappingRegistry;
	}

	/**
	 * Register the given mapping.
	 * <p>This method may be invoked at runtime after initialization has completed.
	 * @param mapping the mapping for the handler method
	 * @param handler the handler
	 * @param method the method
	 */
	public void registerMapping(T mapping, Object handler, Method method) {
		if (logger.isTraceEnabled()) {
			logger.trace("Register \"" + mapping + "\" to " + method.toGenericString());
		}
		this.mappingRegistry.register(mapping, handler, method);
	}

	/**
	 * Un-register the given mapping.
	 * <p>This method may be invoked at runtime after initialization has completed.
	 * @param mapping the mapping to unregister
	 */
	public void unregisterMapping(T mapping) {
		if (logger.isTraceEnabled()) {
			logger.trace("Unregister mapping \"" + mapping + "\"");
		}
		this.mappingRegistry.unregister(mapping);
	}


	// Handler method detection

	/**
	 * Detects handler methods at initialization.
	 * @see #initHandlerMethods
	 */
	@Override
	public void afterPropertiesSet() {
		//Map的初始化
		initHandlerMethods();
	}

	/**
	 * Scan beans in the ApplicationContext, detect and register handler methods.
	 * @see #getCandidateBeanNames()
	 * @see #processCandidateBean
	 * @see #handlerMethodsInitialized
	 */
	/**
	 * @Author MTSS
	 * @Description 初始化处理器方法
	 * @Date 14:30 2019/9/29
	 * @Param []
	 * @return void
	 **/
	protected void initHandlerMethods() {
		//***从容器中获取注册的Bean***
		for (String beanName : getCandidateBeanNames()) {
			if (!beanName.startsWith(SCOPED_TARGET_NAME_PREFIX)) {
				//***处理 Bean***
				processCandidateBean(beanName);
			}
		}
		//***初始化处理器的方法们。目前是空方法，暂无具体的实现***
		handlerMethodsInitialized(getHandlerMethods());
	}

	/**
	 * Determine the names of candidate beans in the application context.
	 * @since 5.1
	 * @see #setDetectHandlerMethodsInAncestorContexts
	 * @see BeanFactoryUtils#beanNamesForTypeIncludingAncestors
	 */
	protected String[] getCandidateBeanNames() {
		return (this.detectHandlerMethodsInAncestorContexts ?
				BeanFactoryUtils.beanNamesForTypeIncludingAncestors(obtainApplicationContext(), Object.class) :
				obtainApplicationContext().getBeanNamesForType(Object.class));
	}

	/**
	 * Determine the type of the specified candidate bean and call
	 * {@link #detectHandlerMethods} if identified as a handler type.
	 * <p>This implementation avoids bean creation through checking
	 * {@link org.springframework.beans.factory.BeanFactory#getType}
	 * and calling {@link #detectHandlerMethods} with the bean name.
	 * @param beanName the name of the candidate bean
	 * @since 5.1
	 * @see #isHandler
	 * @see #detectHandlerMethods
	 */
	protected void processCandidateBean(String beanName) {
		//***获得 Bean 对应的类型***
		Class<?> beanType = null;
		try {
			beanType = obtainApplicationContext().getType(beanName);
		}
		catch (Throwable ex) {
			// An unresolvable bean type, probably from a lazy bean - let's ignore it.
			if (logger.isTraceEnabled()) {
				logger.trace("Could not resolve type for bean '" + beanName + "'", ex);
			}
		}
		//isHandler是一个模板方法，主要的作用是检查方法的类是否存在@Controller和@RequestMapping
		if (beanType != null && isHandler(beanType)) {
			//***保存Handler到Map里***
			detectHandlerMethods(beanName);
		}
	}

	/**
	 * Look for handler methods in the specified handler bean.
	 * @param handler either a bean name or an actual handler instance
	 * @see #getMappingForMethod
	 */
	/**
	 * @Author MTSS
	 * @Description 扫描处理方法
	 * @Date 14:32 2019/9/29
	 * @Param [handler]
	 * @return void
	 **/
	protected void detectHandlerMethods(Object handler) {
		//***获得handler的类型***
		Class<?> handlerType = (handler instanceof String ?
				obtainApplicationContext().getType((String) handler) : handler.getClass());

		if (handlerType != null) {
			//***获得真实的类。因为，handlerType 可能是代理类***
			Class<?> userType = ClassUtils.getUserClass(handlerType);
			//***获得匹配的方法的集合***
			Map<Method, T> methods = MethodIntrospector.selectMethods(userType,
					(MethodIntrospector.MetadataLookup<T>) method -> {
						try {
							return getMappingForMethod(method, userType);
						}
						catch (Throwable ex) {
							throw new IllegalStateException("Invalid mapping on handler class [" +
									userType.getName() + "]: " + method, ex);
						}
					});
			if (logger.isTraceEnabled()) {
				logger.trace(formatMappings(userType, methods));
			}
			//***遍历方法，逐个注册 HandlerMethod***
			methods.forEach((method, mapping) -> {
				Method invocableMethod = AopUtils.selectInvocableMethod(method, userType);
				//将符合要求的method注册到Map中
				registerHandlerMethod(handler, invocableMethod, mapping);
			});
		}
	}

	private String formatMappings(Class<?> userType, Map<Method, T> methods) {
		String formattedType = Arrays.stream(ClassUtils.getPackageName(userType).split("\\."))
				.map(p -> p.substring(0, 1))
				.collect(Collectors.joining(".", "", "." + userType.getSimpleName()));
		Function<Method, String> methodFormatter = method -> Arrays.stream(method.getParameterTypes())
				.map(Class::getSimpleName)
				.collect(Collectors.joining(",", "(", ")"));
		return methods.entrySet().stream()
				.map(e -> {
					Method method = e.getKey();
					return e.getValue() + ": " + method.getName() + methodFormatter.apply(method);
				})
				.collect(Collectors.joining("\n\t", "\n\t" + formattedType + ":" + "\n\t", ""));
	}

	/**
	 * Register a handler method and its unique mapping. Invoked at startup for
	 * each detected handler method.
	 * @param handler the bean name of the handler or the handler instance
	 * @param method the method to register
	 * @param mapping the mapping conditions associated with the handler method
	 * @throws IllegalStateException if another method was already registered
	 * under the same mapping
	 */
	protected void registerHandlerMethod(Object handler, Method method, T mapping) {
		//注册HandlerMethod
		this.mappingRegistry.register(mapping, handler, method);
	}

	/**
	 * Create the HandlerMethod instance.
	 * @param handler either a bean name or an actual handler instance
	 * @param method the target method
	 * @return the created HandlerMethod
	 */
	/**
	 * @Author MTSS
	 * @Description 创建 HandlerMethod
	 * @Date 9:24 2019/9/29
	 * @Param [handler, method]
	 * @return org.springframework.web.method.HandlerMethod
	 **/
	protected HandlerMethod createHandlerMethod(Object handler, Method method) {
		//***如果 handler 的类型为String，则表明此handler为一个Bean 对象***
		if (handler instanceof String) {
			return new HandlerMethod((String) handler,
					obtainApplicationContext().getAutowireCapableBeanFactory(), method);
		}
		//***如果handler是非String类型，则表明这已经是一个handler对象，即HandlerMethod是一个handler + method***
		return new HandlerMethod(handler, method);
	}

	/**
	 * Extract and return the CORS configuration for the mapping.
	 */
	@Nullable
	protected CorsConfiguration initCorsConfiguration(Object handler, Method method, T mapping) {
		return null;
	}

	/**
	 * Invoked after all handler methods have been detected.
	 * @param handlerMethods a read-only map with handler methods and mappings.
	 */
	protected void handlerMethodsInitialized(Map<T, HandlerMethod> handlerMethods) {
		// Total includes detected mappings + explicit registrations via registerMapping
		int total = handlerMethods.size();
		if ((logger.isTraceEnabled() && total == 0) || (logger.isDebugEnabled() && total > 0) ) {
			logger.debug(total + " mappings in " + formatMappingName());
		}
	}


	// Handler method lookup

	/**
	 * Look up a handler method for the given request.
	 */
	/**
	 * @Author MTSS
	 * @Description  处理Method的handler
	 * @Date 16:40 2019/9/25
	 * @Param [request]
	 * @return org.springframework.web.method.HandlerMethod
	 **/
	@Override
	protected HandlerMethod getHandlerInternal(HttpServletRequest request) throws Exception {
		//***获得请求的路径***
		String lookupPath = getUrlPathHelper().getLookupPathForRequest(request);
		request.setAttribute(LOOKUP_PATH, lookupPath);
		//***获得写锁***
		this.mappingRegistry.acquireReadLock();
		try {
			//***获得 HandlerMethod 对象***
			HandlerMethod handlerMethod = lookupHandlerMethod(lookupPath, request);
			//***进一步，获得 HandlerMethod 对象***
			return (handlerMethod != null ? handlerMethod.createWithResolvedBean() : null);
		}
		finally {
			//***释放写锁***
			this.mappingRegistry.releaseReadLock();
		}
	}

	/**
	 * Look up the best-matching handler method for the current request.
	 * If multiple matches are found, the best match is selected.
	 * @param lookupPath mapping lookup path within the current servlet mapping
	 * @param request the current request
	 * @return the best-matching handler method, or {@code null} if no match
	 * @see #handleMatch(Object, String, HttpServletRequest)
	 * @see #handleNoMatch(Set, String, HttpServletRequest)
	 */
	/**
	 * @Author MTSS
	 * @Description 获得 HandlerMethod 对象
	 * @Date 11:58 2019/9/29
	 * @Param [lookupPath, request]
	 * @return org.springframework.web.method.HandlerMethod
	 **/
	@Nullable
	protected HandlerMethod lookupHandlerMethod(String lookupPath, HttpServletRequest request) throws Exception {
		//***Match 数组，存储匹配上当前请求的结果***
		List<Match> matches = new ArrayList<>();
		//***优先，基于直接 URL 的 Mapping 们，进行匹配***
		List<T> directPathMatches = this.mappingRegistry.getMappingsByUrl(lookupPath);
		if (directPathMatches != null) {
			addMatchingMappings(directPathMatches, matches, request);
		}
		//***其次，扫描注册表的 Mapping 们，进行匹配***
		if (matches.isEmpty()) {
			// No choice but to go through all mappings...
			addMatchingMappings(this.mappingRegistry.getMappings().keySet(), matches, request);
		}
        //***如果匹配到，则获取最佳匹配的 Match 对象的 handlerMethod 属性***
		if (!matches.isEmpty()) {
			Comparator<Match> comparator = new MatchComparator(getMappingComparator(request));
			matches.sort(comparator);
			Match bestMatch = matches.get(0);
			if (matches.size() > 1) {
				if (logger.isTraceEnabled()) {
					logger.trace(matches.size() + " matching mappings: " + matches);
				}
				if (CorsUtils.isPreFlightRequest(request)) {
					return PREFLIGHT_AMBIGUOUS_MATCH;
				}
				Match secondBestMatch = matches.get(1);
				if (comparator.compare(bestMatch, secondBestMatch) == 0) {
					Method m1 = bestMatch.handlerMethod.getMethod();
					Method m2 = secondBestMatch.handlerMethod.getMethod();
					String uri = request.getRequestURI();
					throw new IllegalStateException(
							"Ambiguous handler methods mapped for '" + uri + "': {" + m1 + ", " + m2 + "}");
				}
			}
			request.setAttribute(BEST_MATCHING_HANDLER_ATTRIBUTE, bestMatch.handlerMethod);
			handleMatch(bestMatch.mapping, lookupPath, request);
			return bestMatch.handlerMethod;
		}
		else {
			return handleNoMatch(this.mappingRegistry.getMappings().keySet(), lookupPath, request);
		}
	}

	private void addMatchingMappings(Collection<T> mappings, List<Match> matches, HttpServletRequest request) {
		for (T mapping : mappings) {
			T match = getMatchingMapping(mapping, request);
			if (match != null) {
				matches.add(new Match(match, this.mappingRegistry.getMappings().get(mapping)));
			}
		}
	}

	/**
	 * Invoked when a matching mapping is found.
	 * @param mapping the matching mapping
	 * @param lookupPath mapping lookup path within the current servlet mapping
	 * @param request the current request
	 */
	protected void handleMatch(T mapping, String lookupPath, HttpServletRequest request) {
		request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, lookupPath);
	}

	/**
	 * Invoked when no matching mapping is not found.
	 * @param mappings all registered mappings
	 * @param lookupPath mapping lookup path within the current servlet mapping
	 * @param request the current request
	 * @throws ServletException in case of errors
	 */
	@Nullable
	protected HandlerMethod handleNoMatch(Set<T> mappings, String lookupPath, HttpServletRequest request)
			throws Exception {

		return null;
	}

	@Override
	protected boolean hasCorsConfigurationSource(Object handler) {
		return super.hasCorsConfigurationSource(handler) ||
				(handler instanceof HandlerMethod && this.mappingRegistry.getCorsConfiguration((HandlerMethod) handler) != null) ||
				handler.equals(PREFLIGHT_AMBIGUOUS_MATCH);
	}

	@Override
	protected CorsConfiguration getCorsConfiguration(Object handler, HttpServletRequest request) {
		CorsConfiguration corsConfig = super.getCorsConfiguration(handler, request);
		if (handler instanceof HandlerMethod) {
			HandlerMethod handlerMethod = (HandlerMethod) handler;
			if (handlerMethod.equals(PREFLIGHT_AMBIGUOUS_MATCH)) {
				return AbstractHandlerMethodMapping.ALLOW_CORS_CONFIG;
			}
			else {
				CorsConfiguration corsConfigFromMethod = this.mappingRegistry.getCorsConfiguration(handlerMethod);
				corsConfig = (corsConfig != null ? corsConfig.combine(corsConfigFromMethod) : corsConfigFromMethod);
			}
		}
		return corsConfig;
	}


	// Abstract template methods

	/**
	 * Whether the given type is a handler with handler methods.
	 * @param beanType the type of the bean being checked
	 * @return "true" if this a handler type, "false" otherwise.
	 */
	protected abstract boolean isHandler(Class<?> beanType);

	/**
	 * Provide the mapping for a handler method. A method for which no
	 * mapping can be provided is not a handler method.
	 * @param method the method to provide a mapping for
	 * @param handlerType the handler type, possibly a sub-type of the method's
	 * declaring class
	 * @return the mapping, or {@code null} if the method is not mapped
	 */
	@Nullable
	protected abstract T getMappingForMethod(Method method, Class<?> handlerType);

	/**
	 * Extract and return the URL paths contained in the supplied mapping.
	 */
	protected abstract Set<String> getMappingPathPatterns(T mapping);

	/**
	 * Check if a mapping matches the current request and return a (potentially
	 * new) mapping with conditions relevant to the current request.
	 * @param mapping the mapping to get a match for
	 * @param request the current HTTP servlet request
	 * @return the match, or {@code null} if the mapping doesn't match
	 */
	@Nullable
	protected abstract T getMatchingMapping(T mapping, HttpServletRequest request);

	/**
	 * Return a comparator for sorting matching mappings.
	 * The returned comparator should sort 'better' matches higher.
	 * @param request the current request
	 * @return the comparator (never {@code null})
	 */
	protected abstract Comparator<T> getMappingComparator(HttpServletRequest request);


	/**
	 * A registry that maintains all mappings to handler methods, exposing methods
	 * to perform lookups and providing concurrent access.
	 * <p>Package-private for testing purposes.
	 */
	/**
	 * @Author MTSS
	 * @Description T代表着匹配条件【模式】（也就是RequestCondition）
	 * @Date 14:06 2019/9/29
	 * @Param
	 * @return
	 **/
	class MappingRegistry {
        //***注册表1***
		private final Map<T, MappingRegistration<T>> registry = new HashMap<>();
        //***一个匹配模式对应一个handler方法***
		private final Map<T, HandlerMethod> mappingLookup = new LinkedHashMap<>();
        //***URL 映射，一个url接口可以对应多个匹配模式***
		private final MultiValueMap<String, T> urlLookup = new LinkedMultiValueMap<>();
        //***Mapping名字 与 HandlerMethod映射***
		private final Map<String, List<HandlerMethod>> nameLookup = new ConcurrentHashMap<>();

		private final Map<HandlerMethod, CorsConfiguration> corsLookup = new ConcurrentHashMap<>();
        //***读写锁***
		private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();

		/**
		 * Return all mappings and handler methods. Not thread-safe.
		 * @see #acquireReadLock()
		 */
		public Map<T, HandlerMethod> getMappings() {
			return this.mappingLookup;
		}

		/**
		 * Return matches for the given URL path. Not thread-safe.
		 * @see #acquireReadLock()
		 */
		@Nullable
		public List<T> getMappingsByUrl(String urlPath) {
			return this.urlLookup.get(urlPath);
		}

		/**
		 * Return handler methods by mapping name. Thread-safe for concurrent use.
		 */
		public List<HandlerMethod> getHandlerMethodsByMappingName(String mappingName) {
			return this.nameLookup.get(mappingName);
		}

		/**
		 * Return CORS configuration. Thread-safe for concurrent use.
		 */
		@Nullable
		public CorsConfiguration getCorsConfiguration(HandlerMethod handlerMethod) {
			HandlerMethod original = handlerMethod.getResolvedFromHandlerMethod();
			return this.corsLookup.get(original != null ? original : handlerMethod);
		}

		/**
		 * Acquire the read lock when using getMappings and getMappingsByUrl.
		 */
		/**
		 * @Author MTSS
		 * @Description 获取读锁
		 * @Date 14:57 2019/9/27
		 * @Param []
		 * @return void
		 **/
		public void acquireReadLock() {
			this.readWriteLock.readLock().lock();
		}

		/**
		 * Release the read lock after using getMappings and getMappingsByUrl.
		 */
		/**
		 * @Author MTSS
		 * @Description 释放读锁
		 * @Date 14:58 2019/9/27
		 * @Param []
		 * @return void
		 **/
		public void releaseReadLock() {
			this.readWriteLock.readLock().unlock();
		}

		/**
		 * @Author MTSS
		 * @Description 注册，获取Mapping
		 * @Date 14:58 2019/9/27
		 * @Param [mapping, handler, method]
		 * @return void
		 **/
		public void register(T mapping, Object handler, Method method) {
			// Assert that the handler method is not a suspending one.
			if (KotlinDetector.isKotlinType(method.getDeclaringClass()) && KotlinDelegate.isSuspend(method)) {
				throw new IllegalStateException("Unsupported suspending handler method detected: " + method);
			}
			//***获取写锁***
			this.readWriteLock.writeLock().lock();
			try {
				//***创建 HandlerMethod 对象***
				HandlerMethod handlerMethod = createHandlerMethod(handler, method);
				//***校验当前 mapping 不存在，否则抛出 IllegalStateException 异常***
				validateMethodMapping(handlerMethod, mapping);
				//***添加 mapping + HandlerMethod 到 mappingLookup 中***
				 this.mappingLookup.put(mapping, handlerMethod);
				 //***获得 mapping 对应的普通 URL 数组***
				 List<String> directUrls = getDirectUrls(mapping);
				 //***添加到 url + mapping 到 urlLookup 集合中***
				 for (String url : directUrls) {
				 this.urlLookup.add(url, mapping);
				 }
                //***初始化 nameLookup***
				String name = null;
				if (getNamingStrategy() != null) {
					//***获得 Mapping 的名字***
					name = getNamingStrategy().getName(handlerMethod, mapping);
					//***添加到 mapping 的名字 + HandlerMethod 到 nameLookup 中***
					addMappingName(name, handlerMethod);
				}

				CorsConfiguration corsConfig = initCorsConfiguration(handler, method, mapping);
				if (corsConfig != null) {
					this.corsLookup.put(handlerMethod, corsConfig);
				}
                //***mapping + handlerMethod + directUrls + name -> registry***
				this.registry.put(mapping, new MappingRegistration<>(mapping, handlerMethod, directUrls, name));
			}
			finally {
				//***释放写锁***
				this.readWriteLock.writeLock().unlock();
			}
		}
        /**
         * @Author MTSS
         * @Description 校验当前的 Mapping 是否存在
         * @Date 8:37 2019/9/29
         * @Param [handlerMethod, mapping]
         * @return void
         **/
		private void validateMethodMapping(HandlerMethod handlerMethod, T mapping) {
			// Assert that the supplied mapping is unique.
			HandlerMethod existingHandlerMethod = this.mappingLookup.get(mapping);
			if (existingHandlerMethod != null && !existingHandlerMethod.equals(handlerMethod)) {
				throw new IllegalStateException(
						"Ambiguous mapping. Cannot map '" + handlerMethod.getBean() + "' method \n" +
						handlerMethod + "\nto " + mapping + ": There is already '" +
						existingHandlerMethod.getBean() + "' bean method\n" + existingHandlerMethod + " mapped.");
			}
		}
        /**
         * @Author MTSS
         * @Description 获得 mapping 对应的直接 URL 数组
         * @Date 15:23 2019/9/27
         * @Param [mapping]
         * @return java.util.List<java.lang.String>
         **/ 
		private List<String> getDirectUrls(T mapping) {
			List<String> urls = new ArrayList<>(1);
			//***遍历 Mapping 对应的路径***
			for (String path : getMappingPathPatterns(mapping)) {
				//***非**模式**路径***
				if (!getPathMatcher().isPattern(path)) {
					urls.add(path);
				}
			}
			return urls;
		}
        /**
         * @Author MTSS
         * @Description 添加 mapping 的名字 + HandlerMethod 到 nameLookup 中
         * @Date 8:42 2019/9/29
         * @Param [name, handlerMethod]
         * @return void
         **/
		private void addMappingName(String name, HandlerMethod handlerMethod) {
			//*** 获得 Mapping 的名字，对应的 HandlerMethod 数组***
			List<HandlerMethod> oldList = this.nameLookup.get(name);
			if (oldList == null) {
				oldList = Collections.emptyList();
			}
            //***如果已经存在，则不用添加***
			for (HandlerMethod current : oldList) {
				if (handlerMethod.equals(current)) {
					return;
				}
			}
            //***添加到 nameLookup 中，重新创建的原因是，保证数组的大小固定。因为，基本不太存在扩容的可能性，申请大了就浪费了***
			List<HandlerMethod> newList = new ArrayList<>(oldList.size() + 1);
			newList.addAll(oldList);
			newList.add(handlerMethod);
			this.nameLookup.put(name, newList);
		}
        /**
         * @Author MTSS
         * @Description 取消注册
         * @Date 8:47 2019/9/29
         * @Param [mapping]
         * @return void
         **/
		public void unregister(T mapping) {
			//***获得写锁***
			this.readWriteLock.writeLock().lock();
			try {
				//***从 registry 中移除***
				MappingRegistration<T> definition = this.registry.remove(mapping);
				if (definition == null) {
					return;
				}
                //***从 mappingLookup 中移除***
				this.mappingLookup.remove(definition.getMapping());
                //***从 urlLookup 移除***
				for (String url : definition.getDirectUrls()) {
					List<T> list = this.urlLookup.get(url);
					if (list != null) {
						list.remove(definition.getMapping());
						if (list.isEmpty()) {
							this.urlLookup.remove(url);
						}
					}
				}
                //***从 nameLookup 移除***
				removeMappingName(definition);
                //***从 corsLookup 中移除***
				this.corsLookup.remove(definition.getHandlerMethod());
			}
			finally {
				//***释放写锁***
				this.readWriteLock.writeLock().unlock();
			}
		}

		private void removeMappingName(MappingRegistration<T> definition) {
			String name = definition.getMappingName();
			if (name == null) {
				return;
			}
			HandlerMethod handlerMethod = definition.getHandlerMethod();
			List<HandlerMethod> oldList = this.nameLookup.get(name);
			if (oldList == null) {
				return;
			}
			if (oldList.size() <= 1) {
				this.nameLookup.remove(name);
				return;
			}
			List<HandlerMethod> newList = new ArrayList<>(oldList.size() - 1);
			for (HandlerMethod current : oldList) {
				if (!current.equals(handlerMethod)) {
					newList.add(current);
				}
			}
			this.nameLookup.put(name, newList);
		}
	}

    /**
     * @Author MTSS
     * @Description 注册类
     * @Date 16:06 2019/9/29
     * @Param
     * @return
     **/
	private static class MappingRegistration<T> {
        //***Mapping 对象***
		private final T mapping;
        //***HandlerMethod 对象***
		private final HandlerMethod handlerMethod;
        //***直接 URL 数组***
		private final List<String> directUrls;
		/**
		 * {@link #mapping} 的名字
		 */
		@Nullable
		private final String mappingName;

		public MappingRegistration(T mapping, HandlerMethod handlerMethod,
				@Nullable List<String> directUrls, @Nullable String mappingName) {

			Assert.notNull(mapping, "Mapping must not be null");
			Assert.notNull(handlerMethod, "HandlerMethod must not be null");
			this.mapping = mapping;
			this.handlerMethod = handlerMethod;
			this.directUrls = (directUrls != null ? directUrls : Collections.emptyList());
			this.mappingName = mappingName;
	}

		public T getMapping() {
			return this.mapping;
		}

		public HandlerMethod getHandlerMethod() {
			return this.handlerMethod;
		}

		public List<String> getDirectUrls() {
			return this.directUrls;
		}

		@Nullable
		public String getMappingName() {
			return this.mappingName;
		}
	}


	/**
	 * A thin wrapper around a matched HandlerMethod and its mapping, for the purpose of
	 * comparing the best match with a comparator in the context of the current request.
	 */
	private class Match {

		private final T mapping;

		private final HandlerMethod handlerMethod;

		public Match(T mapping, HandlerMethod handlerMethod) {
			this.mapping = mapping;
			this.handlerMethod = handlerMethod;
		}

		@Override
		public String toString() {
			return this.mapping.toString();
		}
	}


	private class MatchComparator implements Comparator<Match> {

		private final Comparator<T> comparator;

		public MatchComparator(Comparator<T> comparator) {
			this.comparator = comparator;
		}

		@Override
		public int compare(Match match1, Match match2) {
			return this.comparator.compare(match1.mapping, match2.mapping);
		}
	}


	private static class EmptyHandler {

		@SuppressWarnings("unused")
		public void handle() {
			throw new UnsupportedOperationException("Not implemented");
		}
	}

	/**
	 * Inner class to avoid a hard dependency on Kotlin at runtime.
	 */
	private static class KotlinDelegate {

		static private boolean isSuspend(Method method) {
			KFunction<?> function = ReflectJvmMapping.getKotlinFunction(method);
			return function != null && function.isSuspend();
		}
	}

}
