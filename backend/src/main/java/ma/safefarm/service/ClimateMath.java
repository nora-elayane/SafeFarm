package ma.safefarm.service;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;

/** Pure climate maths (no I/O) so it can be unit-tested. */
public final class ClimateMath {
    private ClimateMath() {}

    /** Extraterrestrial radiation Ra in MJ m-2 day-1 (FAO-56, eq. 21). */
    public static double extraterrestrialRadiation(double latDeg, int dayOfYear) {
        double phi = Math.toRadians(latDeg);
        double dr = 1 + 0.033 * Math.cos(2 * Math.PI / 365 * dayOfYear);
        double delta = 0.409 * Math.sin(2 * Math.PI / 365 * dayOfYear - 1.39);
        double x = -Math.tan(phi) * Math.tan(delta);
        double ws = Math.acos(Math.max(-1, Math.min(1, x)));
        return (24 * 60 / Math.PI) * 0.0820 * dr
                * (ws * Math.sin(phi) * Math.sin(delta) + Math.cos(phi) * Math.cos(delta) * Math.sin(ws));
    }

    /** Reference evapotranspiration ET0 (mm/day), Hargreaves-Samani, needs only Tmax/Tmin and latitude. */
    public static double hargreavesEt0(double tmax, double tmin, double latDeg, int dayOfYear) {
        double range = Math.max(0, tmax - tmin);
        double tmean = (tmax + tmin) / 2;
        double et0 = 0.0023 * 0.408 * extraterrestrialRadiation(latDeg, dayOfYear) * (tmean + 17.8) * Math.sqrt(range);
        return Math.max(0, et0);
    }

    /** Result of comparing a rolling n-month window with the same window in previous years. */
    public record Anomaly(int months, double value, double normal, double anomalyPct, double z, double percentile,
                          int baselineYears) {}

    /**
     * Simplified SPI / SPEI: z-score of the n-month sum ending at {@code end} against the same n-month window
     * in previous years. Returns null if data are incomplete, the baseline is too short (&lt;10 years) or
     * the window is climatologically dry (normal &lt; minNormal) where a standardised index is meaningless.
     * Note: this is a z-score on sums, not the gamma / log-logistic fit of the official SPI/SPEI.
     */
    public static Anomaly windowAnomaly(NavigableMap<YearMonth, Double> monthly, YearMonth end, int n,
                                        double minNormalAbs) {
        Double cur = windowSum(monthly, end, n);
        if (cur == null) return null;
        List<Double> base = new ArrayList<>();
        for (int k = 1; k <= 40; k++) {
            Double v = windowSum(monthly, end.minusYears(k), n);
            if (v != null) base.add(v);
        }
        if (base.size() < 10) return null;
        double mean = base.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = base.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / (base.size() - 1);
        double sd = Math.sqrt(var);
        if (sd < 1e-9 || Math.abs(mean) < minNormalAbs) return null;
        double z = Math.max(-3, Math.min(3, (cur - mean) / sd));
        double below = base.stream().filter(v -> v <= cur).count();
        double pct = 100.0 * below / base.size();
        double anomalyPct = mean == 0 ? 0 : 100.0 * (cur - mean) / Math.abs(mean);
        return new Anomaly(n, cur, mean, anomalyPct, z, pct, base.size());
    }

    private static Double windowSum(NavigableMap<YearMonth, Double> monthly, YearMonth end, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) {
            Double v = monthly.get(end.minusMonths(i));
            if (v == null) return null;
            sum += v;
        }
        return sum;
    }

    /** Drought severity class from a standardised index (SPI-like categories). */
    public static int severity(double z) {
        if (z > -0.5) return 0;
        if (z > -1.0) return 1;
        if (z > -1.5) return 2;
        if (z > -2.0) return 3;
        return 4;
    }

    public static String severityLabel(int severity) {
        return switch (severity) {
            case 0 -> "Normal or wet";
            case 1 -> "Slightly dry";
            case 2 -> "Dry (moderate)";
            case 3 -> "Severe drought";
            default -> "Extreme drought";
        };
    }
}
