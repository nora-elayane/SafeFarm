package ma.safefarm.service;

import ma.safefarm.entity.Farm;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ClimateAnalysisTest {

    // Rough semi-arid monthly rain (mm) and daily Tmax/Tmin shape, similar to Tadla.
    private static final double[] RAIN = {45, 40, 45, 35, 20, 8, 1, 2, 12, 32, 45, 45};

    private Map<String, Map<String, Object>> synth(int startYear, LocalDate end, int dryFromYear, int dryFromMonth) {
        Map<String, Object> rain = new LinkedHashMap<>(), tmax = new LinkedHashMap<>(), tmin = new LinkedHashMap<>(),
                t2m = new LinkedHashMap<>(), rh = new LinkedHashMap<>(), sol = new LinkedHashMap<>();
        DateTimeFormatter f = DateTimeFormatter.BASIC_ISO_DATE;
        for (LocalDate d = LocalDate.of(startYear, 1, 1); !d.isAfter(end); d = d.plusDays(1)) {
            int m = d.getMonthValue();
            double yearFactor = 1 + 0.35 * Math.sin(d.getYear() * 1.7);
            double r = RAIN[m - 1] / d.lengthOfMonth() * yearFactor;
            boolean dry = d.getYear() > dryFromYear || (d.getYear() == dryFromYear && m >= dryFromMonth);
            if (dry) r *= 0.15;
            double season = Math.sin((d.getDayOfYear() - 110) / 365.0 * 2 * Math.PI);
            double hi = 25 + 11 * season, lo = 11 + 8 * season;
            String k = d.format(f);
            rain.put(k, r); tmax.put(k, hi); tmin.put(k, lo); t2m.put(k, (hi + lo) / 2);
            rh.put(k, 55.0); sol.put(k, 18.0);
        }
        rain.put("20200101", -999.0); // fill value must be ignored
        Map<String, Map<String, Object>> p = new HashMap<>();
        p.put("PRECTOTCORR", rain); p.put("T2M_MAX", tmax); p.put("T2M_MIN", tmin); p.put("T2M", t2m);
        p.put("RH2M", rh); p.put("ALLSKY_SFC_SW_DWN", sol);
        return p;
    }

    private Farm farm(String crop, String water, String priority, String soil) {
        Farm f = new Farm();
        f.setName("t"); f.setRegion("r"); f.setArea(10.0); f.setLatitude(32.34); f.setLongitude(-6.35);
        f.setCurrentCrop(crop); f.setPreviousCrop(crop); f.setWaterAvailability(water); f.setPriority(priority);
        f.setSoilType(soil);
        return f;
    }

    @Test
    void et0_is_realistic_for_tadla() {
        double jul = ClimateMath.hargreavesEt0(37, 20, 32.34, 196);
        double jan = ClimateMath.hargreavesEt0(16, 4, 32.34, 15);
        assertTrue(jul > 5 && jul < 9, "July ET0 " + jul);
        assertTrue(jan > 0.6 && jan < 2.2, "Jan ET0 " + jan);
    }

    @Test
    void normal_year_is_not_flagged_dry_and_drought_year_is() {
        AnalysisService svc = new AnalysisService(null);
        LocalDate end = LocalDate.of(2026, 12, 31);

        Map<String, Object> normal = svc.compute(farm("Soft wheat", "Mixed", "Save water", "Loam"),
                synth(2011, end, 9999, 1), "20110101", "20261231", "test");
        Map<?, ?> dn = (Map<?, ?>) normal.get("drought");
        assertEquals(Boolean.TRUE, dn.get("available"));
        assertTrue(((Number) dn.get("severity")).intValue() <= 1, "normal: " + dn);

        Map<String, Object> dry = svc.compute(farm("Soft wheat", "Mixed", "Save water", "Loam"),
                synth(2011, end, 2026, 7), "20110101", "20261231", "test");
        Map<?, ?> dd = (Map<?, ?>) dry.get("drought");
        assertTrue(((Number) dd.get("severity")).intValue() >= 3, "dry: " + dd);
        assertEquals(12, ((List<?>) dry.get("monthly")).size());
        assertEquals(12, ((List<?>) dry.get("recent")).size());
        double et0 = ((Number) dry.get("annualEt0Mm")).doubleValue();
        assertTrue(et0 > 1200 && et0 < 1900, "annual ET0 " + et0);
        System.out.println("DRY drought=" + dd + " indices=" + dry.get("indices"));
        System.out.println("irrigation=" + dry.get("irrigation"));
    }

    @Test
    void short_series_is_rejected() {
        AnalysisService svc = new AnalysisService(null);
        assertThrows(IllegalStateException.class, () -> svc.compute(
                farm("Orge", "Rain-fed", "", ""), synth(2022, LocalDate.of(2026, 12, 31), 9999, 1), "a", "b", "test"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void rotation_reacts_to_water_regime_and_drought() {
        AnalysisService svc = new AnalysisService(null);
        RotationService rot = new RotationService();
        LocalDate end = LocalDate.of(2026, 12, 31);
        Map<String, Object> dry = svc.compute(farm("Soft wheat", "Rain-fed", "Save water", "Loam"),
                synth(2011, end, 2026, 7), "a", "b", "t");

        Map<String, Object> rainfed = rot.scenarios(farm("Soft wheat", "Rain-fed", "Save water", "Loam"), dry);
        List<Map<String, Object>> s1 = (List<Map<String, Object>>) rainfed.get("scenarios");
        assertEquals(3, s1.size());
        String seq = (String) s1.get(1).get("sequence");
        assertFalse(seq.contains("Sugar beet") || seq.contains("Maize"), "rainfed must exclude thirsty crops: " + seq);
        assertFalse(seq.startsWith("Soft wheat → Durum") || seq.startsWith("Soft wheat → Barley"), "must break cereal family: " + seq);

        // the climate input must change the output: a drought-free context gives a different ranking score
        Map<String, Object> wet = svc.compute(farm("Soft wheat", "Irrigated", "Diversify income", "Loam"),
                synth(2011, end, 9999, 1), "a", "b", "t");
        Map<String, Object> irrigated = rot.scenarios(farm("Soft wheat", "Irrigated", "Diversify income", "Loam"), wet);
        List<Map<String, Object>> s2 = (List<Map<String, Object>>) irrigated.get("scenarios");
        assertNotEquals(s1.get(1).get("sequence"), s2.get(1).get("sequence"));
        System.out.println("RAINFED/DRY  : " + s1.get(1).get("sequence") + " | " + s1.get(2).get("sequence") + " scores " + s1.get(0).get("score") + "/" + s1.get(1).get("score") + "/" + s1.get(2).get("score"));
        System.out.println("IRRIGATED/WET: " + s2.get(1).get("sequence") + " | " + s2.get(2).get("sequence"));
        System.out.println(s1.get(1).get("reason"));

        // no climate -> still works, severity unknown
        Map<String, Object> none = rot.scenarios(farm("Soft wheat", "Mixed", "", ""), null);
        assertEquals(false, none.get("climateAvailable"));
    }
}
