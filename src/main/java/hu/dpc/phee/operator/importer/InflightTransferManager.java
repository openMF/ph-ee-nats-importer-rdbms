package hu.dpc.phee.operator.importer;

import hu.dpc.phee.operator.entity.transfer.Transfer;
import hu.dpc.phee.operator.entity.transfer.TransferRepository;
import hu.dpc.phee.operator.entity.transfer.TransferStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class InflightTransferManager {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final Map<Long, Transfer> inflightTransfers = new HashMap<>();

    @Autowired
    private TransferRepository transferRepository;

    public void transferStarted(Long workflowInstanceKey, Long timestamp, String direction) {
        logger.trace("TRANSFER STARTED");
        Transfer transfer = getOrCreateTransfer(workflowInstanceKey);
        logger.trace("transfer.getStartedAt() "+transfer.getStartedAt());
        if (transfer.getStartedAt() == null) {
            transfer.setDirection(direction);
            transfer.setStartedAt(new Date(timestamp));
            transferRepository.save(transfer);
        } else {
            logger.trace("transfer {} already started at {}", workflowInstanceKey, transfer.getStartedAt());
        }
    }

    public void transferEnded(Long workflowInstanceKey, Long timestamp) {
        logger.trace("TRANSFER ENDED");
        synchronized (inflightTransfers) {
            Transfer transfer = inflightTransfers.remove(workflowInstanceKey);
            if (transfer == null) {
                logger.error("failed to remove in-flight transfer {}", workflowInstanceKey);
                transfer = transferRepository.findByWorkflowInstanceKey(workflowInstanceKey);
                if (transfer == null || transfer.getCompletedAt() != null) {
                    logger.error("completed event arrived for non existent transfer {} or it was already finished!", workflowInstanceKey);
                    return;
                }
            }
            transfer.setCompletedAt(new Date(timestamp));
            transfer.setStatus(TransferStatus.COMPLETED);
            transferRepository.save(transfer);
            logger.trace("transfer finished {}", transfer.getWorkflowInstanceKey());
        }
    }

    public Transfer getOrCreateTransfer(Long workflowInstanceKey) {
        logger.trace("TRANSFER GET OR CREATE");
        synchronized (inflightTransfers) {
            Transfer transfer = inflightTransfers.get(workflowInstanceKey);
            if (transfer == null) {
                transfer = transferRepository.findByWorkflowInstanceKey(workflowInstanceKey);
                if (transfer == null) {
                    transfer = new Transfer(workflowInstanceKey); // Sets status to ONGOING
                    logger.trace("started in-flight transfer {}", transfer.getWorkflowInstanceKey());
                }
                inflightTransfers.put(workflowInstanceKey, transfer);
            }
            return transfer;
        }
    }
}