package integrations.sentinel;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads a fixed set of flow rules into Sentinel's process-wide rule manager and removes them on
 * close.
 *
 * <p>Sentinel rules are global to the JVM. This holder suits an application that defines its rules
 * in code; a production setup usually registers a dynamic rule data source instead. Closing it
 * clears every flow rule, including rules loaded by anyone else.
 */
public final class SentinelFlowRules implements AutoCloseable {

    private final List<FlowRule> rules;

    /**
     * Loads {@code rules}, replacing every flow rule currently loaded.
     *
     * @param rules the rules to load
     */
    public SentinelFlowRules(List<FlowRule> rules) {
        this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
        FlowRuleManager.loadRules(this.rules);
    }

    /** @return the loaded rules */
    public List<FlowRule> rules() {
        return rules;
    }

    @Override
    public void close() {
        FlowRuleManager.loadRules(Collections.emptyList());
    }
}
