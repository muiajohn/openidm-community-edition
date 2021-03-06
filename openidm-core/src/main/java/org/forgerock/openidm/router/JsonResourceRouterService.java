/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyrighted [year] [name of copyright owner]".
 *
 * Copyright © 2011 ForgeRock AS. All rights reserved.
 */

package org.forgerock.openidm.router;

// Java SE
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

// SLF4J
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// OSGi
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.Filter;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.ComponentException;

// Felix SCR
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Modified;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.scr.annotations.ReferenceStrategy;
import org.apache.felix.scr.annotations.Service;

// JSON Fluent
import org.forgerock.json.fluent.JsonValue;
import org.forgerock.json.fluent.JsonValueException;

// JSON Resource
import org.forgerock.json.resource.JsonResource;
import org.forgerock.json.resource.JsonResourceException;
import org.forgerock.json.resource.JsonResourceFilter;
import org.forgerock.json.resource.JsonResourceFilterChain;
import org.forgerock.json.resource.JsonResourceRouter;
import org.forgerock.json.resource.SimpleJsonResource.Method;

// OpenIDM
import org.forgerock.openidm.config.JSONEnhancedConfig;
import org.forgerock.openidm.objset.ForbiddenException;
import org.forgerock.openidm.objset.InternalServerErrorException;
import org.forgerock.openidm.osgi.ServiceTrackerNotifier;
import org.forgerock.openidm.osgi.ServiceTrackerListener;
import org.forgerock.openidm.scope.ScopeFactory;
import org.forgerock.openidm.script.Script;
import org.forgerock.openidm.script.ScriptException;
import org.forgerock.openidm.script.ScriptThrownException;
import org.forgerock.openidm.script.Scripts;
import org.forgerock.openidm.smartevent.EventEntry;
import org.forgerock.openidm.smartevent.Name;
import org.forgerock.openidm.smartevent.Publisher;

/**
 * Provides internal routing for a top-level object set.
 *
 * @author Paul C. Bryan
 * @author aegloff
 */
@Component(
    name = "org.forgerock.openidm.router",
    policy = ConfigurationPolicy.OPTIONAL,
    metatype = true,
    configurationFactory = false,
    immediate = true
)
@Properties({
    @Property(name = "service.description", value = "OpenIDM internal JSON resource router"),
    @Property(name = "service.vendor", value = "ForgeRock AS"),
    @Property(name = "openidm.restlet.path", value = "/")
})
@Service
public class JsonResourceRouterService implements JsonResource {

    /** TODO: Description. */
    private final static Logger LOGGER = LoggerFactory.getLogger(JsonResourceRouterService.class);

    /** TODO: Description. */
    private static final String PREFIX_PROPERTY = "openidm.router.prefix";

    /**
     * Event name prefix for monitoring the router
     */
    public final static String EVENT_ROUTER_PREFIX = "openidm/internal/router/";
    
    /** TODO: Description. */
    private final Router router = new Router();

    /** TODO: Description. */
    private Chain chain = new Chain(router);

    /** TODO: Description. */
    private ComponentContext context;

    /** 
     * Currently handled via service tracker instead, 
     * until SCR implementation properly resolves and cleanly shuts down this scneario 
     * 1.6.0 Logs exceptions at shut-down
     * 1.6.2 Misses some registered events / getService returns null
     */
/*    @Reference(
        name = "ref_JsonResourceRouterService_JsonResource",
        referenceInterface = JsonResource.class,
        bind = "bind",
        unbind = "unbind",
        cardinality = ReferenceCardinality.OPTIONAL_MULTIPLE,
        policy = ReferencePolicy.DYNAMIC,
        strategy = ReferenceStrategy.EVENT
    )
    protected int _dummy; // whiteboard pattern
    protected synchronized void bind(JsonResource resource, Map<String, Object> properties) {
        Object prefix = properties.get(PREFIX_PROPERTY);
        if (prefix != null && prefix instanceof String) { // service is specified as internally routable
            router.addRoute((String)prefix, resource);
        }
    }
    protected synchronized void unbind(JsonResource resource, Map<String, Object> properties) {
        Object prefix = properties.get(PREFIX_PROPERTY);
        if (prefix != null && prefix instanceof String) { // service is specified as internally routable
            router.removeRoute((String)prefix);
        }
    }
*/
    /** Scope factory service. */
    @Reference(
        name = "ref_JsonResourceRouterService_ScopeFactory",
        referenceInterface = ScopeFactory.class,
        bind = "bindScopeFactory",
        unbind = "unbindScopeFactory",
        cardinality = ReferenceCardinality.MANDATORY_UNARY,
        policy = ReferencePolicy.DYNAMIC
    )
    private ScopeFactory scopeFactory;
    protected void bindScopeFactory(ScopeFactory scopeFactory) {
        this.scopeFactory = scopeFactory;
        this.scopeFactory.setRouter(this);
    }
    protected void unbindScopeFactory(ScopeFactory scopeFactory) {
        this.scopeFactory.setRouter(null);
        this.scopeFactory = null;
    }

    /**
     * Returns {@code true} if the values are === equal.
     */
    private static boolean eq(Object o1, Object o2) {
        return (o1 == o2 || (o1 != null && o1.equals(o2)));
    }

    /**
     * Initialize the router with configuration. Supports modifying router configuration.
     */
    private void init(ComponentContext context) {
        String pid = (String)context.getProperties().get("service.pid");
        String factoryPid = (String)context.getProperties().get("service.factoryPid");
        if (factoryPid != null) {
            LOGGER.warn("Factory config for router not allowed, ignoring config {}-{}", pid, factoryPid );
            return;
        }
        this.context = context;
        try {
            JsonValue config = new JsonValue(new JSONEnhancedConfig().getConfiguration(context));
            Chain chain = new Chain(router);
            for (JsonValue jv : config.get("filters").expect(List.class)) { // optional
                chain.getFilters().add(new Filter(jv));
            }
            this.chain = chain;
        } catch (JsonValueException jve) {
            // The router should stay up for basic support even with invalid config, do not throw Exception
            LOGGER.warn("Router configuration error", jve);
        } catch (Exception e) {
            // The router should stay up for basic support even with invalid config, do not throw Exception
            LOGGER.warn("Failed to configure router", e);
        }
    }

    ServiceTrackerNotifier tracker;
    
    @Activate
    protected synchronized void activate(ComponentContext context) {
        LOGGER.info("Activate router configuration, properties: {}", context.getProperties());
        final BundleContext bundleContext = context.getBundleContext();
        
        // Use a service tracker to register and deregister services on the router
        ServiceTrackerListener listener = new ServiceTrackerListener() {
            public void addedService(ServiceReference reference, Object service) {
                String prefix = (String) reference.getProperty(PREFIX_PROPERTY);
                if (prefix != null) {
                    if (service == null) {
                        LOGGER.warn("Framework issue, service in service tracker is null for {}", prefix);
                    } else {
                        router.addRoute(prefix, (JsonResource) service);
                        LOGGER.debug("Added route for {} {}", prefix, service);
                    }
                }
            }

            public void removedService(ServiceReference reference, Object service) {
                String prefix = (String) reference.getProperty(PREFIX_PROPERTY);
                if (prefix != null) {
                    router.removeRoute(prefix);
                    LOGGER.debug("Removed route for {} {}", prefix);
                }
            }

            public void modifiedService(ServiceReference reference, Object service) {
                String prefix = (String) reference.getProperty(PREFIX_PROPERTY);
                if (prefix != null) {
                    if (service == null) {
                        LOGGER.warn("Framework issue, service in service tracker is null for {}", prefix);
                    } else {
                        router.replaceRoute(prefix, (JsonResource) service);
                        LOGGER.debug("Replaced route for {} {}", prefix, service);
                    }
                }
            }
        };
        tracker = new ServiceTrackerNotifier(context.getBundleContext(), JsonResource.class.getName(), null, listener);
        tracker.open();
        
        init(context);
    }

    @Modified
    protected synchronized void modified(ComponentContext context) {
        LOGGER.debug("Modified router configuration, properties: {}", context.getProperties());
        init(context);
    }

    @Deactivate
    protected synchronized void deactivate(ComponentContext context) {
        chain.getFilters().clear();
        if (tracker != null) {
            tracker.close();
        }
        this.context = null;
    }
    
    /**
     * @param request the router request
     * @return an event name For monitoring purposes
     */
    Name getRouterEventName(JsonValue request) {
        String method = request.get("method").asString();
        String idContext = "";

        // For query and action group statistics by full URI
        if ("query".equals(method) || "action".equals(method)) {
            idContext = request.get("id").asString();
        } else {
            // For CRUD, patch group statistics without the local resource identifier
            idContext = stripLastId(request.get("id").asString());
        }

        String eventName = new StringBuilder(EVENT_ROUTER_PREFIX)
                .append(idContext)
                .append("/")
                .append(method)
                .toString();
        
        return Name.get(eventName);
    }
    
    /**
     * Strips the local resource identifier from a qualified resource identifier
     * @param a qualified resource identifier in String form
     * @return the resource context
     */
    private String stripLastId(String id) {
        String result = id;
        if (id != null) {
            int lastSlash = id.lastIndexOf("/");
            if (lastSlash >= 0) {
                result = id.substring(0, lastSlash);
            }
        }
        return result;
    }
    
    @Override
    public JsonValue handle(JsonValue request) throws JsonResourceException {
        EventEntry measure = Publisher.start(getRouterEventName(request), request, null);
        if (request.get("method").required().asString().equals("create")) {
            String id = request.get("id").asString();
            // For a create with an empty ID, server generate the ID
            // Relies on json resource representing empty local ID with ending /
            if (id != null && id.endsWith("/")) {
                request.put("id", id + UUID.randomUUID().toString());
            }
        }
        try {
            JsonValue response = chain.handle(request); // dispatch to router, via filter chain
            measure.setResult(response);
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Request: {}, Response: {}", request, response);
            }
            return response;
        } catch (JsonResourceException jre) {
            measure.setResult(jre);
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Resource exception: {} processing request: {}", new Object[] {jre.toJsonValue(), request, jre});
            }
            int code = jre.getCode();
            if (code >= 500 && code <= 599) { // HTTP server-side error
                LOGGER.warn("JSON resource exception", jre);
            }
            throw jre;
        } catch (RuntimeException re) {
            measure.setResult(re);
            LOGGER.warn("Uncaught runtime exception processing request: {}", request, re);
            throw new JsonResourceException(JsonResourceException.INTERNAL_ERROR);
        } catch (StackOverflowError sfe) {
            measure.setResult(sfe);
            LOGGER.warn("Uncaught stack overflow error processing request: {}", request, sfe);
            throw new JsonResourceException(JsonResourceException.INTERNAL_ERROR);
        } finally {
            measure.end();
        }
    }

    /**
     * TODO: Description.
     */
    private class Router extends JsonResourceRouter {
        public Map<String, JsonResource> getRoutes() {
            return routes;
        }
        public synchronized void addRoute(String prefix, JsonResource resource) {
            LinkedHashMap<String, JsonResource> copyOnWrite = 
                    new LinkedHashMap<String, JsonResource>(router.getRoutes());
            copyOnWrite.put(prefix, resource);
            routes = copyOnWrite;
            LOGGER.debug("Routes state after add: {}", routes);
        }
        
        public synchronized void removeRoute(String prefix) {
            LinkedHashMap<String, JsonResource> copyOnWrite = 
                    new LinkedHashMap<String, JsonResource>(router.getRoutes());
            copyOnWrite.remove(prefix);
            routes = copyOnWrite;
            LOGGER.debug("Routes state after delete: {}", routes);
        }
        
        public synchronized void replaceRoute(String prefix, JsonResource resource) {
            LinkedHashMap<String, JsonResource> copyOnWrite = 
                    new LinkedHashMap<String, JsonResource>(router.getRoutes());
            copyOnWrite.remove(prefix);
            copyOnWrite.put(prefix, resource);
            routes = copyOnWrite;
            LOGGER.debug("Routes state after replace: {}", routes);
        }
    }

    /**
     * TODO: Description.
     */
    private class Chain extends JsonResourceFilterChain {
        public Chain(JsonResource resource) {
            this.resource = resource;
        }
        public List<JsonResourceFilter> getFilters() {
            return filters;
        }
    }

    /**
     * TODO: Description.
     */
    private class Filter implements JsonResourceFilter {

        /** TODO: Description. */
        private HashSet<String> methods;

        /** TODO: Description. */
        private Pattern pattern;

        /** TODO: Description. */
        private String pointer;

        /** TODO: Description. */
        private Script condition;

        /** TODO: Description. */
        private Script onRequest;

        /** TODO: Description. */
        private Script onResponse;

        /** TODO: Description. */
        private Script onFailure;

        /**
         * TODO: Description.
         *
         * @param config TODO.
         * @throws JsonValueException TODO.
         */
        public Filter(JsonValue config) throws JsonValueException {
            pointer = config.getPointer().toString();
            pattern = config.get("pattern").asPattern();
            List<String> methods = config.get("methods").asList(String.class);
            if (methods != null) {
                this.methods = new HashSet<String>(methods);
            }
            String name = getClass().getName();
            condition = Scripts.newInstance(name, config.get("condition"));
            onRequest = Scripts.newInstance(name, config.get("onRequest"));
            onResponse = Scripts.newInstance(name, config.get("onResponse"));
            onFailure = Scripts.newInstance(name, config.get("onFailure"));
        }

        /**
         * TODO: Description.
         *
         * @param method TODO.
         * @param id TODO.
         * @return TODO.
         */
        private boolean matches(String method, String id) {
            return ((methods == null || methods.contains(method))
             && (pattern == null || (id != null && pattern.matcher(id).matches())));
        }

        /**
         * TODO: Description.
         *
         * @param scope TODO
         * @return TODO.
         * @throws JsonResourceException TODO.
         */
        private boolean evalCondition(Map<String, Object> scope) throws JsonResourceException {
            boolean result = true; // default true unless script proves otherwise
            if (condition != null) {
                try {
                    result = Boolean.TRUE.equals(condition.exec(scope));
                } catch (ScriptException se) {
                    String msg = pointer + " condition script encountered exception";
                    LOGGER.debug(msg, se);
                    throw new JsonResourceException(JsonResourceException.INTERNAL_ERROR, msg, se);
                }
            }
            LOGGER.debug("{} evalCondition yielded {}", pointer, Boolean.toString(result));
            return result;
        }

       /**
         * TODO: Description.
         *
         * @param scope TODO.
         * @throws JsonResourceException TODO.
         */
        private void onRequest(Map<String, Object> scope) throws JsonResourceException {
            if (onRequest != null) {
                LOGGER.debug("Calling {} onRequest script", pointer); 
                try {
                    onRequest.exec(scope);
                } catch (ScriptThrownException ste) {
                    JsonResourceException ex = ste.toJsonResourceException(null);
                    LOGGER.debug("onRequest exception", ste);
                    throw ex;
                } catch (ScriptException se) {
                    String msg = pointer + " onRequest script encountered exception";
                    LOGGER.debug(msg, se);
                    throw se.toJsonResourceException(msg);
                }
            }
        }

        /**
         * TODO: Description.
         *
         * @param scope TODO.
         * @throws JsonResourceException TODO.
         */
        private void onResponse(Map<String, Object> scope) throws JsonResourceException {
            if (onResponse != null) {
                LOGGER.debug("Calling {} onResponse script", pointer); 
                try {
                    onResponse.exec(scope);
                } catch (ScriptException se) {
                    String msg = pointer + " onResponse script encountered exception";
                    LOGGER.debug(msg, se);
                    throw se.toJsonResourceException(msg);
                }
            }
        }

        /**
         * TODO: Description.
         *
         * @param scope TODO.
         * @throws JsonResourceException TODO.
         */
        private void onFailure(Map<String, Object> scope) throws JsonResourceException {
            if (onFailure != null) {
                LOGGER.debug("Calling {} onFailure script", pointer); 
                try {
                    onFailure.exec(scope);
                } catch (ScriptException se) {
                    String msg = pointer + " onFailure script encountered exception";
                    LOGGER.debug(msg, se);
                    throw se.toJsonResourceException(msg);
                }
            }
        }

        /**
         * Filters the JSON resource request and/or JSON resource response. If the
         * {@code method}, {@code id} pattern and {@code condition} match, then the (optional)
         * {@code onRequest}, {@code onResponse} and/or {@code onFailure} scripts are invoked.
         * <p>
         * This method creates a copy of the request value, so unlike the rules specified in
         * the {@code JsonResourceFilter} class, the {@code onRequest} script is allowed to
         * modify the request. The modified request is what will be passed on to the next
         * filter/handler in the chain.
         *
         * @param request the JSON resource request.
         * @param next the next filter or resource in chain.
         * @return the JSON resource response.
         * @throws JsonResourceException if there is an exception handling the request.
         */
        @Override
        public JsonValue filter(JsonValue request, JsonResource next) throws JsonResourceException {
            Map<String, Object> scope = null;
            
            if (matches(request.get("method").asString(), request.get("id").asString())) {
                scope = scopeFactory.newInstance(request);
                scope.put("request", request.getObject());
                if (!evalCondition(scope)) {
                    scope = null; // do not filter
                }
            }
            if (scope != null) {
                onRequest(scope);
                Object r = scope.get("request");
                if (r != null && request != r) { // script replaced request in scope
                    request = new JsonValue(r);
                }
            }
            JsonValue response;
            try {
                response = next.handle(request);
            } catch (JsonResourceException jre) {
                if (scope != null) {
                    scope.put("exception", jre.toJsonValue().getObject());
                    onFailure(scope);
                }
                throw jre;
            }
            if (scope != null) {
                scope.put("response", response == null ? null : response.getObject());
                onResponse(scope);
            }
            return response;
        }
    }
}
