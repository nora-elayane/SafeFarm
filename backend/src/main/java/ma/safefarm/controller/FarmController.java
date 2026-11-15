package ma.safefarm.controller;
import ma.safefarm.entity.Farm; import ma.safefarm.repository.FarmRepository; import ma.safefarm.service.*; import jakarta.validation.Valid; import org.springframework.web.bind.annotation.*; import java.util.*;
@RestController @RequestMapping("/api") public class FarmController {
 private final FarmRepository repo; private final AnalysisService analysis; private final RotationService rotations;
 public FarmController(FarmRepository r,AnalysisService a,RotationService s){repo=r;analysis=a;rotations=s;}
 @GetMapping("/farms") public List<Farm> all(){return repo.findAll();}
 @PostMapping("/farms") public Farm create(@Valid @RequestBody Farm f){return repo.save(f);}
 @PutMapping("/farms/{id}") public Farm update(@PathVariable Long id,@Valid @RequestBody Farm f){Farm old=repo.findById(id).orElseThrow(()->new NoSuchElementException("Farm not found")); f.setName(f.getName()); old.setName(f.getName());old.setRegion(f.getRegion());old.setArea(f.getArea());old.setLatitude(f.getLatitude());old.setLongitude(f.getLongitude());old.setSoilType(f.getSoilType());old.setWaterAvailability(f.getWaterAvailability());old.setCurrentCrop(f.getCurrentCrop());old.setPreviousCrop(f.getPreviousCrop());old.setPriority(f.getPriority());old.setEquipment(f.getEquipment());return repo.save(old);}
 @DeleteMapping("/farms/{id}") public Map<String,String> delete(@PathVariable Long id){repo.deleteById(id);return Map.of("message","Farm deleted");}
 @GetMapping("/farms/{id}/analysis") public Map<String,Object> analyze(@PathVariable Long id){Farm f=repo.findById(id).orElseThrow(()->new NoSuchElementException("Farm not found"));return analysis.analyze(f);}
 @GetMapping("/farms/{id}/rotations") public Map<String,Object> rotations(@PathVariable Long id){
  Farm f=repo.findById(id).orElseThrow(()->new NoSuchElementException("Farm not found"));
  Map<String,Object> climate=null; String warning=null;
  try{climate=analysis.analyze(f);}catch(Exception e){warning="NASA data unavailable ("+e.getMessage()+"): scenarios computed without a drought index.";}
  Map<String,Object> res=new LinkedHashMap<>(rotations.scenarios(f,climate)); if(warning!=null)res.put("warning",warning);
  if(climate!=null){res.put("droughtIndices",climate.get("indices"));}
  return res;
 }
}
