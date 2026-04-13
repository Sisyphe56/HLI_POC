package com.hanwha.setdata.extract.cycle;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Ported from Python {@code CycleRule} (extract_payment_cycle_v2.py).
 *
 * <p>Represents a context-to-cycles rule: when a record matches all tokens in
 * {@code contexts}, emit the listed {@code cycles}. {@code priority} equals the
 * number of context tokens — higher priority rules win in {@code pick_cycles}.
 */
public final class CycleRule {
    public final List<String> contexts;
    public final List<String> cycles;
    public final int priority;

    public CycleRule(List<String> contexts, List<String> cycles) {
        this.contexts = List.copyOf(contexts);
        // Python: tuple(dict.fromkeys(cycles)) — order-preserving dedupe
        this.cycles = List.copyOf(new ArrayList<>(new LinkedHashSet<>(cycles)));
        this.priority = this.contexts.size();
    }
}
