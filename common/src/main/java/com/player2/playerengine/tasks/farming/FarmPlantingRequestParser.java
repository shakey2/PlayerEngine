package com.player2.playerengine.tasks.farming;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded parser shared by the direct command, agentic validator, and step factory. */
public final class FarmPlantingRequestParser {
    public static final int MAX_RAW_REQUEST_LENGTH = 1_024;
    public static final int MAX_REQUEST_TYPES = 12;
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "requests", "x", "y", "z", "farm_policy");

    private FarmPlantingRequestParser() {
    }

    public record Parsed(
            List<FarmPlantingRequest> requests,
            BlockPos exactCenter,
            FarmPlantingPolicy policy,
            String error) {
        public Parsed {
            requests = requests == null ? List.of() : List.copyOf(requests);
            exactCenter = exactCenter == null ? null : exactCenter.immutable();
            policy = policy == null ? FarmPlantingPolicy.preserve() : policy;
            error = error == null ? "" : error;
        }

        public boolean valid() {
            return error.isEmpty();
        }

        public int totalCount() {
            int total = 0;
            for (FarmPlantingRequest request : requests) {
                total = Math.addExact(total, request.count());
            }
            return total;
        }
    }

    public static Parsed parseArgs(Map<String, String> rawArgs) {
        Map<String, String> args = rawArgs == null ? Map.of() : rawArgs;
        for (String key : args.keySet()) {
            if (key == null || !ALLOWED_KEYS.contains(key)) {
                return invalid("unknown_argument");
            }
        }
        String rawRequests = args.get("requests");
        boolean hasX = args.containsKey("x");
        boolean hasY = args.containsKey("y");
        boolean hasZ = args.containsKey("z");
        if (hasX != hasY || hasX != hasZ) {
            return invalid("coordinates_all_or_none");
        }
        BlockPos exact = null;
        if (hasX) {
            try {
                exact = new BlockPos(
                        Integer.parseInt(args.get("x")),
                        Integer.parseInt(args.get("y")),
                        Integer.parseInt(args.get("z")));
            } catch (NumberFormatException | NullPointerException invalid) {
                return invalid("coordinates_invalid");
            }
        }
        return parse(rawRequests, exact, args.get("farm_policy"));
    }

    public static Parsed parse(String rawRequests, BlockPos exactCenter, String rawPolicy) {
        try {
            List<FarmPlantingRequest> requests = parseRequests(rawRequests);
            FarmPlantingPolicy policy = FarmPlantingPolicy.parse(rawPolicy);
            if (policy.kind() != FarmPlantingPolicy.Kind.PRESERVE && exactCenter == null) {
                return invalid("policy_requires_exact_farm");
            }
            if (policy.kind() == FarmPlantingPolicy.Kind.SET) {
                String restricted = policy.plantingItemId();
                for (FarmPlantingRequest request : requests) {
                    if (!restricted.equals(request.plantingItemId())) {
                        return invalid("policy_request_mismatch");
                    }
                }
            }
            return new Parsed(requests, exactCenter, policy, "");
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            return invalid("requests_invalid");
        }
    }

    static List<FarmPlantingRequest> parseRequests(String raw) {
        String value = Objects.requireNonNull(raw, "requests").strip();
        if (value.isEmpty() || value.length() > MAX_RAW_REQUEST_LENGTH) {
            throw new IllegalArgumentException("requests are empty or too long");
        }
        LinkedHashMap<String, Integer> merged = new LinkedHashMap<>();
        String[] entries = value.split(",", -1);
        if (entries.length > MAX_REQUEST_TYPES) {
            throw new IllegalArgumentException("too many crop request types");
        }
        for (String entry : entries) {
            int equals = entry.indexOf('=');
            if (equals < 1 || equals != entry.lastIndexOf('=') || equals == entry.length() - 1) {
                throw new IllegalArgumentException("request must be item=count");
            }
            String itemId = FarmPlantingRequest.canonicalId(entry.substring(0, equals).strip());
            int count;
            try {
                count = Integer.parseInt(entry.substring(equals + 1).strip());
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("request count is not an integer", invalid);
            }
            if (count < 1 || count > FarmPlotPolicy.SOIL_CELL_COUNT) {
                throw new IllegalArgumentException("request count is outside the farm footprint");
            }
            merged.merge(itemId, count, Math::addExact);
        }
        if (merged.size() > MAX_REQUEST_TYPES) {
            throw new IllegalArgumentException("too many merged crop request types");
        }
        ArrayList<FarmPlantingRequest> result = new ArrayList<>(merged.size());
        int total = 0;
        for (Map.Entry<String, Integer> entry : merged.entrySet()) {
            total = Math.addExact(total, entry.getValue());
            if (total > FarmPlotPolicy.SOIL_CELL_COUNT) {
                throw new IllegalArgumentException("requested plants exceed one farm");
            }
            result.add(new FarmPlantingRequest(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(result);
    }

    private static Parsed invalid(String token) {
        return new Parsed(List.of(), null, FarmPlantingPolicy.preserve(), token);
    }
}
