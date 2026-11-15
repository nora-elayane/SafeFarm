package ma.safefarm.repository;
import ma.safefarm.entity.Farm; import org.springframework.data.jpa.repository.JpaRepository;
public interface FarmRepository extends JpaRepository<Farm,Long> {}
