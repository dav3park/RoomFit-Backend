package com.roomfit.placement;
import com.roomfit.product.catalog.GeneratedFurnitureCatalog;
import com.roomfit.room.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
class FurnitureInstallationPolicyTest {
 private Furniture item(String variant,String id){var p=GeneratedFurnitureCatalog.get().products().stream().filter(v->v.getVariantId().equals(variant)).findFirst().orElseThrow();return new Furniture(id,p.getType(),p.getName(),p.getWidth(),p.getDepth(),p.getHeight(),new Position(3,3),0,FurnitureStatus.RECOMMENDED,p.getProductId(),p.getStyleTags(),variant);}
 private Room room(){return new Room(null,6,6,2.7,"meter",List.of(),List.of());}
 @Test void cornerWindowTreatmentsFitWithoutFloorFurnitureInset(){
  var r=new Room(null,6,6,2.7,"meter",List.of(new Opening("corner","window","north",0,2,1.2,.9)),List.of());
  for(var variant:List.of("curtain-fabric","blind-wood")){
   var installed=FurnitureInstallationPolicy.windowCandidate(r,item(variant,"treatment"),r.getOpenings().getFirst());
   assertThat(installed).isNotNull();
   assertThat(installed.getWidth()).isCloseTo(variant.equals("curtain-fabric")?2.1:2,org.assertj.core.data.Offset.offset(1e-6));
   assertThat(new ValidationService().validate(r,List.of(installed)).isBoundaryValid()).isTrue();
   installed.setPosition(new Position(-1,installed.getPosition().getZ()));
   assertThat(new ValidationService().validate(r,List.of(installed)).isBoundaryValid()).isFalse();
  }
 }
 @Test void tabletopLampRequiresARealSurface(){var lamp=item("lamp-table","lamp");var desk=item("desk-compact","desk");
  assertThat(FurnitureInstallationPolicy.resolve(room(),List.of(lamp)).get("lamp").valid()).isFalse();
  var installed=FurnitureInstallationPolicy.resolve(room(),List.of(desk,lamp)).get("lamp");
  assertThat(installed.valid()).isTrue();assertThat(installed.minY()).isEqualTo(.73);assertThat(installed.supportId()).isEqualTo("desk");
 }
 @Test void supportTieBreakIsStableAndOverhangIsRejected(){var lamp=item("lamp-table","lamp");var a=item("desk-compact","a");var b=item("desk-compact","b");
  assertThat(FurnitureInstallationPolicy.supportFor(lamp,List.of(b,a)).orElseThrow().getId()).isEqualTo("a");
  lamp.setPosition(new Position(3.59,3));assertThat(FurnitureInstallationPolicy.supportFor(lamp,List.of(a))).isEmpty();
 }
 @Test void cabinetTopsAndCornerDesktopDoNotUseShelvesOrHutches(){
  var host=item("expansion-c070","host");var tv=item("tv-small","screen");
  assertThat(FurnitureInstallationPolicy.resolve(room(),List.of(host,tv)).get("screen").minY()).isEqualTo(.484);
  var desk=item("desk-corner","desk");var monitor=item("monitor-basic","monitor");monitor.setPosition(FurnitureInstallationPolicy.supportPosition(desk));
  assertThat(FurnitureInstallationPolicy.resolve(room(),List.of(desk,monitor)).get("monitor").minY()).isEqualTo(.755);
 }

 @Test void installedLampHeightBlocksAWindowEvenWhenItsOwnModelIsShort(){
  var desk=item("desk-compact","desk");var lamp=item("lamp-table","lamp");desk.setPosition(new Position(3,5.6));lamp.setPosition(new Position(3,5.6));
  var withWindow=new Room(null,6,6,2.7,"meter",List.of(new Opening("window","window","north",2,2,1.2,.9)),List.of());
  assertThat(new ValidationService().validate(withWindow,List.of(desk)).isWindowClearance()).isTrue();
  assertThat(new ValidationService().validate(withWindow,List.of(desk,lamp)).isWindowClearance()).isFalse();
 }
 @Test void removingASupportEvaluatesTheUnchangedDependent(){
  var desk=item("desk-compact","desk");var lamp=item("lamp-table","lamp");
  var result=new ValidationService().validateChange(room(),List.of(desk,lamp),List.of(lamp));
  assertThat(result.isBoundaryValid()).isFalse();
  assertThat(result.getIssues()).anyMatch(issue->issue.type()==ValidationIssue.Type.INSTALLATION_INVALID&&issue.furnitureId().equals("lamp"));
 }

}
