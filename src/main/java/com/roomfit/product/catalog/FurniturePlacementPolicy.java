package com.roomfit.product.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Set;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FurniturePlacementPolicy(String installation,List<String> roles,List<String> supportTypes,
        Surface surface,Double mountBottom,String windowTreatment,Dimensions sourceDimensions) {
    public FurniturePlacementPolicy {
        if (!Set.of("FLOOR","WALL","WINDOW","SUPPORT","FLOOR_CEILING","FLOOR_OVERLAY").contains(installation))
            throw new IllegalArgumentException("Unknown furniture installation mode");
        roles=roles==null?List.of():List.copyOf(roles);
        supportTypes=supportTypes==null?List.of():List.copyOf(supportTypes);
        if (mountBottom!=null&&(!Double.isFinite(mountBottom)||mountBottom<0))throw new IllegalArgumentException("Invalid mounting height");
    }
    public record Surface(double height,double minX,double maxX,double minZ,double maxZ) {
        public Surface {if(!Double.isFinite(height)||height<0||!Double.isFinite(minX)||!Double.isFinite(maxX)
                ||!Double.isFinite(minZ)||!Double.isFinite(maxZ)||minX>=maxX||minZ>=maxZ)throw new IllegalArgumentException("Invalid furniture support surface");}
    }
    public record Dimensions(double width,double depth,double height) {
        public Dimensions {if(!Double.isFinite(width)||!Double.isFinite(depth)||!Double.isFinite(height)||width<=0||depth<=0||height<=0)throw new IllegalArgumentException("Invalid policy source dimensions");}
    }
    public static FurniturePlacementPolicy legacy(String type) {
        String support="monitor".equals(type)?"desk":"tv".equals(type)?"media_console":null;
        return new FurniturePlacementPolicy(support==null?"FLOOR":"SUPPORT",List.of(),support==null?List.of():List.of(support),null,null,null,null);
    }
}
