package hu.dpc.phee.operator.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class BpmnProcess {

    private String id, direction, type;

    public BpmnProcess(String type) {
        this.type = type;
    }
}
