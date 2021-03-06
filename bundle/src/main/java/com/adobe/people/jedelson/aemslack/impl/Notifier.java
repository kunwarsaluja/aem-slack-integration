/*
 * #%L
 * Copyright (C) 2015 Adobe
 * %%
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
 * #L%
 */
package com.adobe.people.jedelson.aemslack.impl;

import java.io.IOException;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.PropertyUnbounded;
import org.apache.felix.scr.annotations.Reference;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.acs.commons.wcm.AuthorUIHelper;
import com.adobe.granite.comments.Comment;
import com.adobe.granite.comments.CommentCollection;
import com.adobe.granite.comments.CommentingEvent;
import com.adobe.granite.taskmanagement.Task;
import com.adobe.granite.taskmanagement.TaskEvent;
import com.adobe.granite.taskmanagement.TaskEventType;
import com.adobe.granite.taskmanagement.TaskManager;
import com.day.cq.commons.Externalizer;

@Component(policy = ConfigurationPolicy.REQUIRE, metatype = true, label = "AEM Slack Integration - Notifier",
        description = "Event handler for passing AEM notifications to Slack")
public class Notifier {

    public class TaskListener implements EventHandler {
        @Override
        public void handleEvent(Event event) {
            String taskId = PropertiesUtil.toString(event.getProperty(TaskEvent.TASK_ID), null);
            String taskType = PropertiesUtil.toString(event.getProperty(TaskEvent.TASK_EVENT_TYPE_STRING), null);
            if (taskId != null && TaskEventType.TASK_CREATED.name().equals(taskType)) {
                queue.submit(new PostTask(taskId));
            }
        }
    }

    private class CommentListener implements EventHandler {
        @Override
        public void handleEvent(Event event) {
            CommentingEvent ce = CommentingEvent.fromEvent(event);
            if (ce != null) {
                String commentPath = ce.getCommentPath();
                queue.submit(new PostComment(commentPath));
            }
        }
    }

    public class PostTask implements Runnable {

        private String taskId;

        public PostTask(String taskId) {
            this.taskId = taskId;
        }

        @SuppressWarnings("deprecation")
        @Override
        public void run() {
            ResourceResolver resolver = null;
            try {
                resolver = rrFactory.getAdministrativeResourceResolver(null);
                TaskManager taskManager = resolver.adaptTo(TaskManager.class);
                Task task = taskManager.getTask(taskId);
                if (task != null) {
                    String assignee = task.getCurrentAssignee();

                    String slackUsername = usernameMappings.get(assignee);
                    if (slackUsername != null) {
                        String text = String.format("New task received. %s Click <%s|here> to view your task list.",
                                task.getDescription(), externalizer.authorLink(resolver, "/notifications.html"));
                        SlackMessage msg = new SlackMessage(text);
                        msg.setChannel("@" + slackUsername);
                        sendMessage(msg);
                    }
                }
            } catch (Exception e) {
                log.error("Unable to submit comment notification to Slack");
            } finally {
                resolver.close();
            }
        }

    }

    private class PostComment implements Runnable {

        private String commentPath;

        public PostComment(String commentPath) {
            this.commentPath = commentPath;
        }

        @SuppressWarnings("deprecation")
        @Override
        public void run() {
            ResourceResolver resolver = null;
            try {
                resolver = rrFactory.getAdministrativeResourceResolver(null);
                Resource commentResource = resolver.getResource(commentPath);
                if (commentResource != null) {
                    Comment comment = commentResource.adaptTo(Comment.class);
                    CommentCollection<?> collection = comment.getCollection();
                    String targetPath = collection.getTarget().getPath();
                    String viewerPath = null;
                    if (targetPath.startsWith("/content/dam")) {
                        viewerPath = authorUIHelper.generateEditAssetLink(collection.getTarget().getParent().getPath(),
                                true, resolver);
                    }

                    if (viewerPath != null) {
                        String author = comment.getAuthorName();
                        String text;
                        String slackUsername = usernameMappings.get(author);
                        if (slackUsername != null) {
                            text = String.format("User <@%s> created comment '%s'. Click <%s|here> to view the asset.",
                                    slackUsername, comment.getMessage(), viewerPath);
                        } else {
                            text = String.format("User %s created comment '%s'. Click <%s|here> to view the asset.",
                                    author, comment.getMessage(), viewerPath);
                        }
                        SlackMessage msg = new SlackMessage(text);
                        sendMessage(msg);
                    }
                }
            } catch (Exception e) {
                log.error("Unable to submit comment notification to Slack");
            } finally {
                resolver.close();
            }
        }
    }

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);

    @Property(label = "Slack Incoming WebHook URL",
            description = "URL provided by Slack as the Incoming WebHook endpoint.")
    private static final String PROP_URL = "url";

    @Property(unbounded = PropertyUnbounded.ARRAY, label = "AEM to Slack Username Mapping",
            description = "Mapping between AEM and Slack usernames. In the form <aemname>=<slackname>.")
    private static final String PROP_MAPPING = "username.mapping";

    @Reference
    private AuthorUIHelper authorUIHelper;

    private ServiceRegistration commentListenerRegistration;

    private HttpClient httpClient;

    private ExecutorService queue;

    @Reference
    private ResourceResolverFactory rrFactory;

    @Reference
    private Externalizer externalizer;

    private String url;

    private Map<String, String> usernameMappings = Collections.emptyMap();

    private ServiceRegistration taskListenerRegistration;

    @Activate
    private void activate(ComponentContext ctx) {
        queue = Executors.newSingleThreadExecutor();
        httpClient = new HttpClient();

        Dictionary<?, ?> props = ctx.getProperties();
        url = PropertiesUtil.toString(props.get(PROP_URL), null);
        if (url == null) {
            throw new IllegalArgumentException("URL is not defined");
        }
        usernameMappings = PropertiesUtil.toMap(props.get(PROP_MAPPING), new String[0]);

        BundleContext bundleContext = ctx.getBundleContext();
        Hashtable<String, Object> serviceProps = new Hashtable<String, Object>();
        serviceProps.put(EventConstants.EVENT_TOPIC,
                CommentingEvent.EVENT_TOPIC_BASE + "/" + CommentingEvent.Type.COMMENTED.name().toLowerCase());
        commentListenerRegistration = bundleContext.registerService(EventHandler.class.getName(), new CommentListener(),
                serviceProps);

        serviceProps.put(EventConstants.EVENT_TOPIC, TaskEvent.TOPIC);
        taskListenerRegistration = bundleContext.registerService(EventHandler.class.getName(), new TaskListener(),
                serviceProps);

    }

    @Deactivate
    private void deactivate() {
        if (commentListenerRegistration != null) {
            commentListenerRegistration.unregister();
            commentListenerRegistration = null;
        }
        if (taskListenerRegistration != null) {
            taskListenerRegistration.unregister();
            taskListenerRegistration = null;
        }
        queue.shutdownNow();
    }

    private void sendMessage(SlackMessage msg) throws HttpException, IOException {
        PostMethod pm = new PostMethod(url);
        pm.setRequestEntity(new StringRequestEntity(msg.toJsonString(), "application/json", "UTF-8"));
        httpClient.executeMethod(pm);
    }
}
