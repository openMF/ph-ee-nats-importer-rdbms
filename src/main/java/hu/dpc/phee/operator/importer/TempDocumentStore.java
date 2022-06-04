package hu.dpc.phee.operator.importer;

import com.jayway.jsonpath.DocumentContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TempDocumentStore {

    private final Map<Object, List<DocumentContext>> tempVariableEvents = new HashMap<>();
    private final Map<Object, String> workflowkeyBpmnAccociations = new ConcurrentHashMap<>();

    public String getBpmnprocessId(Object workflowKey) {
        return workflowkeyBpmnAccociations.get(workflowKey);
    }

    public void setBpmnprocessId(Object workflowKey, String bpmnprocessId) {
        workflowkeyBpmnAccociations.putIfAbsent(workflowKey, bpmnprocessId);
    }

    public void storeDocument(Object workflowKey, DocumentContext document) {
        synchronized (tempVariableEvents) {
            List<DocumentContext> existingEvents = tempVariableEvents.get(workflowKey);
            if (existingEvents == null) {
                existingEvents = new ArrayList<>();
            }
            existingEvents.add(document);
            tempVariableEvents.put(workflowKey, existingEvents);
        }
    }

    public List<DocumentContext> takeStoredDocuments(Object workflowKey) {
        synchronized (tempVariableEvents) {
            List<DocumentContext> removedDocuments = tempVariableEvents.remove(workflowKey);
            return removedDocuments == null ? Collections.emptyList() : removedDocuments;
        }
    }
}
