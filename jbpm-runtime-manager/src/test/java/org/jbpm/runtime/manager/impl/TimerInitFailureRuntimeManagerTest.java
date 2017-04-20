/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.jbpm.runtime.manager.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.persistence.EntityManagerFactory;

import org.jboss.byteman.contrib.bmunit.BMScript;
import org.jboss.byteman.contrib.bmunit.BMUnitConfig;
import org.jboss.byteman.contrib.bmunit.BMUnitRunner;
import org.jbpm.runtime.manager.impl.jpa.EntityManagerFactoryManager;
import org.jbpm.runtime.manager.util.TestUtil;
import org.jbpm.services.task.identity.JBossUserGroupCallbackImpl;
import org.jbpm.test.util.AbstractBaseTest;
import org.jbpm.test.util.CountDownProcessEventListener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.kie.api.event.process.ProcessEventListener;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.manager.RuntimeEngine;
import org.kie.api.runtime.manager.RuntimeEnvironment;
import org.kie.api.runtime.manager.RuntimeEnvironmentBuilder;
import org.kie.api.runtime.manager.RuntimeManager;
import org.kie.api.runtime.manager.RuntimeManagerFactory;
import org.kie.api.runtime.process.ProcessInstance;
import org.kie.api.task.TaskService;
import org.kie.api.task.model.TaskSummary;
import org.kie.internal.io.ResourceFactory;
import org.kie.internal.runtime.manager.SessionNotFoundException;
import org.kie.internal.runtime.manager.context.ProcessInstanceIdContext;
import org.kie.internal.task.api.UserGroupCallback;

import bitronix.tm.resource.jdbc.PoolingDataSource;

@RunWith(BMUnitRunner.class)
@BMUnitConfig(loadDirectory = "target/test-classes")
public class TimerInitFailureRuntimeManagerTest extends AbstractBaseTest {
    
    private PoolingDataSource pds;
    private UserGroupCallback userGroupCallback;
    private EntityManagerFactory emf;
    private RuntimeManager manager;

    @Before
    public void setup() {
        TestUtil.cleanupSingletonSessionId();
        pds = TestUtil.setupPoolingDataSource();
        emf = EntityManagerFactoryManager.get().getOrCreate("org.jbpm.persistence.jpa");
        Properties properties= new Properties();
        properties.setProperty("mary", "HR");
        properties.setProperty("john", "HR");
        userGroupCallback = new JBossUserGroupCallbackImpl(properties);
    }
    
    @After
    public void teardown() {
        if (manager != null) {
            manager.close();
        }
        EntityManagerFactoryManager.get().clear();
        pds.close();
    }
 
    
    @Test(timeout=15000)
    @BMScript(value = "byteman-scripts/failOnRuntimeManagerInitRules.btm")
    public void testPerProcessInstanceRuntimeManager() throws Exception {
        final CountDownProcessEventListener countDownListener = new CountDownProcessEventListener("Intermediate Catch Event 1", 1);
        RuntimeEnvironment environment = createEnvironment(countDownListener);        
        manager = RuntimeManagerFactory.Factory.get().newPerProcessInstanceRuntimeManager(environment, "first");        
        assertNotNull(manager);
        
        RuntimeEngine runtime = manager.getRuntimeEngine(ProcessInstanceIdContext.get());
        KieSession ksession = runtime.getKieSession();

        // start a new process instance
        Map<String, Object> params = new HashMap<String, Object>();
        ProcessInstance pi = ksession.startProcess("TimerInitFailure", params);
        assertEquals(ProcessInstance.STATE_ACTIVE, pi.getState());

        manager.disposeRuntimeEngine(runtime);
        
        countDownListener.waitTillCompleted();
        
        // User access
        runtime = manager.getRuntimeEngine(ProcessInstanceIdContext.get(pi.getId()));
        runtime.getKieSession();
        TaskService taskService = runtime.getTaskService();
        
        List<TaskSummary> list = taskService.getTasksAssignedAsPotentialOwner("john", "en-UK");
        assertEquals(1, list.size());
            
        long taskId = list.get(0).getId();
        taskService.start(taskId, "john");
        taskService.complete(taskId, "john", null);        
        manager.disposeRuntimeEngine(runtime);
        
        try {
            runtime = manager.getRuntimeEngine(ProcessInstanceIdContext.get(pi.getId()));
            runtime.getTaskService();
            fail("ProcessInstance should already be completed");
        } catch (SessionNotFoundException e) {
            // expected
        }
    }
    
    private RuntimeEnvironment createEnvironment(final CountDownProcessEventListener countDownListener) {
        RuntimeEnvironment environment = RuntimeEnvironmentBuilder.Factory.get()
                .newDefaultBuilder()
                .entityManagerFactory(emf)
                .userGroupCallback(userGroupCallback)
                .registerableItemsFactory(new DefaultRegisterableItemsFactory(){

                    @Override
                    public List<ProcessEventListener> getProcessEventListeners(RuntimeEngine runtime) {
                        List<ProcessEventListener> listeners = super.getProcessEventListeners(runtime);                        
                        listeners.add(countDownListener);
                        return listeners;
                    }                   
                })
                .addAsset(ResourceFactory.newClassPathResource("BPMN2-TimerInitFailure.bpmn2"), ResourceType.BPMN2)
                .get();
        
        return environment;
    }
}