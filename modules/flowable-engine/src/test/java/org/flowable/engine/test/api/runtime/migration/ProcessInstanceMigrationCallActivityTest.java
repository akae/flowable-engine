/* Licensed under the Apache License, Version 2.0 (the "License");
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

package org.flowable.engine.test.api.runtime.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.assertj.core.api.Condition;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.impl.migration.ProcessInstanceMigrationValidationResult;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.test.PluggableFlowableTestCase;
import org.flowable.engine.migration.ActivityMigrationMapping;
import org.flowable.engine.migration.ProcessInstanceMigrationBuilder;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * @author Dennis Federico
 */
public class ProcessInstanceMigrationCallActivityTest extends PluggableFlowableTestCase {

    @AfterEach
    protected void tearDown() {
        deleteDeployments();
    }

    @Test
    public void testValidationAutoMapOfMissingCallActivityInNewModel() {
        ProcessDefinition procDefWithCallActivity = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/two-tasks-with-call-activity.bpmn20.xml");
        ProcessDefinition procDefCallActivity = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/oneTaskProcess.bpmn20.xml");
        ProcessDefinition procDefSimpleOneTask = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/one-task-simple-process.bpmn20.xml");

        //Start the processInstance and confirm the migration state to validate
        ProcessInstance processInstance = runtimeService.startProcessInstanceById(procDefWithCallActivity.getId());
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task).extracting(Task::getTaskDefinitionKey).isEqualTo("firstTask");
        assertThat(task).extracting(Task::getProcessDefinitionId).isEqualTo(procDefWithCallActivity.getId());
        completeTask(task);

        List<Execution> processExecutions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(processExecutions).extracting(Execution::getActivityId).containsExactly("callActivity");
        assertThat(processExecutions).extracting("processDefinitionId").containsOnly(procDefWithCallActivity.getId());
        ProcessInstance subProcessInstance = runtimeService.createProcessInstanceQuery().superProcessInstanceId(processInstance.getId()).singleResult();
        List<Execution> subProcessExecutions = runtimeService.createExecutionQuery().processInstanceId(subProcessInstance.getId()).onlyChildExecutions().list();
        assertThat(subProcessExecutions).extracting(Execution::getActivityId).containsExactly("theTask");
        assertThat(subProcessExecutions).extracting("processDefinitionId").containsOnly(procDefCallActivity.getId());
        List<Task> subProcessTasks = taskService.createTaskQuery().processInstanceId(subProcessInstance.getId()).list();
        assertThat(subProcessTasks).extracting(Task::getTaskDefinitionKey).containsExactly("theTask");
        assertThat(subProcessTasks).extracting(Task::getProcessDefinitionId).containsOnly(procDefCallActivity.getId());

        //Prepare migration and validate
        ProcessInstanceMigrationBuilder processInstanceMigrationBuilder = runtimeService.createProcessInstanceMigrationBuilder().migrateToProcessDefinition(procDefSimpleOneTask.getId());
        ProcessInstanceMigrationValidationResult processInstanceMigrationValidationResult = processInstanceMigrationBuilder.validateMigration(processInstance.getId());
        assertThat(processInstanceMigrationValidationResult.hasErrors()).isTrue();

        assertThat(processInstanceMigrationValidationResult.getValidationMessages())
            .containsAnyOf("Call activity 'callActivity' does not exist in the new model. It must be mapped explicitly for migration (or all its child activities)");

        try {
            processInstanceMigrationBuilder.migrate(processInstance.getId());
            fail("Migration should not be possible");
        } catch (FlowableException e) {
            assertTextPresent("Call activity 'callActivity' does not exist in the new model. It must be mapped explicitly for migration (or all its child activities)", e.getMessage());
        }

        completeProcessInstanceTasks(subProcessInstance.getId());
        completeProcessInstanceTasks(processInstance.getId());
        assertProcessEnded(processInstance.getId());
    }

    @Test
    public void testValidationAutoMapOfCallActivityToDifferentActivityType() {
        ProcessDefinition procDefWithCallActivity = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/two-tasks-with-call-activity.bpmn20.xml");
        ProcessDefinition procDefSubProcess = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/oneTaskProcess.bpmn20.xml");
        ProcessDefinition procDefWithoutCallActivity = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/one-task-process-named-call-activity.bpmn20.xml");

        //Start the processInstance and confirm the migration state to validate
        ProcessInstance processInstance = runtimeService.startProcessInstanceById(procDefWithCallActivity.getId());
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task).extracting(Task::getTaskDefinitionKey).isEqualTo("firstTask");
        completeTask(task);

        List<Execution> processExecutions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(processExecutions).extracting(Execution::getActivityId).containsExactly("callActivity");
        assertThat(processExecutions).extracting("processDefinitionId").containsOnly(procDefWithCallActivity.getId());

        ProcessInstance subProcessInstance = runtimeService.createProcessInstanceQuery().superProcessInstanceId(processInstance.getId()).singleResult();
        List<Execution> subProcessExecutions = runtimeService.createExecutionQuery().processInstanceId(subProcessInstance.getId()).onlyChildExecutions().list();
        assertThat(subProcessExecutions).extracting(Execution::getActivityId).containsExactlyInAnyOrder("theTask");
        assertThat(subProcessExecutions).extracting("processDefinitionId").containsOnly(procDefSubProcess.getId());
        List<Task> subProcessTasks = taskService.createTaskQuery().processInstanceId(subProcessInstance.getId()).list();
        assertThat(subProcessTasks).extracting(Task::getTaskDefinitionKey).containsExactlyInAnyOrder("theTask");
        assertThat(subProcessTasks).extracting(Task::getProcessDefinitionId).containsOnly(procDefSubProcess.getId());

        //Prepare migration and validate
        ProcessInstanceMigrationBuilder processInstanceMigrationBuilder = runtimeService.createProcessInstanceMigrationBuilder().migrateToProcessDefinition(procDefWithoutCallActivity.getId());
        ProcessInstanceMigrationValidationResult processInstanceMigrationValidationResult = processInstanceMigrationBuilder.validateMigration(processInstance.getId());
        assertThat(processInstanceMigrationValidationResult.hasErrors()).isTrue();

        assertThat(processInstanceMigrationValidationResult.getValidationMessages())
            .containsAnyOf("Call activity 'callActivity' is not a Call Activity in the new model. It must be mapped explicitly for migration (or all its child activities)");

        try {
            processInstanceMigrationBuilder.migrate(processInstance.getId());
            fail("Migration should not be possible");
        } catch (FlowableException e) {
            assertTextPresent("Call activity 'callActivity' is not a Call Activity in the new model. It must be mapped explicitly for migration (or all its child activities)", e.getMessage());
        }

        completeProcessInstanceTasks(subProcessInstance.getId());
        completeProcessInstanceTasks(processInstance.getId());
        assertProcessEnded(processInstance.getId());
    }

    @Test
    public void testValidationOfIncompleteMappingOfCallActivitySubProcessActivities() {
        ProcessDefinition procDefWithCallActivityV1 = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/two-tasks-with-call-activity-v3.bpmn20.xml");
        ProcessDefinition procDefSubProcessV1 = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/parallel-gateway-two-splits-four-tasks.bpmn20.xml");
        ProcessDefinition procDefWithCallActivityV2 = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/two-tasks-with-call-activity.bpmn20.xml");
        ProcessDefinition procDefSubProcessV2 = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/oneTaskProcess.bpmn20.xml");

        //Start the processInstance and confirm the migration state to validate
        ProcessInstance processInstance = runtimeService.startProcessInstanceById(procDefWithCallActivityV1.getId());
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task).extracting(Task::getTaskDefinitionKey).isEqualTo("firstTask");
        completeTask(task);

        List<Execution> processExecutions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(processExecutions).extracting(Execution::getActivityId).containsExactly("callActivity");
        assertThat(processExecutions).extracting("processDefinitionId").containsOnly(procDefWithCallActivityV1.getId());
        ProcessInstance subProcessInstance = runtimeService.createProcessInstanceQuery().superProcessInstanceId(processInstance.getId()).singleResult();
        task = taskService.createTaskQuery().processInstanceId(subProcessInstance.getId()).singleResult();
        assertThat(task).extracting(Task::getTaskDefinitionKey).isEqualTo("taskBefore");
        completeTask(task);
        List<Execution> subProcessExecutions = runtimeService.createExecutionQuery().processInstanceId(subProcessInstance.getId()).onlyChildExecutions().list();
        assertThat(subProcessExecutions).extracting(Execution::getActivityId).containsExactlyInAnyOrder("oddFlowTask1", "evenFlowTask2");
        assertThat(subProcessExecutions).extracting("processDefinitionId").containsOnly(procDefSubProcessV1.getId());
        List<Task> subProcessTasks = taskService.createTaskQuery().processInstanceId(subProcessInstance.getId()).list();
        assertThat(subProcessTasks).extracting(Task::getTaskDefinitionKey).containsExactlyInAnyOrder("oddFlowTask1", "evenFlowTask2");
        assertThat(subProcessTasks).extracting(Task::getProcessDefinitionId).containsOnly(procDefSubProcessV1.getId());

        //Prepare migration and validate
        ProcessInstanceMigrationBuilder processInstanceMigrationBuilder = runtimeService.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(procDefWithCallActivityV2.getId())
            .addActivityMigrationMapping(ActivityMigrationMapping.createMappingFor("evenFlowTask2", "anyTask").inParentProcessOfCallActivityId("callActivity"));
        ProcessInstanceMigrationValidationResult processInstanceMigrationValidationResult = processInstanceMigrationBuilder.validateMigration(processInstance.getId());
        assertThat(processInstanceMigrationValidationResult.hasErrors()).isTrue();

        assertThat(processInstanceMigrationValidationResult.getValidationMessages())
            .containsAnyOf("Incomplete migration mapping for call activity. The call activity 'callActivity' called element is different in the new model. Running subProcess activities '[oddFlowTask1]' should also be mapped for migration (or the call activity itself)");

        try {
            processInstanceMigrationBuilder.migrate(processInstance.getId());
            fail("Migration should not be possible");
        } catch (FlowableException e) {
            assertTextPresent("Call activity 'callActivity' has a different called element in the new model. It must be mapped explicitly for migration (or all its child activities)", e.getMessage());
        }

        completeProcessInstanceTasks(subProcessInstance.getId());
        completeProcessInstanceTasks(processInstance.getId());
        assertProcessEnded(processInstance.getId());
    }

    @Test
    public void testValidationOfInvalidActivityInMigrationMappingToCallActivitySubProcess() {
        ProcessDefinition procDefSimpleOneTask = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/one-task-simple-process.bpmn20.xml");
        ProcessDefinition procDefWithCallActivity = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/two-tasks-with-call-activity.bpmn20.xml");
        ProcessDefinition procDefCallActivity = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/oneTaskProcess.bpmn20.xml");

        //Start the processInstance
        ProcessInstance processInstance = runtimeService.startProcessInstanceById(procDefSimpleOneTask.getId());

        //Confirm the state to migrate
        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).extracting(Execution::getActivityId).containsExactly("userTask1Id");
        assertThat(executions).extracting("processDefinitionId").containsOnly(procDefSimpleOneTask.getId());
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task).extracting(Task::getTaskDefinitionKey).isEqualTo("userTask1Id");
        assertThat(task).extracting(Task::getProcessDefinitionId).isEqualTo(procDefSimpleOneTask.getId());

        //Prepare and action the migration - to a new ProcessDefinition containing a call activity subProcess
        ProcessInstanceMigrationBuilder processInstanceMigrationBuilder = runtimeService.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(procDefWithCallActivity.getId())
            .addActivityMigrationMapping(ActivityMigrationMapping.createMappingFor("userTask1Id", "wrongActivityId").inSubProcessOfCallActivityId("callActivity"));
        ProcessInstanceMigrationValidationResult processInstanceMigrationValidationResult = processInstanceMigrationBuilder.validateMigration(processInstance.getId());
        assertThat(processInstanceMigrationValidationResult.hasErrors()).isTrue();
        assertThat(processInstanceMigrationValidationResult.getValidationMessages()).contains("Invalid mapping for 'userTask1Id' to 'wrongActivityId', cannot be found in the process definition with id 'oneTaskProcess'");

        try {
            processInstanceMigrationBuilder.migrate(processInstance.getId());
            fail("Migration should not be possible");
        } catch (FlowableException e) {
            assertTextPresent("Cannot find activity 'wrongActivityId' in process definition for with id 'oneTaskProcess'", e.getMessage());
        }

        completeProcessInstanceTasks(processInstance.getId());
        assertProcessEnded(processInstance.getId());
    }

    @Test
    public void testValidationOfInvalidCallActivityInMigrateMappingToCallActivitySubProcess() {
        ProcessDefinition procDefSimpleOneTask = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/one-task-simple-process.bpmn20.xml");
        ProcessDefinition procDefWithCallActivity = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/two-tasks-with-call-activity.bpmn20.xml");
        ProcessDefinition procDefCallActivity = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/oneTaskProcess.bpmn20.xml");

        //Start the processInstance
        ProcessInstance processInstance = runtimeService.startProcessInstanceById(procDefSimpleOneTask.getId());

        //Confirm the state to migrate
        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).extracting(Execution::getActivityId).containsExactly("userTask1Id");
        assertThat(executions).extracting("processDefinitionId").containsOnly(procDefSimpleOneTask.getId());
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task).extracting(Task::getTaskDefinitionKey).isEqualTo("userTask1Id");
        assertThat(task).extracting(Task::getProcessDefinitionId).isEqualTo(procDefSimpleOneTask.getId());

        //Prepare and action the migration - to a new ProcessDefinition containing a call activity subProcess
        ProcessInstanceMigrationBuilder processInstanceMigrationBuilder = runtimeService.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(procDefWithCallActivity.getId())
            .addActivityMigrationMapping(ActivityMigrationMapping.createMappingFor("userTask1Id", "theTask").inSubProcessOfCallActivityId("wrongCallActivity"));
        ProcessInstanceMigrationValidationResult processInstanceMigrationValidationResult = processInstanceMigrationBuilder.validateMigration(processInstance.getId());
        assertThat(processInstanceMigrationValidationResult.hasErrors()).isTrue();
        assertThat(processInstanceMigrationValidationResult.getValidationMessages()).contains("There's no call activity element with id 'wrongCallActivity' in the process definition with id 'twoTasksParentProcess'");

        try {
            processInstanceMigrationBuilder.migrate(processInstance.getId());
            fail("Migration should not be possible");
        } catch (FlowableException e) {
            assertTextPresent("Call activity could not be found in process definition for id theTask", e.getMessage());
        }

        completeProcessInstanceTasks(processInstance.getId());
        assertProcessEnded(processInstance.getId());
    }

    @Test
    public void testMigrateMovingCallActivityToSimpleActivity() {
        ProcessDefinition procDefWithCallActivity = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/two-tasks-with-call-activity.bpmn20.xml");
        ProcessDefinition procDefSubProcess = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/oneTaskProcess.bpmn20.xml");
        ProcessDefinition procDefSimpleOneTask = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/one-task-simple-process.bpmn20.xml");

        //Start the processInstance
        ProcessInstance processInstance = runtimeService.startProcessInstanceById(procDefWithCallActivity.getId());
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task).extracting(Task::getTaskDefinitionKey).isEqualTo("firstTask");
        completeTask(task);

        //Confirm the state to migrate
        List<Execution> processExecutions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(processExecutions).extracting(Execution::getActivityId).containsExactly("callActivity");
        assertThat(processExecutions).extracting("processDefinitionId").containsOnly(procDefWithCallActivity.getId());

        ProcessInstance subProcessInstance = runtimeService.createProcessInstanceQuery().superProcessInstanceId(processInstance.getId()).singleResult();
        List<Execution> subProcessExecutions = runtimeService.createExecutionQuery().processInstanceId(subProcessInstance.getId()).onlyChildExecutions().list();
        assertThat(subProcessExecutions).extracting(Execution::getActivityId).containsExactlyInAnyOrder("theTask");
        assertThat(subProcessExecutions).extracting("processDefinitionId").containsOnly(procDefSubProcess.getId());
        List<Task> subProcessTasks = taskService.createTaskQuery().processInstanceId(subProcessInstance.getId()).list();
        assertThat(subProcessTasks).extracting(Task::getTaskDefinitionKey).containsExactlyInAnyOrder("theTask");
        assertThat(subProcessTasks).extracting(Task::getProcessDefinitionId).containsOnly(procDefSubProcess.getId());

        //Prepare and action the migration - to a new ProcessDefinition containing a call activity subProcess
        ProcessInstanceMigrationBuilder processInstanceMigrationBuilder = runtimeService.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(procDefSimpleOneTask.getId())
            .addActivityMigrationMapping(ActivityMigrationMapping.createMappingFor("callActivity", "userTask1Id"));

        ProcessInstanceMigrationValidationResult processInstanceMigrationValidationResult = processInstanceMigrationBuilder.validateMigration(processInstance.getId());
        assertThat(processInstanceMigrationValidationResult.hasErrors()).isFalse();
        assertThat(processInstanceMigrationValidationResult.getValidationMessages()).isEmpty();

        processInstanceMigrationBuilder.migrate(processInstance.getId());

        //Confirm migration
        processExecutions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(processExecutions).extracting(Execution::getActivityId).containsExactly("userTask1Id");
        assertThat(processExecutions).extracting("processDefinitionId").containsOnly(procDefSimpleOneTask.getId());

        subProcessInstance = runtimeService.createProcessInstanceQuery().superProcessInstanceId(processInstance.getId()).singleResult();
        assertNull(subProcessInstance);

        List<Task> processTasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(processTasks).extracting(Task::getTaskDefinitionKey).containsExactly("userTask1Id");
        assertThat(processTasks).extracting(Task::getProcessDefinitionId).containsOnly(procDefSimpleOneTask.getId());

        completeTask(processTasks.get(0));

        assertProcessEnded(processInstance.getId());
    }

    @Test
    public void testMigrateMovingParallelMultiInstanceCallActivityToSimpleActivity() {

        Condition<Execution> isInactiveExecution = new Condition<>(execution -> !((ExecutionEntity) execution).isActive(), "inactive execution");

        ProcessDefinition procDefWithCallActivity = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/parallel-multi-instance-call-activity.bpmn20.xml");
        ProcessDefinition procDefSubProcess = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/oneTaskProcess.bpmn20.xml");
        ProcessDefinition procDefSimpleOneTask = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/one-task-simple-process.bpmn20.xml");

        //Start the processInstance
        ProcessInstance processInstance = runtimeService.startProcessInstanceById(procDefWithCallActivity.getId(), Collections.singletonMap("nrOfLoops", 3));
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task).extracting(Task::getTaskDefinitionKey).isEqualTo("beforeMultiInstance");
        completeTask(task);

        //Confirm the state to migrate
        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        //MultiInstance parent and its parallel instances
        assertThat(executions).size().isEqualTo(4);
        assertThat(executions).extracting(Execution::getActivityId).containsOnly("parallelMICallActivity");
        //Parallel MI root execution is inactive
        assertThat(executions).haveExactly(1, isInactiveExecution);
        assertThat(executions).extracting("processDefinitionId").containsOnly(procDefWithCallActivity.getId());

        Execution miRoot = executions.stream().filter(e -> ((ExecutionEntity) e).isMultiInstanceRoot()).findFirst().get();
        Map<String, Object> miRootVars = runtimeService.getVariables(miRoot.getId());
        assertThat(miRootVars).extracting("nrOfActiveInstances").containsOnly(3);
        assertThat(miRootVars).extracting("nrOfCompletedInstances").containsOnly(0);
        assertThat(miRootVars).extracting("nrOfLoops").containsOnly(3);

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(tasks).isEmpty();

        List<ProcessInstance> subProcessInstances = runtimeService.createProcessInstanceQuery().superProcessInstanceId(processInstance.getId()).list();
        assertThat(subProcessInstances).size().isEqualTo(3);

        tasks = subProcessInstances.stream()
            .map(ProcessInstance::getId)
            .map(subProcId -> taskService.createTaskQuery().processInstanceId(subProcId).singleResult())
            .collect(Collectors.toList());

        assertThat(tasks).extracting(Task::getTaskDefinitionKey).containsOnly("theTask");
        assertThat(tasks).extracting(Task::getProcessDefinitionId).containsOnly(procDefSubProcess.getId());

        //Prepare and action the migration
        ProcessInstanceMigrationBuilder processInstanceMigrationBuilder = runtimeService.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(procDefSimpleOneTask.getId())
            .addActivityMigrationMapping(ActivityMigrationMapping.createMappingFor("parallelMICallActivity", "userTask1Id"));
        ProcessInstanceMigrationValidationResult processInstanceMigrationValidationResult = processInstanceMigrationBuilder.validateMigration(processInstance.getId());

        //        assertThat(processInstanceMigrationValidationResult.hasErrors()).isFalse();
        assertThat(processInstanceMigrationValidationResult.getValidationMessages()).isEmpty();

        processInstanceMigrationBuilder.migrate(processInstance.getId());

        //Confirm migration
        executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).extracting(Execution::getActivityId).containsExactly("userTask1Id");
        assertThat(executions).extracting("processDefinitionId").containsOnly(procDefSimpleOneTask.getId());

        ProcessInstance subProcessInstance = runtimeService.createProcessInstanceQuery().superProcessInstanceId(processInstance.getId()).singleResult();
        assertNull(subProcessInstance);

        List<Task> processTasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(processTasks).extracting(Task::getTaskDefinitionKey).containsExactly("userTask1Id");
        assertThat(processTasks).extracting(Task::getProcessDefinitionId).containsOnly(procDefSimpleOneTask.getId());

        completeTask(processTasks.get(0));

        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Disabled("Not supported - Cannot migrate to an arbitrary activity inside an MI subProcess")
    public void testInvalidMigrationMovingCallActivityToActivityInsideMultiInstance() {
        ProcessDefinition procDefWithCallActivity = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/two-tasks-with-call-activity.bpmn20.xml");
        ProcessDefinition procDefSubProcess = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/oneTaskProcess.bpmn20.xml");
        ProcessDefinition procDefMITask = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/parallel-multi-instance-subprocess.bpmn20.xml");

        //Start the processInstance
        ProcessInstance processInstance = runtimeService.startProcessInstanceById(procDefWithCallActivity.getId());
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task).extracting(Task::getTaskDefinitionKey).isEqualTo("firstTask");
        completeTask(task);

        //Confirm the state to migrate
        List<Execution> processExecutions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(processExecutions).extracting(Execution::getActivityId).containsExactly("callActivity");
        assertThat(processExecutions).extracting("processDefinitionId").containsOnly(procDefWithCallActivity.getId());

        ProcessInstance subProcessInstance = runtimeService.createProcessInstanceQuery().superProcessInstanceId(processInstance.getId()).singleResult();
        List<Execution> subProcessExecutions = runtimeService.createExecutionQuery().processInstanceId(subProcessInstance.getId()).onlyChildExecutions().list();
        assertThat(subProcessExecutions).extracting(Execution::getActivityId).containsExactlyInAnyOrder("theTask");
        assertThat(subProcessExecutions).extracting("processDefinitionId").containsOnly(procDefSubProcess.getId());
        List<Task> subProcessTasks = taskService.createTaskQuery().processInstanceId(subProcessInstance.getId()).list();
        assertThat(subProcessTasks).extracting(Task::getTaskDefinitionKey).containsExactlyInAnyOrder("theTask");
        assertThat(subProcessTasks).extracting(Task::getProcessDefinitionId).containsOnly(procDefSubProcess.getId());

        //Prepare and action the migration
        ProcessInstanceMigrationBuilder processInstanceMigrationBuilder = runtimeService.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(procDefMITask.getId())
            .addActivityMigrationMapping(ActivityMigrationMapping.createMappingFor("callActivity", "subTask2"));

        ProcessInstanceMigrationValidationResult processInstanceMigrationValidationResult = processInstanceMigrationBuilder.validateMigration(processInstance.getId());
        assertThat(processInstanceMigrationValidationResult.hasErrors()).isTrue();
        assertThat(processInstanceMigrationValidationResult.getValidationMessages()).contains("Invalid mapping for 'callActivity' to 'subTask2', cannot migrate arbitrarily inside a Multi Instance container 'parallelMISubProcess' inside process definition with id 'parallelMultiInstanceSubProcess'");

        try {
            processInstanceMigrationBuilder.migrate(processInstance.getId());
            fail("Migration should not be possible");
        } catch (FlowableException e) {
            assertTextPresent("Cannot find activity 'wrongActivityId' in process definition for with id 'oneTaskProcess'", e.getMessage());
        }

    }

    @Test
    public void testMigrateMovingActivityIntoCallActivitySubProcess() {
        ProcessDefinition procDefSimpleOneTask = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/one-task-simple-process.bpmn20.xml");
        ProcessDefinition procDefWithCallActivity = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/two-tasks-with-call-activity.bpmn20.xml");
        ProcessDefinition procDefCallActivity = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/oneTaskProcess.bpmn20.xml");

        //Start the processInstance
        ProcessInstance processInstance = runtimeService.startProcessInstanceById(procDefSimpleOneTask.getId());

        //Confirm the state to migrate
        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).extracting(Execution::getActivityId).containsExactly("userTask1Id");
        assertThat(executions).extracting("processDefinitionId").containsOnly(procDefSimpleOneTask.getId());
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task).extracting(Task::getTaskDefinitionKey).isEqualTo("userTask1Id");
        assertThat(task).extracting(Task::getProcessDefinitionId).isEqualTo(procDefSimpleOneTask.getId());

        //Prepare and action the migration - to a new ProcessDefinition containing a call activity subProcess
        ProcessInstanceMigrationBuilder processInstanceMigrationBuilder = runtimeService.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(procDefWithCallActivity.getId())
            .addActivityMigrationMapping(ActivityMigrationMapping.createMappingFor("userTask1Id", "theTask").inSubProcessOfCallActivityId("callActivity"));

        ProcessInstanceMigrationValidationResult processInstanceMigrationValidationResult = processInstanceMigrationBuilder.validateMigration(processInstance.getId());
        assertThat(processInstanceMigrationValidationResult.hasErrors()).isFalse();
        assertThat(processInstanceMigrationValidationResult.getValidationMessages()).isEmpty();

        processInstanceMigrationBuilder.migrate(processInstance.getId());

        ProcessInstance subProcessInstance = runtimeService.createProcessInstanceQuery().superProcessInstanceId(processInstance.getId()).singleResult();
        assertNotNull(subProcessInstance);

        List<Execution> processExecutions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(processExecutions).extracting(Execution::getActivityId).containsExactly("callActivity");
        assertThat(processExecutions).extracting("processDefinitionId").containsOnly(procDefWithCallActivity.getId());

        List<Execution> subProcessExecutions = runtimeService.createExecutionQuery().processInstanceId(subProcessInstance.getId()).onlyChildExecutions().list();
        assertThat(subProcessExecutions).extracting(Execution::getActivityId).containsExactly("theTask");
        assertThat(subProcessExecutions).extracting("processDefinitionId").containsOnly(procDefCallActivity.getId());

        List<Task> processTasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(processTasks).isEmpty();

        List<Task> subProcessTasks = taskService.createTaskQuery().processInstanceId(subProcessInstance.getId()).list();
        assertThat(subProcessTasks).extracting(Task::getTaskDefinitionKey).containsExactly("theTask");
        assertThat(subProcessTasks).extracting(Task::getProcessDefinitionId).containsOnly(procDefCallActivity.getId());

        completeTask(subProcessTasks.get(0));

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task).extracting(Task::getTaskDefinitionKey).isEqualTo("secondTask");
        assertThat(task).extracting(Task::getProcessDefinitionId).isEqualTo(procDefWithCallActivity.getId());

        completeTask(task);

        assertProcessEnded(processInstance.getId());
    }

    @Test
    public void testMigrateMovingActivityIntoCallActivitySubProcessV2() {
        ProcessDefinition procDefSimpleOneTask = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/one-task-simple-process.bpmn20.xml");
        ProcessDefinition procDefWithCallActivity = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/two-tasks-with-call-activity-v2.bpmn20.xml");
        ProcessDefinition procDefCallActivity = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/two-tasks-simple-process.bpmn20.xml");

        //Start the processInstance
        ProcessInstance processInstance = runtimeService.startProcessInstanceById(procDefSimpleOneTask.getId());

        //Confirm the state to migrate
        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(executions).extracting(Execution::getActivityId).containsExactly("userTask1Id");
        assertThat(executions).extracting("processDefinitionId").containsOnly(procDefSimpleOneTask.getId());
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task).extracting(Task::getTaskDefinitionKey).isEqualTo("userTask1Id");
        assertThat(task).extracting(Task::getProcessDefinitionId).isEqualTo(procDefSimpleOneTask.getId());

        //Prepare and action the migration - to a new ProcessDefinition containing a call activity subProcess
        ProcessInstanceMigrationBuilder processInstanceMigrationBuilder = runtimeService.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(procDefWithCallActivity.getId())
            .addActivityMigrationMapping(ActivityMigrationMapping.createMappingFor("userTask1Id", "userTask2Id").inSubProcessOfCallActivityId("callActivity"));

        ProcessInstanceMigrationValidationResult processInstanceMigrationValidationResult = processInstanceMigrationBuilder.validateMigration(processInstance.getId());
        assertThat(processInstanceMigrationValidationResult.getValidationMessages()).isEmpty();

        processInstanceMigrationBuilder.migrate(processInstance.getId());

        ProcessInstance subProcessInstance = runtimeService.createProcessInstanceQuery().superProcessInstanceId(processInstance.getId()).singleResult();
        assertNotNull(subProcessInstance);

        List<Execution> processExecutions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(processExecutions).extracting(Execution::getActivityId).containsExactly("callActivity");
        assertThat(processExecutions).extracting("processDefinitionId").containsOnly(procDefWithCallActivity.getId());

        List<Execution> subProcessExecutions = runtimeService.createExecutionQuery().processInstanceId(subProcessInstance.getId()).onlyChildExecutions().list();
        assertThat(subProcessExecutions).extracting(Execution::getActivityId).containsExactly("userTask2Id");
        assertThat(subProcessExecutions).extracting("processDefinitionId").containsOnly(procDefCallActivity.getId());

        List<Task> processTasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(processTasks).isEmpty();

        List<Task> subProcessTasks = taskService.createTaskQuery().processInstanceId(subProcessInstance.getId()).list();
        assertThat(subProcessTasks).extracting(Task::getTaskDefinitionKey).containsExactly("userTask2Id");
        assertThat(subProcessTasks).extracting(Task::getProcessDefinitionId).containsOnly(procDefCallActivity.getId());

        completeTask(subProcessTasks.get(0));

        task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task).extracting(Task::getTaskDefinitionKey).isEqualTo("secondTask");
        assertThat(task).extracting(Task::getProcessDefinitionId).isEqualTo(procDefWithCallActivity.getId());

        completeTask(task);

        assertProcessEnded(processInstance.getId());
    }

    @Test
    public void testMigrateMovingCallActivitySubProcessActivityToSubProcessParent() {
        ProcessDefinition procDefWithCallActivity = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/two-tasks-with-call-activity.bpmn20.xml");
        ProcessDefinition procDefCallActivity = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/oneTaskProcess.bpmn20.xml");
        ProcessDefinition procDefSimpleOneTask = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/one-task-simple-process.bpmn20.xml");

        //Start the processInstance
        ProcessInstance processInstance = runtimeService.startProcessInstanceById(procDefWithCallActivity.getId());

        //Confirm the state to migrate
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task).extracting(Task::getTaskDefinitionKey).isEqualTo("firstTask");
        assertThat(task).extracting(Task::getProcessDefinitionId).isEqualTo(procDefWithCallActivity.getId());
        completeTask(task);

        List<Execution> processExecutions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(processExecutions).extracting(Execution::getActivityId).containsExactly("callActivity");
        assertThat(processExecutions).extracting("processDefinitionId").containsOnly(procDefWithCallActivity.getId());
        ProcessInstance subProcessInstance = runtimeService.createProcessInstanceQuery().superProcessInstanceId(processInstance.getId()).singleResult();
        List<Execution> subProcessExecutions = runtimeService.createExecutionQuery().processInstanceId(subProcessInstance.getId()).onlyChildExecutions().list();
        assertThat(subProcessExecutions).extracting(Execution::getActivityId).containsExactly("theTask");
        assertThat(subProcessExecutions).extracting("processDefinitionId").containsOnly(procDefCallActivity.getId());
        List<Task> subProcessTasks = taskService.createTaskQuery().processInstanceId(subProcessInstance.getId()).list();
        assertThat(subProcessTasks).extracting(Task::getTaskDefinitionKey).containsExactly("theTask");
        assertThat(subProcessTasks).extracting(Task::getProcessDefinitionId).containsOnly(procDefCallActivity.getId());

        //Prepare and action the migration - to a new ProcessDefinition containing a call activity subProcess
        ProcessInstanceMigrationBuilder processInstanceMigrationBuilder = runtimeService.createProcessInstanceMigrationBuilder().migrateToProcessDefinition(procDefSimpleOneTask.getId())
            .addActivityMigrationMapping(ActivityMigrationMapping.createMappingFor("theTask", "userTask1Id").inParentProcessOfCallActivityId("callActivity"));

        ProcessInstanceMigrationValidationResult processInstanceMigrationValidationResult = processInstanceMigrationBuilder.validateMigration(processInstance.getId());
        assertThat(processInstanceMigrationValidationResult.getValidationMessages()).isEmpty();

        processInstanceMigrationBuilder.migrate(processInstance.getId());

        //Confirm migration
        subProcessInstance = runtimeService.createProcessInstanceQuery().superProcessInstanceId(processInstance.getId()).singleResult();
        assertNull(subProcessInstance);

        processExecutions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(processExecutions).extracting(Execution::getActivityId).containsExactly("userTask1Id");
        assertThat(processExecutions).extracting("processDefinitionId").containsOnly(procDefSimpleOneTask.getId());

        List<Task> processTasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(processTasks).extracting(Task::getTaskDefinitionKey).containsExactly("userTask1Id");
        assertThat(processTasks).extracting(Task::getProcessDefinitionId).containsOnly(procDefSimpleOneTask.getId());

        //Complete process
        completeTask(processTasks.get(0));
        assertProcessEnded(processInstance.getId());
    }

    @Test
    @Disabled("WIP - Not supported yet")
    public void testMigrateMovingActivityInParallelMultiInstanceCallActivityToSubProcessParent() {
        ProcessDefinition procDefWithCallActivity = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/parallel-multi-instance-call-activity.bpmn20.xml");
        ProcessDefinition procDefCallActivity = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/oneTaskProcess.bpmn20.xml");
        ProcessDefinition procDefTwoTasks = deployProcessDefinition("my deploy", "org/flowable/engine/test/api/runtime/migration/two-tasks-simple-process.bpmn20.xml");

        //Start the processInstance
        ProcessInstance processInstance = runtimeService.startProcessInstanceById(procDefWithCallActivity.getId(), Collections.singletonMap("nrOfLoops", 3));

        //Confirm the state to migrate
        Task task = taskService.createTaskQuery().processInstanceId(processInstance.getId()).singleResult();
        assertThat(task).extracting(Task::getTaskDefinitionKey).isEqualTo("beforeMultiInstance");
        assertThat(task).extracting(Task::getProcessDefinitionId).isEqualTo(procDefWithCallActivity.getId());
        completeTask(task);

        List<ProcessInstance> subProcesses = runtimeService.createProcessInstanceQuery().superProcessInstanceId(processInstance.getId()).list();
        assertThat(subProcesses).size().isEqualTo(3);
        assertThat(subProcesses).extracting(ProcessInstance::getProcessDefinitionId).containsOnly(procDefCallActivity.getId());

        List<Task> parallelTasks = subProcesses.stream().map(ProcessInstance::getId).map(subProcessId -> taskService.createTaskQuery().processInstanceId(subProcessId).list()).flatMap(Collection::stream).collect(Collectors.toList());
        assertThat(parallelTasks).extracting(Task::getTaskDefinitionKey).containsOnly("theTask");
        assertThat(parallelTasks).extracting(Task::getProcessDefinitionId).containsOnly(procDefCallActivity.getId());

        //Prepare and action the migration
        ProcessInstanceMigrationBuilder processInstanceMigrationBuilder = runtimeService.createProcessInstanceMigrationBuilder()
            .migrateToProcessDefinition(procDefTwoTasks.getId())
            .addActivityMigrationMapping(ActivityMigrationMapping.createMappingFor("theTask", "userTask2Id").inParentProcessOfCallActivityId("parallelMICallActivity"));

        ProcessInstanceMigrationValidationResult processInstanceMigrationValidationResult = processInstanceMigrationBuilder.validateMigration(processInstance.getId());
        assertThat(processInstanceMigrationValidationResult.getValidationMessages()).isEmpty();

        processInstanceMigrationBuilder.migrate(processInstance.getId());

        //Confirm migration
        subProcesses = runtimeService.createProcessInstanceQuery().superProcessInstanceId(processInstance.getId()).list();
        assertThat(subProcesses).isEmpty();

        List<Execution> processExecutions = runtimeService.createExecutionQuery().processInstanceId(processInstance.getId()).onlyChildExecutions().list();
        assertThat(processExecutions).size().isEqualTo(1);
        assertThat(processExecutions).extracting(Execution::getActivityId).containsExactly("userTask2Id");
        assertThat(processExecutions).extracting("processDefinitionId").containsOnly(procDefTwoTasks.getId());

        List<Task> processTasks = taskService.createTaskQuery().processInstanceId(processInstance.getId()).list();
        assertThat(processTasks).size().isEqualTo(1);
        assertThat(processTasks).extracting(Task::getTaskDefinitionKey).containsExactly("userTask2Id");
        assertThat(processTasks).extracting(Task::getProcessDefinitionId).containsOnly(procDefTwoTasks.getId());

        //Complete the process
        completeProcessInstanceTasks(processInstance.getId());
        assertProcessEnded(processInstance.getId());
    }

}
