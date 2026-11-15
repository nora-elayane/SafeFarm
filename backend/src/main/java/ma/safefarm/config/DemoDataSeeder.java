package ma.safefarm.config;

import ma.safefarm.entity.Farm;
import ma.safefarm.repository.FarmRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** With the zero-setup "h2" profile, start with one pilot farm so the demo is never empty. */
@Configuration
@Profile("h2")
public class DemoDataSeeder {
    @Bean
    CommandLineRunner seed(FarmRepository repo) {
        return args -> {
            if (repo.count() > 0) return;
            Farm f = new Farm();
            f.setName("Tadla pilot farm");
            f.setRegion("Béni Mellal — Tadla");
            f.setArea(12.5);
            f.setLatitude(32.34);
            f.setLongitude(-6.35);
            f.setSoilType("Clay loam");
            f.setWaterAvailability("Mixed");
            f.setCurrentCrop("Soft wheat");
            f.setPreviousCrop("Barley");
            f.setPriority("Save water");
            f.setEquipment("Drip irrigation");
            repo.save(f);
        };
    }
}
