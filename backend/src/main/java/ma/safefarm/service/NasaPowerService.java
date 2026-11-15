package ma.safefarm.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches daily NASA POWER data (AG community) for a point.
 * The window is {@link #YEARS} years so that drought indices (SPI / SPEI) can be
 * computed against a meaningful historical baseline. Responses are cached because
 * POWER is a 0.5° grid: two parcels in the same cell return the same series.
 */
@Service
public class NasaPowerService {

    public static final int YEARS = 15;
    private static final Duration TTL = Duration.ofHours(6);
    private static final String PARAMS = "T2M,T2M_MAX,T2M_MIN,PRECTOTCORR,RH2M,ALLSKY_SFC_SW_DWN";

    private record Cached(Instant at, Map<String, Object> data) {}

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final RestClient client = RestClient.builder()
            .baseUrl("https://power.larc.nasa.gov/api/temporal/daily/point").build();

    public Map<String, Object> fetch(double lat, double lon) {
        String key = String.format("%.2f|%.2f", lat, lon);
        Cached c = cache.get(key);
        if (c != null && Duration.between(c.at(), Instant.now()).compareTo(TTL) < 0) return c.data();

        LocalDate end = LocalDate.now().minusDays(3);
        LocalDate start = end.minusYears(YEARS);
        String s = start.format(DateTimeFormatter.BASIC_ISO_DATE), e = end.format(DateTimeFormatter.BASIC_ISO_DATE);

        Map<?, ?> response = client.get().uri(uri -> uri
                .queryParam("parameters", PARAMS).queryParam("community", "AG")
                .queryParam("longitude", lon).queryParam("latitude", lat)
                .queryParam("start", s).queryParam("end", e).queryParam("format", "JSON").build())
                .retrieve().body(Map.class);

        if (response == null || !(response.get("properties") instanceof Map<?, ?> props)
                || !(props.get("parameter") instanceof Map<?, ?> params)) {
            throw new IllegalStateException("NASA POWER returned no usable data");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "NASA POWER");
        out.put("latitude", lat);
        out.put("longitude", lon);
        out.put("startDate", s);
        out.put("endDate", e);
        out.put("parameters", params);
        cache.put(key, new Cached(Instant.now(), out));
        return out;
    }
}
