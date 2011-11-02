/*
 * Copyright (c) 2011 the original author or authors.
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

package grails.plugins.quartz.listeners;

import org.codehaus.groovy.grails.support.PersistenceContextInterceptor;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.listeners.JobListenerSupport;

/**
 * JobListener implementation which wraps the execution of a Quartz Job in a
 * persistence context, via the persistenceInterceptor.
 *
 * @author Sergey Nebolsin (nebolsin@gmail.com)
 * @since 0.2
 */
public class SessionBinderJobListener extends JobListenerSupport {

    public static final String NAME = "sessionBinderListener";

    private PersistenceContextInterceptor persistenceInterceptor;

    public String getName() {
        return NAME;
    }

    public PersistenceContextInterceptor getPersistenceInterceptor() {
        return persistenceInterceptor;
    }

    public void setPersistenceInterceptor(PersistenceContextInterceptor persistenceInterceptor) {
        this.persistenceInterceptor = persistenceInterceptor;
    }

    public void jobToBeExecuted(JobExecutionContext context) {
        if (persistenceInterceptor != null) {
            persistenceInterceptor.init();
        }
    }

    public void jobWasExecuted(JobExecutionContext context, JobExecutionException exception) {
        if (persistenceInterceptor != null) {
            persistenceInterceptor.flush();
            persistenceInterceptor.destroy();
        }
    }
}
