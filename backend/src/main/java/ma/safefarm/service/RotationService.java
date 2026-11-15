package ma.safefarm.service;

import ma.safefarm.entity.Farm;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;

/**
 * Rule-based rotation comparison. Nothing here is machine learning: each candidate crop gets a transparent
 * 0-100 suitability score from weighted factors (water fit, drought tolerance, soil health, soil cover, income,
 * soil suitability) whose weights depend on the farmer's priority and on the current drought severity.
 * The crop catalogue is a generic agronomic simplification and must be validated locally.
 * Free-text inputs are matched in English and French (e.g. "Soft wheat" / "Blé tendre").
 */
@Service
public class RotationService {

    /** water / tolerance / cover / income: 1 (low) .. 3 (high). */
    record Crop(String[] keys, String name, String family, int water, int tolerance, int cover, int income,
                boolean legume, boolean fallow, String needMm, String risk, Set<String> badSoils) {}

    static final Set<String> SANDY = Set.of("sandy", "sableux");
    static final Set<String> CLAY = Set.of("clay", "argileux");

    static final List<Crop> CATALOG = List.of(
            new Crop(new String[]{"ble tendre", "soft wheat", "bread wheat"}, "Soft wheat", "Poaceae", 2, 2, 2, 2, false, false, "450–650",
                    "Water stress at grain filling, rusts, dependence on inputs", SANDY),
            new Crop(new String[]{"ble dur", "durum"}, "Durum wheat", "Poaceae", 2, 2, 2, 2, false, false, "400–600",
                    "Water stress and heat scorch at grain filling, rusts", SANDY),
            new Crop(new String[]{"orge", "barley"}, "Barley", "Poaceae", 1, 3, 2, 1, false, false, "300–500",
                    "Variable yield in a very dry year, leaf diseases", Set.of()),
            new Crop(new String[]{"pois chiche", "chickpea"}, "Chickpea", "Fabaceae", 1, 2, 2, 3, true, false, "300–450",
                    "Ascochyta blight, sensitivity to frost and humidity at flowering, sowing date to fit", CLAY),
            new Crop(new String[]{"lentille", "lentil"}, "Lentil", "Fabaceae", 1, 3, 1, 3, true, false, "250–400",
                    "Weak competitor against weeds, variable yield, markets to check", CLAY),
            new Crop(new String[]{"feve", "faba", "fava", "broad bean"}, "Faba bean", "Fabaceae", 2, 1, 3, 2, true, false, "350–500",
                    "Sensitive to water stress and heat, broomrape", SANDY),
            new Crop(new String[]{"vesce", "vetch"}, "Vetch-oat (forage)", "Fabaceae", 1, 3, 3, 1, true, false, "250–400",
                    "Forage market and cutting calendar to confirm", Set.of()),
            new Crop(new String[]{"tournesol", "sunflower"}, "Sunflower", "Asteraceae", 2, 2, 2, 2, false, false, "450–650",
                    "Sensitive to water stress at flowering, bird damage, adapted seed to source", Set.of()),
            new Crop(new String[]{"betterave", "beet"}, "Sugar beet", "Amaranthaceae", 3, 1, 2, 3, false, false, "600–800",
                    "Very high water demand: reliable irrigation and a sugar-mill contract are essential", SANDY),
            new Crop(new String[]{"mais", "maize", "corn"}, "Maize (silage)", "Poaceae", 3, 1, 3, 2, false, false, "500–800",
                    "Very high water demand, high risk in a dry year", SANDY));

    static final Crop FALLOW = new Crop(new String[]{"jachere", "fallow"}, "Fallow", "—", 1, 3, 1, 1, false, true, "≈ 0",
            "Prolonged bare soil: erosion, moisture loss, weeds to manage", Set.of());

    private static final String[] FACTORS = {"water", "drought", "soilHealth", "cover", "income", "production", "soilFit"};

    public Map<String, Object> scenarios(Farm f, Map<String, Object> analysis) {
        String currentName = blank(f.getCurrentCrop()) ? "Cereal" : f.getCurrentCrop().trim();
        Crop current = find(f.getCurrentCrop());
        Crop previous = find(f.getPreviousCrop());
        Ctx ctx = new Ctx(regime(f.getWaterAvailability()), severity(analysis), norm(f.getSoilType()),
                f.getPriority() == null ? "" : f.getPriority());

        boolean monoculture = current != null && previous != null && current.family().equals(previous.family())
                && !current.fallow();

        // ---- candidate sequences current -> A -> B
        record Seq(Crop a, Crop b, double score) {}
        List<Seq> seqs = new ArrayList<>();
        for (Crop a : CATALOG) {
            if (excluded(a, ctx) || (current != null && a.family().equals(current.family()))) continue;
            for (Crop b : CATALOG) {
                if (b == a || excluded(b, ctx) || b.family().equals(a.family())) continue;
                double s = 0.6 * score(a, ctx, monoculture).total + 0.4 * score(b, ctx, false).total;
                seqs.add(new Seq(a, b, s));
            }
        }
        seqs.sort((x, y) -> Double.compare(y.score, x.score));

        List<Map<String, Object>> out = new ArrayList<>();
        out.add(reference(f, currentName, current, ctx));
        Seq first = seqs.isEmpty() ? null : seqs.get(0);
        Seq second = null;
        if (first != null) {
            for (Seq s : seqs) if (s.a != first.a) { second = s; break; }
        }
        int idx = 1;
        for (Seq s : new Seq[]{first, second}) {
            if (s == null) continue;
            out.add(alternative("Alternative " + idx++, currentName, s.a, s.b, s.score, ctx, monoculture, f));
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("climateAvailable", analysis != null);
        res.put("droughtLevel", ctx.severity < 0 ? "Unknown" : ClimateMath.severityLabel(ctx.severity));
        res.put("droughtSeverity", ctx.severity);
        res.put("waterRegime", regimeLabel(ctx.regime));
        res.put("priority", ctx.priority);
        res.put("monoculture", monoculture);
        res.put("scenarios", out);
        return res;
    }

    // ------------------------------------------------------------------ scoring

    record Ctx(String regime, int severity, String soil, String priority) {}

    static final class Score {
        double total;
        final Map<String, Double> parts = new LinkedHashMap<>();
    }

    static Score score(Crop c, Ctx ctx, boolean monoculture) {
        Map<String, Double> w = weights(ctx);
        double[] f = {
                waterFit(c, ctx),
                (c.tolerance() - 1) / 2.0,
                (c.legume() ? 0.6 : 0) + (c.cover() / 3.0) * 0.4,
                (c.cover() - 1) / 2.0,
                (c.income() - 1) / 2.0,
                c.fallow() ? 0 : 1,
                c.badSoils().contains(ctx.soil) ? 0 : 1};
        Score s = new Score();
        for (int i = 0; i < FACTORS.length; i++) {
            double part = w.get(FACTORS[i]) * f[i] * 100;
            s.parts.put(FACTORS[i], part);
            s.total += part;
        }
        if (monoculture && c.legume()) s.total += 8; // breaks cereal-on-cereal
        s.total = Math.min(100, s.total);
        return s;
    }

    /** 0..1 : how well the crop's water demand fits the regime and the current drought severity. */
    static double waterFit(Crop c, Ctx ctx) {
        double[] base = switch (ctx.regime) {
            case "rainfed" -> new double[]{1.0, 0.45, 0.0};
            case "irrigated" -> new double[]{0.9, 0.9, 0.8};
            default -> new double[]{1.0, 0.7, 0.3};
        };
        double v = base[c.water() - 1];
        int sev = Math.max(0, ctx.severity);
        v -= sev * (c.water() - 1) * 0.06;
        return Math.max(0, Math.min(1, v));
    }

    static Map<String, Double> weights(Ctx ctx) {
        Map<String, Double> w = new LinkedHashMap<>();
        w.put("water", 0.28); w.put("drought", 0.22); w.put("soilHealth", 0.15); w.put("cover", 0.10);
        w.put("income", 0.08); w.put("production", 0.07); w.put("soilFit", 0.10);
        String p = norm(ctx.priority);
        if (p.contains("water") || p.contains("eau")) { w.put("water", 0.40); w.put("drought", 0.15); }
        else if (p.contains("drought") || p.contains("secheresse")) { w.put("drought", 0.40); w.put("water", 0.20); }
        else if (p.contains("soil") || p.contains("sol")) { w.put("soilHealth", 0.35); w.put("water", 0.20); w.put("drought", 0.15); }
        else if (p.contains("erosion")) { w.put("cover", 0.30); w.put("soilHealth", 0.20); w.put("water", 0.20); w.put("drought", 0.10); }
        else if (p.contains("divers")) { w.put("income", 0.30); w.put("water", 0.20); w.put("drought", 0.15); }
        w.merge("drought", 0.04 * Math.max(0, ctx.severity), Double::sum);
        double sum = w.values().stream().mapToDouble(Double::doubleValue).sum();
        w.replaceAll((k, v) -> v / sum);
        return w;
    }

    /** Rain-fed farms cannot sustain the thirstiest crops. */
    static boolean excluded(Crop c, Ctx ctx) {
        return "rainfed".equals(ctx.regime) && c.water() >= 3;
    }

    // ------------------------------------------------------------------ scenario builders

    private Map<String, Object> reference(Farm f, String currentName, Crop current, Ctx ctx) {
        double s = current == null ? Double.NaN
                : 0.6 * score(current, ctx, false).total + 0.4 * score(FALLOW, ctx, false).total;
        String need = current == null ? "Crop not recognised in the catalogue: water need not estimated"
                : current.name() + " ≈ " + current.needMm() + " mm/season (order of magnitude); fallow ≈ 0";
        Map<String, Object> m = base("Current practice", currentName + " → fallow → " + currentName,
                "Baseline: the rotation the farm practises today, scored with the same rules as the alternatives.",
                need, "Low cover during the fallow",
                (current == null ? "" : current.risk() + ". ") + FALLOW.risk(),
                "Comparison baseline", "Baseline", Double.isNaN(s) ? null : s);
        m.put("scoreBreakdown", current == null ? Map.of() : round(score(current, ctx, false).parts));
        m.put("isReference", true);
        m.put("missingInformation", missing(ctx));
        return m;
    }

    private Map<String, Object> alternative(String name, String currentName, Crop a, Crop b, double s, Ctx ctx,
                                            boolean mono, Farm f) {
        Score sa = score(a, ctx, mono), sb = score(b, ctx, false);
        StringBuilder why = new StringBuilder();
        why.append("Current drought: ").append(ctx.severity < 0 ? "not assessed (NASA data unavailable)"
                : ClimateMath.severityLabel(ctx.severity)).append(". ");
        why.append("Water regime: ").append(regimeLabel(ctx.regime)).append(". ");
        if (!blank(f.getPriority())) why.append("Priority: ").append(f.getPriority()).append(". ");
        why.append(a.name()).append(": ").append(strength(a, ctx, mono)).append(' ');
        why.append(b.name()).append(": ").append(strength(b, ctx, false)).append(' ');
        why.append("Botanical families alternate (").append(a.family()).append(" → ").append(b.family()).append(").");

        boolean soilKnown = !ctx.soil.isBlank();
        String conf = ctx.severity >= 0 && soilKnown ? "Medium — generic rules, not validated locally"
                : "Low — incomplete context data";
        Map<String, Object> m = base(name, currentName + " → " + a.name() + " → " + b.name(), why.toString(),
                a.name() + " ≈ " + a.needMm() + " mm/season; " + b.name() + " ≈ " + b.needMm() + " mm/season (order of magnitude)",
                "Cover " + coverWord(a) + " then " + coverWord(b),
                a.risk() + ". " + b.risk() + ".",
                benefits(a, b, mono), conf, s);
        Map<String, Double> parts = new LinkedHashMap<>();
        for (String k : FACTORS) parts.put(k, 0.6 * sa.parts.get(k) + 0.4 * sb.parts.get(k));
        m.put("scoreBreakdown", round(parts));
        m.put("isReference", false);
        m.put("missingInformation", missing(ctx));
        return m;
    }

    private Map<String, Object> base(String name, String sequence, String reason, String water, String cover,
                                     String risks, String benefits, String confidence, Double score) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("sequence", sequence);
        m.put("reason", reason);
        m.put("waterNeed", water);
        m.put("soilCover", cover);
        m.put("risks", risks);
        m.put("potentialBenefits", benefits);
        m.put("confidence", confidence);
        m.put("score", score == null ? null : Math.round(score * 10) / 10.0);
        m.put("localValidationRequired", true);
        return m;
    }

    private String strength(Crop c, Ctx ctx, boolean mono) {
        List<String> s = new ArrayList<>();
        s.add("water need " + new String[]{"low", "moderate", "high"}[c.water() - 1]);
        s.add("drought tolerance " + new String[]{"low", "medium", "good"}[c.tolerance() - 1]);
        if (c.legume()) s.add("legume (nitrogen fixation)" + (mono ? ", breaks the cereal monoculture" : ""));
        if (c.badSoils().contains(ctx.soil)) s.add(ctx.soil + " soil poorly suited (to check)");
        return String.join(", ", s) + ".";
    }

    private String benefits(Crop a, Crop b, boolean mono) {
        List<String> s = new ArrayList<>();
        if (a.legume() || b.legume()) s.add("nitrogen input from a legume");
        s.add("disease cycle broken by alternating plant families");
        if (a.cover() >= 2 || b.cover() >= 2) s.add("better soil cover than a fallow");
        if (mono) s.add("exit from a cereal monoculture");
        return String.join("; ", s);
    }

    private String coverWord(Crop c) { return new String[]{"low", "medium", "high"}[c.cover() - 1]; }

    private List<String> missing(Ctx ctx) {
        List<String> l = new ArrayList<>();
        if (ctx.soil.isBlank()) l.add("Soil type (not provided)");
        l.add("Recent soil analysis (organic matter, salinity, depth)");
        l.add("Varieties and sowing dates");
        l.add("Disease history");
        l.add("Irrigation water volume actually available");
        l.add("Seed access and markets");
        l.add("Advice from a local agronomist");
        return l;
    }

    private Map<String, Double> round(Map<String, Double> in) {
        Map<String, Double> o = new LinkedHashMap<>();
        in.forEach((k, v) -> o.put(k, Math.round(v * 10) / 10.0));
        return o;
    }

    // ------------------------------------------------------------------ helpers

    static int severity(Map<String, Object> analysis) {
        if (analysis == null) return -1;
        Object d = analysis.get("drought");
        if (d instanceof Map<?, ?> m && m.get("severity") instanceof Number n) return n.intValue();
        return -1;
    }

    static String regime(String water) {
        String w = norm(water);
        if (w.contains("rain") || w.contains("pluvi")) return "rainfed";
        if (w.contains("irrig")) return "irrigated";
        return "mixed";
    }

    static String regimeLabel(String r) {
        return switch (r) { case "rainfed" -> "rain-fed (rainfall only)"; case "irrigated" -> "irrigated"; default -> "mixed"; };
    }

    static Crop find(String name) {
        String n = norm(name);
        if (n.isBlank()) return null;
        for (Crop c : CATALOG) for (String k : c.keys()) if (n.contains(k)) return c;
        if (n.matches(".*\\bble\\b.*") || n.contains("wheat")) return CATALOG.get(0);
        return null;
    }

    static boolean blank(String s) { return s == null || s.isBlank(); }

    static String norm(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }
}
