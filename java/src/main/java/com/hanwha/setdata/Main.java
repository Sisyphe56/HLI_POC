package com.hanwha.setdata;

import com.hanwha.setdata.compare.CompareProductData;
import com.hanwha.setdata.extract.ProductClassificationExtractor;
import com.hanwha.setdata.extract.annuity.AnnuityAgeExtractor;
import com.hanwha.setdata.extract.cycle.PaymentCycleExtractor;
import com.hanwha.setdata.extract.joinage.JoinAgeExtractor;
import com.hanwha.setdata.extract.period.InsurancePeriodExtractor;
import com.hanwha.setdata.mapping.MapProductCode;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unified pipeline entry point.
 *
 * <p>Subcommands:
 * <pre>
 *   classify [args...]   → ProductClassificationExtractor
 *   period   [args...]   → InsurancePeriodExtractor
 *   cycle    [args...]   → PaymentCycleExtractor
 *   annuity  [args...]   → AnnuityAgeExtractor
 *   joinage  [args...]   → JoinAgeExtractor
 *   map      [args...]   → MapProductCode
 *   compare  [args...]   → CompareProductData
 *   all                  → run every stage with default paths
 * </pre>
 *
 * <p>Each subcommand delegates to the module's own {@code main(String[])},
 * so per-module flags remain identical to the standalone invocations.
 */
public final class Main {

    @FunctionalInterface
    private interface SubMain {
        void run(String[] args) throws Exception;
    }

    private static final Map<String, SubMain> COMMANDS = new LinkedHashMap<>();
    static {
        COMMANDS.put("classify", ProductClassificationExtractor::main);
        COMMANDS.put("period", InsurancePeriodExtractor::main);
        COMMANDS.put("cycle", PaymentCycleExtractor::main);
        COMMANDS.put("annuity", AnnuityAgeExtractor::main);
        COMMANDS.put("joinage", JoinAgeExtractor::main);
        COMMANDS.put("map", MapProductCode::main);
        COMMANDS.put("compare", CompareProductData::main);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            usage();
            System.exit(2);
        }
        String cmd = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        if ("all".equals(cmd)) {
            runAll(rest);
            return;
        }

        SubMain sub = COMMANDS.get(cmd);
        if (sub == null) {
            System.err.println("unknown subcommand: " + cmd);
            usage();
            System.exit(2);
        }
        sub.run(rest);
    }

    private static void runAll(String[] passthrough) throws Exception {
        // Extraction stages (order matters: period/annuity/joinage consume
        // 상품분류 JSON; joinage also consumes 보기납기 JSON).
        System.out.println("==> classify");
        ProductClassificationExtractor.main(passthrough);
        System.out.println("==> cycle");
        PaymentCycleExtractor.main(passthrough);
        System.out.println("==> period");
        InsurancePeriodExtractor.main(passthrough);
        System.out.println("==> annuity");
        AnnuityAgeExtractor.main(passthrough);
        System.out.println("==> joinage");
        JoinAgeExtractor.main(passthrough);
        for (String ds : new String[]{"product_classification", "payment_cycle", "annuity_age", "insurance_period", "join_age"}) {
            System.out.println("==> map " + ds);
            MapProductCode.main(new String[]{"--data-set", ds});
        }
        for (String ds : new String[]{"payment_cycle", "annuity_age", "insurance_period", "join_age"}) {
            System.out.println("==> compare " + ds);
            CompareProductData.main(new String[]{"--data-set", ds});
        }
    }

    private static void usage() {
        System.err.println("usage: setdata <subcommand> [args...]");
        System.err.println("  subcommands: " + String.join(", ", COMMANDS.keySet()) + ", all");
    }

    private Main() {}
}
