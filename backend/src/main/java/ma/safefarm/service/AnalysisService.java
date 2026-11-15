package ma.safefarm.service;

import ma.safefarm.entity.Farm;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Turns 15 years of NASA POWER daily data into:
 *  - monthly rainfall, ET0 (Hargreaves-Samani) and climatic water balance,
 *  - simplified SPI-3, SPI-6 and SPEI-3 drought indices,
 *  - a monthly climatology and the last 12 months versus normal,
 *  - an indicative crop-water outlook for the next 3 months.
 * Everything is exploratory and is labelled as such in the payload.
 */
@Service
public class AnalysisService {

    private final NasaPowerService nasa;

    public AnalysisService(NasaPowerService nasa) { this.nasa = nasa; }

    public Map<String, Object> analyze(Farm farm) {
        Map<String, Object> raw = nasa.fetch(farm.getLatitude(), farm.getLongitude());
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> params = (Map<String, Map<String, Object>>) raw.get("parameters");
        return compute(farm, params, String.valueOf(raw.get("startDate")), String.valueOf(raw.get("endDate")),
                String.valueOf(raw.get("source")));
    }

    /** Network-free core, used by the tests. */
    Map<String, Object> compute(Farm farm, Map<String, Map<String, Object>> params, String startDate, String endDate,
                                String source) {
        double lat = farm.getLatitude();
        TreeMap<LocalDate, Double> rain = series(params.get("PRECTOTCORR"));
        TreeMap<LocalDate, Double> tmax = series(params.get("T2M_MAX"));
        TreeMap<LocalDate, Double> tmin = series(params.get("T2M_MIN"));
        TreeMap<LocalDate, Double> t2m = series(params.get("T2M"));
        TreeMap<LocalDate, Double> rh = series(params.get("RH2M"));
        TreeMap<LocalDate, Double> solar = series(params.get("ALLSKY_SFC_SW_DWN"));

        // ---- daily -> monthly accumulators
        class Acc { double rain, et0, tmean; int rainN, et0N, tN; }
        TreeMap<YearMonth, Acc> acc = new TreeMap<>();
        for (var e : rain.entrySet()) acc.computeIfAbsent(YearMonth.from(e.getKey()), k -> new Acc()).rain += e.getValue();
        for (var e : rain.entrySet()) acc.get(YearMonth.from(e.getKey())).rainN++;
        for (var e : tmax.entrySet()) {
            Double lo = tmin.get(e.getKey());
            if (lo == null) continue;
            Acc a = acc.computeIfAbsent(YearMonth.from(e.getKey()), k -> new Acc());
            a.et0 += ClimateMath.hargreavesEt0(e.getValue(), lo, lat, e.getKey().getDayOfYear());
            a.et0N++;
            a.tmean += (e.getValue() + lo) / 2;
            a.tN++;
        }

        // ---- complete months only (>= 90% valid days), scaled to full-month totals
        TreeMap<YearMonth, Double> mRain = new TreeMap<>(), mEt0 = new TreeMap<>(), mBalance = new TreeMap<>(),
                mTemp = new TreeMap<>();
        for (var e : acc.entrySet()) {
            int len = e.getKey().lengthOfMonth();
            Acc a = e.getValue();
            if (a.rainN >= 0.9 * len && a.et0N >= 0.9 * len) {
                double r = a.rain * len / a.rainN, et = a.et0 * len / a.et0N;
                mRain.put(e.getKey(), r);
                mEt0.put(e.getKey(), et);
                mBalance.put(e.getKey(), r - et);
                mTemp.put(e.getKey(), a.tmean / a.tN);
            }
        }
        if (mRain.size() < 12 * 10) {
            throw new IllegalStateException("NASA POWER series too short to compute drought indices (" + mRain.size() + " valid months).");
        }
        YearMonth end = mRain.lastKey();

        // ---- drought indices
        Map<String, Object> indices = new LinkedHashMap<>();
        List<Double> zs = new ArrayList<>();
        addIndex(indices, zs, "spi3", "Cumulative rainfall, 3 months", ClimateMath.windowAnomaly(mRain, end, 3, 5));
        addIndex(indices, zs, "spi6", "Cumulative rainfall, 6 months", ClimateMath.windowAnomaly(mRain, end, 6, 5));
        addIndex(indices, zs, "spei3", "Water balance (rain − ET0), 3 months", ClimateMath.windowAnomaly(mBalance, end, 3, 5));

        Map<String, Object> composite = new LinkedHashMap<>();
        if (zs.isEmpty()) {
            composite.put("available", false);
            composite.put("severity", -1);
            composite.put("level", "Undetermined (dry season: indices not significant)");
        } else {
            double z = zs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            int sev = ClimateMath.severity(z);
            composite.put("available", true);
            composite.put("z", round(z));
            composite.put("severity", sev);
            composite.put("level", ClimateMath.severityLabel(sev));
            composite.put("indicesUsed", zs.size());
        }

        // ---- climatology by calendar month
        double[] cRain = new double[13], cEt0 = new double[13], cTemp = new double[13];
        int[] cN = new int[13];
        for (var e : mRain.entrySet()) {
            int m = e.getKey().getMonthValue();
            cRain[m] += e.getValue();
            cEt0[m] += mEt0.get(e.getKey());
            cTemp[m] += mTemp.get(e.getKey());
            cN[m]++;
        }
        List<Map<String, Object>> monthly = new ArrayList<>();
        String[] names = {"", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        for (int m = 1; m <= 12; m++) {
            if (cN[m] == 0) continue;
            double r = cRain[m] / cN[m], et = cEt0[m] / cN[m];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", names[m]);
            row.put("monthNumber", m);
            row.put("rainMm", round(r));
            row.put("et0Mm", round(et));
            row.put("temperatureC", round(cTemp[m] / cN[m]));
            row.put("deficitMm", round(Math.max(0, et - r)));
            monthly.add(row);
        }

        // ---- last 12 complete months vs normal
        List<Map<String, Object>> recent = new ArrayList<>();
        for (int i = 11; i >= 0; i--) {
            YearMonth ym = end.minusMonths(i);
            Double r = mRain.get(ym);
            if (r == null) continue;
            int m = ym.getMonthValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", names[m] + " " + String.valueOf(ym.getYear()).substring(2));
            row.put("rainMm", round(r));
            row.put("normalRainMm", round(cRain[m] / cN[m]));
            row.put("et0Mm", round(mEt0.get(ym)));
            recent.add(row);
        }

        // ---- crop water outlook, next 3 months
        String crop = farm.getCurrentCrop();
        double kc = CropKc.of(crop);
        double area = farm.getArea() == null ? 0 : farm.getArea();
        List<Map<String, Object>> months = new ArrayList<>();
        double totalDeficit = 0;
        for (int i = 1; i <= 3; i++) {
            YearMonth ym = end.plusMonths(i);
            int m = ym.getMonthValue();
            if (cN[m] == 0) continue;
            double etc = kc * cEt0[m] / cN[m], r = cRain[m] / cN[m], deficit = Math.max(0, etc - r);
            totalDeficit += deficit;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", names[m]);
            row.put("etcMm", round(etc));
            row.put("normalRainMm", round(r));
            row.put("deficitMm", round(deficit));
            months.add(row);
        }
        Map<String, Object> irrigation = new LinkedHashMap<>();
        irrigation.put("crop", crop == null || crop.isBlank() ? "Not provided" : crop);
        irrigation.put("kc", kc);
        irrigation.put("kcIsDefault", !CropKc.known(crop));
        irrigation.put("months", months);
        irrigation.put("totalDeficitMm", round(totalDeficit));
        irrigation.put("volumePerHectareM3", round(totalDeficit * 10));
        irrigation.put("farmVolumeM3", round(totalDeficit * 10 * area));
        irrigation.put("note", "Indicative estimate: ETc = Kc × ET0 (Hargreaves) minus the month's normal rainfall. "
                + "Kc is an FAO-56 mid-season value, so it overestimates demand outside full crop growth. "
                + "1 mm = 10 m³/ha. It does not replace an irrigation schedule or a local agronomist.");

        // ---- headline averages (daily, whole period)
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("farm", farm);
        out.put("source", source);
        out.put("startDate", startDate);
        out.put("endDate", endDate);
        out.put("validMonths", mRain.size());
        out.put("analysisEndMonth", end.toString());
        out.put("averageDailyRainMm", round(mean(rain)));
        out.put("averageTemperatureC", round(mean(t2m)));
        out.put("averageRelativeHumidityPct", round(mean(rh)));
        out.put("averageSolarRadiation", round(mean(solar)));
        out.put("annualRainMm", round(sumMonths(cRain, cN)));
        out.put("annualEt0Mm", round(sumMonths(cEt0, cN)));
        out.put("indices", indices);
        out.put("drought", composite);
        out.put("droughtSignal", composite.get("level"));
        out.put("monthly", monthly);
        out.put("recent", recent);
        out.put("irrigation", irrigation);
        out.put("methodology", List.of(
                "Rainfall: NASA POWER PRECTOTCORR (~0.5° grid cell, not a measurement of the plot).",
                "ET0: Hargreaves-Samani from T2M_MAX, T2M_MIN and latitude (FAO-56).",
                "Simplified SPI-3, SPI-6 and SPEI-3: z-score of the n-month sum against the same months of previous years (up to 15), not the gamma / log-logistic fit of the official indices.",
                "An index is only computed when the window is climatologically wet (normal > 5 mm); in the dry summer it is marked not significant.",
                "Exploratory indicators: they are not a drought forecast."));
        return out;
    }

    private void addIndex(Map<String, Object> indices, List<Double> zs, String key, String label,
                          ClimateMath.Anomaly a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        if (a == null) {
            m.put("available", false);
        } else {
            m.put("available", true);
            m.put("months", a.months());
            m.put("valueMm", round(a.value()));
            m.put("normalMm", round(a.normal()));
            m.put("anomalyPct", round(a.anomalyPct()));
            m.put("z", round(a.z()));
            m.put("percentile", round(a.percentile()));
            m.put("baselineYears", a.baselineYears());
            m.put("level", ClimateMath.severityLabel(ClimateMath.severity(a.z())));
            zs.add(a.z());
        }
        indices.put(key, m);
    }

    private TreeMap<LocalDate, Double> series(Map<String, Object> raw) {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        if (raw == null) return out;
        for (var e : raw.entrySet()) {
            try {
                double v = Double.parseDouble(String.valueOf(e.getValue()));
                if (v <= -990) continue; // POWER fill value is -999
                out.put(LocalDate.parse(e.getKey(), DateTimeFormatter.BASIC_ISO_DATE), v);
            } catch (Exception ignored) { /* skip non-date keys / bad values */ }
        }
        return out;
    }

    private double mean(Map<LocalDate, Double> m) {
        return m.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private double sumMonths(double[] sums, int[] n) {
        double t = 0;
        for (int m = 1; m <= 12; m++) if (n[m] > 0) t += sums[m] / n[m];
        return t;
    }

    private static double round(double x) { return Math.round(x * 100.0) / 100.0; }

    /** FAO-56 mid-season crop coefficients (indicative). */
    static final class CropKc {
        private static final LinkedHashMap<String, Double> KC = new LinkedHashMap<>();
        static {
            KC.put("pois chiche", 1.0); KC.put("chickpea", 1.0); KC.put("lentille", 1.1); KC.put("lentil", 1.1); KC.put("feve", 1.15); KC.put("faba", 1.15); KC.put("fava", 1.15); KC.put("broad bean", 1.15); KC.put("vesce", 1.0); KC.put("vetch", 1.0);
            KC.put("ble", 1.15); KC.put("wheat", 1.15); KC.put("orge", 1.15); KC.put("barley", 1.15); KC.put("mais", 1.2); KC.put("maize", 1.2); KC.put("corn", 1.2); KC.put("tournesol", 1.0); KC.put("sunflower", 1.0);
            KC.put("betterave", 1.2); KC.put("beet", 1.2); KC.put("pomme de terre", 1.15); KC.put("potato", 1.15); KC.put("tomate", 1.15); KC.put("tomato", 1.15); KC.put("luzerne", 1.0); KC.put("alfalfa", 1.0);
            KC.put("olivier", 0.7); KC.put("olive", 0.7); KC.put("agrume", 0.7); KC.put("citrus", 0.7); KC.put("orange", 0.7);
        }
        static String norm(String s) {
            if (s == null) return "";
            return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        }
        private static boolean hit(String n, String key) {
            return key.length() <= 3 ? n.matches(".*\\b" + key + "\\b.*") : n.contains(key);
        }
        static boolean known(String crop) {
            String n = norm(crop);
            return KC.keySet().stream().anyMatch(k -> hit(n, k));
        }
        static double of(String crop) {
            String n = norm(crop);
            for (var e : KC.entrySet()) if (hit(n, e.getKey())) return e.getValue();
            return 1.0;
        }
    }
}
