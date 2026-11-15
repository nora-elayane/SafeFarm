package ma.safefarm.config;
import org.springframework.context.annotation.Bean; import org.springframework.context.annotation.Configuration; import org.springframework.web.servlet.config.annotation.*;
@Configuration public class CorsConfig { @Bean WebMvcConfigurer cors(){ return new WebMvcConfigurer(){ public void addCorsMappings(CorsRegistry r){ r.addMapping("/api/**").allowedOriginPatterns("http://localhost:*","http://127.0.0.1:*").allowedMethods("GET","POST","PUT","DELETE","OPTIONS").allowedHeaders("*"); } }; } }
