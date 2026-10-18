package com.roomfit.placement;

import com.roomfit.agent.domain.*;
import com.roomfit.product.catalog.GeneratedFurnitureCatalog;
import com.roomfit.product.repository.MockProductRepository;
import com.roomfit.product.service.*;
import com.roomfit.room.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

class ExpandedFurniturePlacementTest {
    static Stream<String> models() {return GeneratedFurnitureCatalog.get().products().stream().map(p->p.getVariantId()).filter(id->id.startsWith("expansion-"));}
    @ParameterizedTest @MethodSource("models")
    void explicitlySelectedNewModelHasAValidInstallation(String variant) {
        var catalog=GeneratedFurnitureCatalog.get();
        var product=catalog.products().stream().filter(p->variant.equals(p.getVariantId())).findFirst().orElseThrow();
        var repo=new MockProductRepository();
        var service=new RuleBasedPlacementService(new MockProductService(repo,new PurchaseUrlHealthCheckService(repo)),new ProductRecommendationService(repo));
        var desk=catalog.products().stream().filter(p->"desk-compact".equals(p.getVariantId())).findFirst().orElseThrow();
        var support=new Furniture("support",desk.getType(),desk.getName(),desk.getWidth(),desk.getDepth(),desk.getHeight(),new Position(4,4),0,FurnitureStatus.EXISTING,desk.getProductId(),desk.getStyleTags(),desk.getVariantId());
        boolean needsSupport="SUPPORT".equals(catalog.placementPolicy(variant).orElseThrow().installation());
        var room=new Room(null,8,8,2.7,"meter",List.of(new Opening("window","window","north",2,2,1.2,.9)),needsSupport?List.of(support):List.of());
        var context=new AgentContext(1L,LifestyleGoal.RELAX_FOCUSED,List.of(DesignStyle.MODERN),List.of(product.getType()),List.of(),List.of(),List.of(product.getProductId()),List.of());
        var result=service.recommend(context,room);
        assertThat(result.getRecommendationStatus()).as(variant+" "+result.getUnplacedFurniture()).isEqualTo(RecommendationExecutionStatus.SUCCESS);
        var item=result.getRecommendedFurniture().stream().filter(f->variant.equals(f.getVariantId())).findFirst().orElseThrow();
        assertThat(item.getProductId()).isEqualTo(product.getProductId());
        assertThat(FurnitureInstallationPolicy.resolve(room,result.getRecommendedFurniture()).get(item.getId()).valid()).as(variant).isTrue();
        var validation=new ValidationService().validate(room,result.getRecommendedFurniture());
        assertThat(validation.isBoundaryValid()&&validation.isCollisionFree()&&validation.isDoorClearance()&&validation.isWindowClearance()).as(variant).isTrue();
    }
}
