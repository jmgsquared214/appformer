/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.uberfire.backend.server.io.watch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.ejb.AccessTimeout;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.ejb.TransactionAttribute;
import javax.enterprise.event.Event;
import javax.inject.Inject;

import org.jboss.errai.security.shared.api.identity.UserImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.uberfire.backend.server.util.Filter;
import org.uberfire.backend.vfs.Path;
import org.uberfire.commons.data.Pair;
import org.uberfire.java.nio.base.WatchContext;
import org.uberfire.java.nio.file.StandardWatchEventKind;
import org.uberfire.java.nio.file.WatchEvent;
import org.uberfire.java.nio.file.WatchKey;
import org.uberfire.rpc.SessionInfo;
import org.uberfire.rpc.impl.SessionInfoImpl;
import org.uberfire.workbench.events.ResourceAdded;
import org.uberfire.workbench.events.ResourceAddedEvent;
import org.uberfire.workbench.events.ResourceBatchChangesEvent;
import org.uberfire.workbench.events.ResourceChange;
import org.uberfire.workbench.events.ResourceDeleted;
import org.uberfire.workbench.events.ResourceDeletedEvent;
import org.uberfire.workbench.events.ResourceEvent;
import org.uberfire.workbench.events.ResourceRenamed;
import org.uberfire.workbench.events.ResourceRenamedEvent;
import org.uberfire.workbench.events.ResourceUpdated;
import org.uberfire.workbench.events.ResourceUpdatedEvent;

import static javax.ejb.TransactionAttributeType.NOT_SUPPORTED;
import static org.uberfire.backend.server.util.Paths.convert;

@Singleton
@Startup
@TransactionAttribute(NOT_SUPPORTED)
@AccessTimeout(value = 30, unit = java.util.concurrent.TimeUnit.SECONDS)
public class IOWatchServiceExecutorImpl implements IOWatchServiceExecutor {

    @Inject
    private Event<ResourceBatchChangesEvent> resourceBatchChanges;

    @Inject
    private Event<ResourceUpdatedEvent> resourceUpdatedEvent;

    @Inject
    private Event<ResourceRenamedEvent> resourceRenamedEvent;

    @Inject
    private Event<ResourceDeletedEvent> resourceDeletedEvent;

    @Inject
    private Event<ResourceAddedEvent> resourceAddedEvent;

    public void setEvents(final Event<ResourceBatchChangesEvent> resourceBatchChanges,
                          final Event<ResourceUpdatedEvent> resourceUpdatedEvent,
                          final Event<ResourceRenamedEvent> resourceRenamedEvent,
                          final Event<ResourceDeletedEvent> resourceDeletedEvent,
                          final Event<ResourceAddedEvent> resourceAddedEvent) {
        this.resourceBatchChanges = resourceBatchChanges;
        this.resourceUpdatedEvent = resourceUpdatedEvent;
        this.resourceRenamedEvent = resourceRenamedEvent;
        this.resourceDeletedEvent = resourceDeletedEvent;
        this.resourceAddedEvent = resourceAddedEvent;
    }

    @Override
    public void execute(final WatchKey wk,
                        final Filter<WatchEvent<?>> filter) {
        final List<WatchEvent<?>> events = wk.pollEvents();
        WatchContext firstContext = null;

        if (events.size() > 1) {
            final Map<Path, Collection<ResourceChange>> changes = new HashMap<>();
            for (final WatchEvent event : events) {
                if (!filter.doFilter(event)) {
                    if (firstContext == null) {
                        firstContext = (WatchContext) event.context();
                    }
                    final Pair<Path, ResourceChange> result = buildChange(event);
                    if (result != null) {
                        if (!changes.containsKey(result.getK1())) {
                            changes.put(result.getK1(),
                                        new ArrayList<>());
                        }
                        changes.get(result.getK1()).add(result.getK2());
                    }
                }
            }
            if (changes.size() == 1 && changes.values().size() == 1) {
                final ResourceChange _event = changes.values().iterator().next().iterator().next();
                if (_event instanceof ResourceUpdated) {
                    resourceUpdatedEvent.fire((ResourceUpdatedEvent) toEvent(changes.keySet().iterator().next(),
                                                                             _event,
                                                                             firstContext));
                } else if (_event instanceof ResourceAdded) {
                    resourceAddedEvent.fire((ResourceAddedEvent) toEvent(changes.keySet().iterator().next(),
                                                                         _event,
                                                                         firstContext));
                } else if (_event instanceof ResourceRenamed) {
                    resourceRenamedEvent.fire((ResourceRenamedEvent) toEvent(changes.keySet().iterator().next(),
                                                                             _event,
                                                                             firstContext));
                } else if (_event instanceof ResourceDeleted) {
                    resourceDeletedEvent.fire((ResourceDeletedEvent) toEvent(changes.keySet().iterator().next(),
                                                                             _event,
                                                                             firstContext));
                }
            } else if (changes.size() > 1) {
                resourceBatchChanges.fire(new ResourceBatchChangesEvent(changes,
                                                                        message(firstContext),
                                                                        sessionInfo(firstContext)));
            }
        } else if (events.size() == 1) {
            final WatchEvent<?> event = events.get(0);
            if (!filter.doFilter(event)) {
                if (event.kind().equals(StandardWatchEventKind.ENTRY_MODIFY)) {
                    resourceUpdatedEvent.fire(buildEvent(ResourceUpdatedEvent.class,
                                                         event).getK2());
                } else if (event.kind().equals(StandardWatchEventKind.ENTRY_CREATE)) {
                    resourceAddedEvent.fire(buildEvent(ResourceAddedEvent.class,
                                                       event).getK2());
                } else if (event.kind().equals(StandardWatchEventKind.ENTRY_RENAME)) {
                    resourceRenamedEvent.fire(buildEvent(ResourceRenamedEvent.class,
                                                         event).getK2());
                } else if (event.kind().equals(StandardWatchEventKind.ENTRY_DELETE)) {
                    resourceDeletedEvent.fire(buildEvent(ResourceDeletedEvent.class,
                                                         event).getK2());
                }
            }
        }
    }

    private String message(final WatchContext context) {
        if (context == null) {
            return null;
        }
        return context.getMessage();
    }

    private <T extends ResourceEvent> Pair<Path, T> buildEvent(final Class<T> clazz,
                                                               final WatchEvent<?> event) {
        final WatchContext context = (WatchContext) event.context();

        final Path _affectedPath;
        final T result;
        if (event.kind().equals(StandardWatchEventKind.ENTRY_MODIFY)) {
            _affectedPath = convert(context.getOldPath());
            result = (T) new ResourceUpdatedEvent(_affectedPath,
                                                  context.getMessage(),
                                                  sessionInfo(context));
        } else if (event.kind().equals(StandardWatchEventKind.ENTRY_CREATE)) {
            _affectedPath = convert(context.getPath());
            result = (T) new ResourceAddedEvent(_affectedPath,
                                                context.getMessage(),
                                                sessionInfo(context));
        } else if (event.kind().equals(StandardWatchEventKind.ENTRY_RENAME)) {
            _affectedPath = convert(context.getOldPath());
            result = (T) new ResourceRenamedEvent(_affectedPath,
                                                  convert(context.getPath()),
                                                  context.getMessage(),
                                                  sessionInfo(context));
        } else if (event.kind().equals(StandardWatchEventKind.ENTRY_DELETE)) {
            _affectedPath = convert(context.getOldPath());
            result = (T) new ResourceDeletedEvent(_affectedPath,
                                                  context.getMessage(),
                                                  sessionInfo(context));
        } else {
            _affectedPath = null;
            result = null;
        }
        if (_affectedPath == null) {
            return null;
        }

        return Pair.newPair(_affectedPath,
                            result);
    }

    private Pair<Path, ResourceChange> buildChange(final WatchEvent<?> event) {
        final WatchContext context = (WatchContext) event.context();

        final Path _affectedPath;
        final ResourceChange result;
        if (event.kind().equals(StandardWatchEventKind.ENTRY_MODIFY)) {
            _affectedPath = convert(context.getOldPath());
            result = new ResourceUpdated(context.getMessage());
        } else if (event.kind().equals(StandardWatchEventKind.ENTRY_CREATE)) {
            _affectedPath = convert(context.getPath());
            result = new ResourceAdded(context.getMessage());
        } else if (event.kind().equals(StandardWatchEventKind.ENTRY_RENAME)) {
            _affectedPath = convert(context.getOldPath());
            result = new ResourceRenamed(convert(context.getPath()),
                                         context.getMessage());
        } else if (event.kind().equals(StandardWatchEventKind.ENTRY_DELETE)) {
            _affectedPath = convert(context.getOldPath());
            result = new ResourceDeleted(context.getMessage());
        } else {
            _affectedPath = null;
            result = null;
        }
        if (_affectedPath == null) {
            return null;
        }

        return Pair.newPair(_affectedPath,
                            result);
    }

    private ResourceEvent toEvent(final Path path,
                                  final ResourceChange change,
                                  final WatchContext context) {
        if (change instanceof ResourceUpdated) {
            return new ResourceUpdatedEvent(path,
                                            context.getMessage(),
                                            sessionInfo(context));
        } else if (change instanceof ResourceAdded) {
            return new ResourceAddedEvent(path,
                                          context.getMessage(),
                                          sessionInfo(context));
        } else if (change instanceof ResourceRenamed) {
            return new ResourceRenamedEvent(path,
                                            ((ResourceRenamed) change).getDestinationPath(),
                                            context.getMessage(),
                                            sessionInfo(context));
        } else if (change instanceof ResourceDeleted) {
            return new ResourceDeletedEvent(path,
                                            context.getMessage(),
                                            sessionInfo(context));
        }
        return null;
    }

    private SessionInfo sessionInfo(final WatchContext context) {
        final String sessionId;
        final String user;
        if (context.getSessionId() == null) {
            sessionId = "<system>";
        } else {
            sessionId = context.getSessionId();
        }
        if (context.getUser() == null) {
            user = "<system>";
        } else {
            user = context.getUser();
        }

        return new SessionInfoImpl(sessionId,
                                   new UserImpl(user));
    }
}
