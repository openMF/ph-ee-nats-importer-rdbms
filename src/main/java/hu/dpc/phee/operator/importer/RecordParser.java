package hu.dpc.phee.operator.importer;

import com.jayway.jsonpath.DocumentContext;
import hu.dpc.phee.operator.config.BpmnProcess;
import hu.dpc.phee.operator.config.BpmnProcessProperties;
import hu.dpc.phee.operator.entity.batch.Batch;
import hu.dpc.phee.operator.entity.batch.BatchRepository;
import hu.dpc.phee.operator.entity.task.Task;
import hu.dpc.phee.operator.entity.task.TaskRepository;
import hu.dpc.phee.operator.entity.transactionrequest.TransactionRequest;
import hu.dpc.phee.operator.entity.transactionrequest.TransactionRequestRepository;
import hu.dpc.phee.operator.entity.transfer.Transfer;
import hu.dpc.phee.operator.entity.transfer.TransferRepository;
import hu.dpc.phee.operator.entity.variable.Variable;
import hu.dpc.phee.operator.entity.variable.VariableRepository;
import java.util.Iterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RecordParser {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Value("${bpmn.transfer-type}")
    private String transferType;

    @Value("${bpmn.transaction-request-type}")
    private String transactionRequestType;

    @Value("${bpmn.batch-type}")
    private String batchType;

    @Value("${bpmn.outgoing-direction}")
    private String outgoingDirection;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private VariableRepository variableRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private TransactionRequestRepository transactionRequestRepository;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private BpmnProcessProperties bpmnProcessProperties;

    @Autowired
    private InflightTransferManager inflightTransferManager;

    @Autowired
    private InflightTransactionRequestManager inflightTransactionRequestManager;

    @Autowired
    private InflightBatchManager inflightBatchManager;

    @Autowired
    private VariableParser variableParser;

    private final Map<Long, Long> inflightCallActivities = new ConcurrentHashMap<>();

    public void addVariableToEntity(DocumentContext newVariable, String bpmnProcessId) {
        logger.trace("newVariable in RecordParser: {}", newVariable.jsonString()); //
        if (newVariable == null) {
            return;
        }

        String name = newVariable.read("$.value.name");
        logger.trace("VARIABLE NAME " + name);
        Long workflowInstanceKey = newVariable.read("$.value.processInstanceKey");
        logger.trace("workflowInstanceKey "+workflowInstanceKey);
        if (inflightCallActivities.containsKey(workflowInstanceKey)) {
            Long parentInstanceKey = inflightCallActivities.get(workflowInstanceKey);
            logger.trace("variable {} in instance {} has parent workflowInstance {}", name, workflowInstanceKey, parentInstanceKey);
            workflowInstanceKey = parentInstanceKey;
        }

        BpmnProcess bpmnProcess = bpmnProcessProperties.getById(bpmnProcessId);        
       
        if (transferType.equals(bpmnProcess.getType())) {
            logger.trace("TIPO DE TRANSFERENCIA");
            if (variableParser.getTransferParsers().containsKey(name)) {
                logger.trace("TIPO DE TRANSFERENCIA add variable {} to transfer for workflow {}", name, workflowInstanceKey);
                String value = newVariable.read("$.value.value");
                logger.trace("TIPO DE TRANSFERENCIA VALUE "+ value);    
                Transfer transfer = inflightTransferManager.getOrCreateTransfer(workflowInstanceKey);
                logger.trace("TIPO DE TRANSFERENCIA transfer.getWorkflowInstanceKey() "+transfer.getWorkflowInstanceKey());
                variableParser.getTransferParsers().get(name).accept(Pair.of(transfer, value));
                transferRepository.save(transfer);
            }
        } else if (transactionRequestType.equals(bpmnProcess.getType())) {
            if (variableParser.getTransactionRequestParsers().containsKey(name)) {
                logger.trace("add variable to transactionRequest {} for workflow {}", name, workflowInstanceKey);
                String value = newVariable.read("$.value.value");

                TransactionRequest transactionRequest = inflightTransactionRequestManager.getOrCreateTransactionRequest(workflowInstanceKey);
                variableParser.getTransactionRequestParsers().get(name).accept(Pair.of(transactionRequest, value));
                if(transactionRequest.getDirection() == null) {
                    transactionRequest.setDirection(bpmnProcess.getDirection());
                }
                transactionRequestRepository.save(transactionRequest);
            }
        } else if (batchType.equals(bpmnProcess.getType())) {
            if (variableParser.getBatchParsers().containsKey(name)) {
                logger.trace("add variable {} to batch for workflow {}", name, workflowInstanceKey);
                String value = newVariable.read("$.value.value");

                Batch batch = inflightBatchManager.getOrCreateBatch(workflowInstanceKey);
                variableParser.getBatchParsers().get(name).accept(Pair.of(batch, value));
                batchRepository.save(batch);
            }
        }
        else {
            logger.trace("Skip adding variable to {} and type is {}", bpmnProcessId, bpmnProcess.getType()); // xx
        }
    }

    public DocumentContext processVariable(DocumentContext json) {
        Long workflowInstanceKey = json.read("$.value.processInstanceKey");
        String name = json.read("$.value.name");
        Long newTimestamp = json.read("$.timestamp");
        List<Variable> existingVariables = variableRepository.findByWorkflowInstanceKey(workflowInstanceKey);
        if (existingVariables != null && !existingVariables.isEmpty()) {
            if (existingVariables.stream().filter(existing -> {
                return name.equals(existing.getName()) && newTimestamp <= existing.getTimestamp(); // variable already inserted before
            }).findFirst().orElse(null) != null) {
                logger.trace("Variable {} already inserted at {} for instance {}, skip processing!", name, newTimestamp, workflowInstanceKey);
                return null;
            }
        }

        Variable variable = new Variable();
        variable.setWorkflowInstanceKey(workflowInstanceKey);
        variable.setTimestamp(newTimestamp);
        variable.setWorkflowKey(json.read("$.value.processDefinitionKey"));
        variable.setName(name);
        String value = json.read("$.value.value");
        variable.setValue(value);
        variableRepository.save(variable);
        return json;
    }

    public void processWorkflowInstance(DocumentContext json) {
        String bpmnProcessId = json.read("$.value.bpmnProcessId");
        BpmnProcess bpmnProcess = bpmnProcessProperties.getById(bpmnProcessId.split("-")[0]);
        Long workflowInstanceKey = json.read("$.value.processInstanceKey");
        logger.trace("workflowInstanceKey "+workflowInstanceKey);
        Long timestamp = json.read("$.timestamp");
        String intent = json.read("$.intent");
        logger.trace("intent "+intent);
        Object parentWorkflowInstanceKey = json.read("$.value.parentProcessInstanceKey");
        logger.trace("parentWorkflowInstanceKey "+parentWorkflowInstanceKey);
        Object flowScopeKey = json.read("$.value.flowScopeKey");
        logger.trace("flowScopeKey "+flowScopeKey);
        
        boolean hasParent = false;
        if (parentWorkflowInstanceKey instanceof Long && (Long) parentWorkflowInstanceKey > 0) {
            hasParent = true;
        }
        logger.trace("hasParent "+hasParent);

        String elementId = json.read("$.value.elementId");
        logger.trace("elementId "+elementId);
        Long callActivityKey = json.read("$.key");
        logger.trace("callActivityKey "+callActivityKey);
        
        if (transferType.equals(bpmnProcess.getType())) {
            logger.trace("INTENT "+intent);
            if ("ELEMENT_ACTIVATING".equals(intent)) {
                logger.trace("EL INTENT ES ACTIVATING");
                if (hasParent) {
                    logger.trace("ACTIVATING HAS PARENT");
                    logger.trace("Sub process {} with key {} started from parent instance {}", bpmnProcessId, callActivityKey, parentWorkflowInstanceKey);
                    inflightCallActivities.put(callActivityKey, (Long) parentWorkflowInstanceKey);
                    inflightTransferManager.transferStarted((Long) parentWorkflowInstanceKey, timestamp, outgoingDirection);
                } else {
                    logger.trace("ACTIVATING INFLIGHT");
                    inflightTransferManager.transferStarted(workflowInstanceKey, timestamp, bpmnProcess.getDirection());
                }
            } else if ("ELEMENT_COMPLETED".equals(intent)) {
                logger.trace("EL intent es COMPLETED con llave " +workflowInstanceKey);
                logger.trace("inflightCallActivities.size() "+inflightCallActivities.size());                
                if (inflightCallActivities.containsKey(workflowInstanceKey)) {
                    logger.trace("INFLIGHT COMPLETED");
                    Long parentInstanceKey = inflightCallActivities.remove(workflowInstanceKey);
                    logger.trace("Sub process {} with key {} ended from parent instance {}", bpmnProcessId, callActivityKey, parentInstanceKey);
                    workflowInstanceKey = parentInstanceKey;
                }
                inflightTransferManager.transferEnded(workflowInstanceKey, timestamp);
            }
        } else if (transactionRequestType.equals(bpmnProcess.getType())) {
            if ("ELEMENT_ACTIVATING".equals(intent)) {
                inflightTransactionRequestManager.transactionRequestStarted(workflowInstanceKey, timestamp, bpmnProcess.getDirection());
            } else if ("ELEMENT_COMPLETED".equals(intent)) {
                inflightTransactionRequestManager.transactionRequestEnded(workflowInstanceKey, timestamp);
            }
        } else if (batchType.equals(bpmnProcess.getType())) {
            if ("ELEMENT_ACTIVATING".equals(intent)) {
                inflightBatchManager.batchStarted(workflowInstanceKey, timestamp, bpmnProcess.getDirection());
            } else if ("ELEMENT_COMPLETED".equals(intent)) {
                inflightBatchManager.batchEnded(workflowInstanceKey, timestamp);
            }
        } else {
            logger.error("Skip parsing bpmnProcess: {}", bpmnProcessId);
        }
    }

    public void processTask(DocumentContext json) {
        String type = json.read("$.value.type");
        if (type == null) {
            return;
        }

        Long workflowInstanceKey = json.read("$.value.processInstanceKey");
        String newElementId = json.read("$.value.elementId");
        Long newTimestamp = json.read("$.timestamp");
        String newIntent = json.read("$.intent");
        List<Task> existingTasks = taskRepository.findByWorkflowInstanceKey(workflowInstanceKey);
        if (existingTasks != null && !existingTasks.isEmpty()) {
            if (existingTasks.stream().filter(existing -> {
                return newElementId.equals(existing.getElementId()) && newIntent.equals(existing.getIntent()); // task intent inserts happens for only once
            }).findFirst().orElse(null) != null) {
                logger.info("Task {} with intent {} already inserted at {} for instance {}, skip processing!",
                        newElementId,
                        newIntent,
                        newTimestamp,
                        workflowInstanceKey);
                return;
            }
        }

        Task task = new Task();
        task.setWorkflowInstanceKey(workflowInstanceKey);
        task.setWorkflowKey(json.read("$.value.processDefinitionKey"));
        task.setTimestamp(newTimestamp);
        task.setIntent(newIntent);
        task.setRecordType(json.read("$.recordType"));
        task.setType(type);
        task.setElementId(newElementId);
        taskRepository.save(task);
    }
}
