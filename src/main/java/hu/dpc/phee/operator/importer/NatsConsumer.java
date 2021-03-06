package hu.dpc.phee.operator.importer;

import com.jayway.jsonpath.DocumentContext;
import hu.dpc.phee.operator.entity.tenant.TenantServerConnection;
import hu.dpc.phee.operator.entity.tenant.TenantServerConnectionRepository;
import hu.dpc.phee.operator.entity.tenant.ThreadLocalContextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.cloud.stream.messaging.Processor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@EnableBinding(Processor.class)
public class NatsConsumer {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private RecordParser recordParser;

    @Autowired
    private TenantServerConnectionRepository repository;

    @Autowired
    private TempDocumentStore tempDocumentStore;

    @StreamListener(Processor.INPUT)
    public void listen(String rawData) {
        try {
            DocumentContext incomingRecord = JsonPathReader.parse(rawData);
            logger.trace("MESSAGE FROM NATS: {}", incomingRecord.jsonString());
            if("DEPLOYMENT".equals(incomingRecord.read("$.valueType"))) {
                logger.trace("Deployment event arrived for bpmn: {}, skip processing", incomingRecord.read("$.value.deployedWorkflows[0].bpmnProcessId", String.class));
                return;
            }

            if(incomingRecord.read("$.valueType").equals("VARIABLE_DOCUMENT")) {
                logger.trace("Skipping VARIABLE_DOCUMENT record ");
                return;
            }

            Object workflowKey = incomingRecord.read("$.value.processDefinitionKey");
            logger.trace("workflowKey "+workflowKey);
            String bpmnprocessIdWithTenant = incomingRecord.read("$.value.bpmnProcessId");
            logger.trace("bpmnprocessIdWithTenant "+bpmnprocessIdWithTenant);
            Object recordKey = incomingRecord.read("$.key");
            logger.trace("recordKey "+recordKey.toString());

            if(bpmnprocessIdWithTenant == null || bpmnprocessIdWithTenant.equalsIgnoreCase("") && workflowKey == null) {
                logger.trace("skipping record: {}", incomingRecord.jsonString());
                return;
            } 
            else if(bpmnprocessIdWithTenant == null || bpmnprocessIdWithTenant.equalsIgnoreCase("") && workflowKey!=null) {
                bpmnprocessIdWithTenant = tempDocumentStore.getBpmnprocessId(workflowKey);
                if (bpmnprocessIdWithTenant == null) {
                    tempDocumentStore.storeDocument(workflowKey, incomingRecord);
                    logger.info("Record with key {} workflowkey {} has no associated bpmn, stored temporarly", recordKey.toString(), workflowKey);
                    return;
                }
            } 
            else {
                tempDocumentStore.setBpmnprocessId(workflowKey, bpmnprocessIdWithTenant);
            }
            
            //logger.trace("bpmnprocessIdWithTenant "+bpmnprocessIdWithTenant);
            String tenantName = bpmnprocessIdWithTenant.substring(bpmnprocessIdWithTenant.indexOf("-") + 1);            
            //logger.trace("tenantName "+tenantName);
            String bpmnprocessId = bpmnprocessIdWithTenant.substring(0, bpmnprocessIdWithTenant.indexOf("-"));
            //logger.trace("bpmnprocessId "+bpmnprocessId);

            TenantServerConnection tenant = repository.findOneBySchemaName(tenantName);
            ThreadLocalContextUtil.setTenant(tenant);

            List<DocumentContext> documents = new ArrayList<>();
            List<DocumentContext> storedDocuments = tempDocumentStore.takeStoredDocuments(workflowKey);
            if(!storedDocuments.isEmpty()) {
                logger.info("Reprocessing {} previously stored records with workflowKey {}", storedDocuments.size(), workflowKey);
                documents.addAll(storedDocuments);
            }
            documents.add(incomingRecord);

            for(DocumentContext doc : documents) {
                try {
                    String valueType = doc.read("$.valueType");
                    logger.trace("TIPO DE DOCUMENTO RECIBIDO "+valueType);
                    switch (valueType) {
                        case "VARIABLE":
                            DocumentContext processedVariable = recordParser.processVariable(doc); // TODO prepare for parent workflow
                            recordParser.addVariableToEntity(processedVariable, bpmnprocessId); // Call to store transfer
                            break;
                        case "JOB":
                            recordParser.processTask(doc);
                            break;
                        case "PROCESS_INSTANCE":
                            if ("PROCESS".equals(doc.read("$.value.bpmnElementType"))) {
                                recordParser.processWorkflowInstance(doc);
                            }
                            break;
                    }
                } catch (Exception ex) {
                    logger.error("Failed to process document:\n{}\nerror: {}\ntrace: {}",
                            doc,
                            ex.getMessage(),
                            limitStackTrace(ex));
                    tempDocumentStore.storeDocument(workflowKey, doc);
                }
            }
        } catch (Exception ex) {
            logger.error("Could not parse zeebe event:\n{}\nerror: {}\ntrace: {}",
                    rawData,
                    ex.getMessage(),
                    limitStackTrace(ex));
        } finally {
            ThreadLocalContextUtil.clear();
        }
    }

    private String limitStackTrace(Exception ex) {
        return Arrays.stream(ex.getStackTrace())
                .limit(10)
                .map(StackTraceElement::toString)
                .collect(Collectors.joining("\n"));
    }
}
