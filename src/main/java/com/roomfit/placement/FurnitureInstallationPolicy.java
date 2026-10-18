package com.roomfit.placement;

import com.roomfit.product.catalog.FurniturePlacementPolicy;
import com.roomfit.product.catalog.GeneratedFurnitureCatalog;
import com.roomfit.room.*;
import java.util.*;

final class FurnitureInstallationPolicy {
    record Installation(boolean valid,String reason,String supportId,String openingId,double minY,double maxY) {}
    record Frame(double x,double z,double nx,double nz,double rotation) {}
    static FurniturePlacementPolicy policy(Furniture item) {
        // 스캔된 벽걸이 가구(상부장 등)는 카탈로그 policy 대신 스캔한 높이를 그대로 쓴다 —
        // minY/maxY가 바닥 가구와 겹치지 않으면 installationExempt로 충돌에서 빠진다.
        if(item.isWallMounted()&&item.getElevation()!=null)
            return new FurniturePlacementPolicy("WALL",List.of(),List.of(),null,item.getElevation(),null,null);
        return GeneratedFurnitureCatalog.get().placementPolicy(item.getVariantId()).orElseGet(()->FurniturePlacementPolicy.legacy(GeneratedFurnitureCatalog.get().normalizeType(item.getType())));
    }
    static Position supportPosition(Furniture support) {
        var p=policy(support);var surface=p.surface();if(surface==null)return support.getPosition();
        double sx=p.sourceDimensions()==null?1:support.getWidth()/p.sourceDimensions().width(),sz=p.sourceDimensions()==null?1:support.getDepth()/p.sourceDimensions().depth();
        var delta=FurnitureBoundary.rotateLocalOffset((surface.minX()+surface.maxX())/2*sx,(surface.minZ()+surface.maxZ())/2*sz,support.getRotation());
        return new Position(support.getPosition().getX()+delta.x(),support.getPosition().getZ()+delta.z());
    }
    static Optional<Furniture> supportFor(Furniture item,List<Furniture> items) {
        var p=policy(item);if(!"SUPPORT".equals(p.installation()))return Optional.empty();
        boolean screen=Set.of("monitor","tv").contains(GeneratedFurnitureCatalog.get().normalizeType(item.getType()));
        return items.stream().filter(other->other.getStatus()!=FurnitureStatus.DELETED&&!other.getId().equals(item.getId()))
            .filter(other->p.supportTypes().contains(GeneratedFurnitureCatalog.get().normalizeType(other.getType())))
            .filter(other->{
                var surface=policy(other).surface();if(surface==null&&(!screen||policy(other).sourceDimensions()!=null))return false;
                var source=policy(other).sourceDimensions();
                double sx=source==null?1:other.getWidth()/source.width(),sz=source==null?1:other.getDepth()/source.depth();
                var nominal=FurnitureBoundary.resolveLocalFootprint(other.getWidth(),other.getDepth(),other.getVariantId());
                double minX=surface==null?nominal.minX():surface.minX()*sx,maxX=surface==null?nominal.maxX():surface.maxX()*sx;
                double minZ=surface==null?nominal.minZ():surface.minZ()*sz,maxZ=surface==null?nominal.maxZ():surface.maxZ()*sz;
                var offsets=screen?List.of(new FurnitureBoundary.Offset(0,0)):FurnitureBoundary.footprint(item).corners();
                for(var offset:offsets){
                    double dx=item.getPosition().getX()+offset.x()-other.getPosition().getX(),dz=item.getPosition().getZ()+offset.z()-other.getPosition().getZ();
                    double r=Math.toRadians(other.getRotation()),x=dx*Math.cos(r)+dz*Math.sin(r),z=-dx*Math.sin(r)+dz*Math.cos(r);
                    if(x<minX-1e-6||x>maxX+1e-6||z<minZ-1e-6||z>maxZ+1e-6)return false;
                }return true;
            }).sorted(Comparator.comparingDouble((Furniture other)->Math.hypot(item.getPosition().getX()-other.getPosition().getX(),item.getPosition().getZ()-other.getPosition().getZ())).thenComparing(Furniture::getId)).findFirst();
    }
    static Map<String,Installation> resolve(Room room,List<Furniture> items) {
        Map<String,Installation> results=new HashMap<>();Set<String> used=new HashSet<>();
        for(var item:items.stream().filter(f->f.getStatus()!=FurnitureStatus.DELETED).sorted(Comparator.comparing(Furniture::getId)).toList()){
            var p=policy(item);String reason=null,supportId=null,openingId=null;double bottom=0;
            switch(p.installation()){
                case "SUPPORT" -> {
                    var support=supportFor(item,items).orElse(null);
                    if(support==null)reason="가구를 올려놓을 수 있는 받침과 상판 공간이 필요합니다.";
                    else {supportId=support.getId();var sp=policy(support);double ratio=sp.sourceDimensions()==null?1:support.getHeight()/sp.sourceDimensions().height();
                        bottom=("WALL".equals(sp.installation())&&sp.mountBottom()!=null?sp.mountBottom():0)+(sp.surface()==null?support.getHeight():sp.surface().height()*ratio);}
                }
                case "WALL" -> {bottom=p.mountBottom()==null?0:p.mountBottom();if(!item.isWallMounted()&&!nearWall(room,item))reason="벽에 설치할 위치가 필요합니다.";}
                case "WINDOW" -> {
                    for(var opening:room.getOpenings().stream().filter(o->"window".equals(o.getType())&&!used.contains(o.getId())).sorted(Comparator.comparing(Opening::getId)).toList()){
                        var target=windowCandidate(room,item,opening);if(target==null)continue;
                        if(Math.hypot(target.getPosition().getX()-item.getPosition().getX(),target.getPosition().getZ()-item.getPosition().getZ())<.025
                            &&Math.cos(Math.toRadians(target.getRotation()-item.getRotation()))>.999
                            &&Math.abs(target.getWidth()-item.getWidth())<.025&&Math.abs(target.getHeight()-item.getHeight())<.025){
                            openingId=opening.getId();used.add(openingId);bottom="BLIND".equals(p.windowTreatment())?windowBottom(room,opening):0;break;}
                    }if(openingId==null)reason="창문의 위치·방향·설치 크기에 맞게 배치해야 합니다.";
                }
                case "FLOOR_CEILING" -> {if(Math.abs(room.getHeight()-item.getHeight())>.01)reason="바닥·천장 고정형 가구와 방 높이가 맞지 않습니다.";}
                default -> { }
            }
            if(bottom+item.getHeight()>room.getHeight()+1e-6)reason="설치한 가구가 천장 높이를 넘습니다.";
            results.put(item.getId(),new Installation(reason==null,reason,supportId,openingId,bottom,bottom+item.getHeight()));
        }return results;
    }
    static Frame openingFrame(Room room,Opening opening){
        Wall wall=room.getWalls().stream().filter(w->w.getId().equals(opening.getWall())).findFirst().orElse(null);
        double x=0,z=0,dx=1,dz=0;
        if(wall!=null){x=wall.getStart().getX();z=wall.getStart().getZ();dx=wall.getEnd().getX()-x;dz=wall.getEnd().getZ()-z;}
        else switch(opening.getWall()){
            case "north" -> z=room.getDepth();case "south" -> { }
            case "east" -> {x=room.getWidth();dx=0;dz=1;}case "west" -> {dx=0;dz=1;}default -> {return null;}
        }
        double len=Math.hypot(dx,dz);if(len<1e-6)return null;dx/=len;dz/=len;
        double center=opening.getOffset()+opening.getWidth()/2;x+=dx*center;z+=dz*center;
        double nx=-dz,nz=dx;if(!FurnitureBoundary.containsPoint(room,x+nx*.3,z+nz*.3)){nx=-nx;nz=-nz;}
        return new Frame(x,z,nx,nz,(Math.toDegrees(Math.atan2(-nx,nz))+360)%360);
    }
    static Furniture windowCandidate(Room room,Furniture item,Opening opening){
        Frame frame=openingFrame(room,opening);if(frame==null)return null;var p=policy(item);boolean blind="BLIND".equals(p.windowTreatment());
        var wall=room.getWalls().stream().filter(w->w.getId().equals(opening.getWall())).findFirst().orElse(null);
        double ax=wall==null?(Set.of("east","west").contains(opening.getWall())?0:1):wall.getEnd().getX()-wall.getStart().getX();
        double az=wall==null?(Set.of("east","west").contains(opening.getWall())?1:0):wall.getEnd().getZ()-wall.getStart().getZ();
        double length=wall==null?(ax==0?room.getDepth():room.getWidth()):Math.hypot(ax,az),direction=Math.hypot(ax,az);
        if(direction<1e-6)return null;ax/=direction;az/=direction;
        double left=blind?0:Math.min(.1,Math.max(0,opening.getOffset()));
        double right=blind?0:Math.min(.1,Math.max(0,length-opening.getOffset()-opening.getWidth())),shift=(right-left)/2;
        double bottom=windowBottom(room,opening),width=opening.getWidth()+left+right,height=blind?opening.getHeight():Math.min(room.getHeight(),bottom+opening.getHeight()+.1);
        if(p.sourceDimensions()!=null&&(width/p.sourceDimensions().width()<.5||width/p.sourceDimensions().width()>2||height/p.sourceDimensions().height()<.5||height/p.sourceDimensions().height()>2))return null;
        double thickness=room.getWalls().stream().filter(w->w.getId().equals(opening.getWall())).mapToDouble(Wall::getThickness).findFirst().orElse(.12);
        double offset=item.getDepth()/2+thickness/2+.03;
        return new Furniture(item.getId(),item.getType(),item.getLabel(),width,item.getDepth(),height,new Position(frame.x()+frame.nx()*offset+ax*shift,frame.z()+frame.nz()*offset+az*shift),frame.rotation(),item.getStatus(),item.getProductId(),item.getStyleTags(),item.getVariantId()).copyMountFrom(item);
    }
    static boolean withinBoundary(Room room,Furniture item){
        if(!"WINDOW".equals(policy(item).installation()))return FurnitureBoundary.isInside(room,item);
        return FurnitureBoundary.footprint(item).corners().stream().allMatch(p->FurnitureBoundary.containsPoint(room,
                item.getPosition().getX()+p.x()*(1-1e-7),item.getPosition().getZ()+p.z()*(1-1e-7)));
    }
    static double windowBottom(Room room,Opening opening){
        double preferred=opening.getSillHeight()==null?1.45:opening.getSillHeight()+opening.getHeight()/2;
        double center=Math.min(room.getHeight()-opening.getHeight()/2-.05,Math.max(opening.getHeight()/2+.05,preferred));
        return center-opening.getHeight()/2;
    }
    private static boolean nearWall(Room room,Furniture item){
        double r=Math.toRadians(item.getRotation()),x=item.getPosition().getX()+Math.sin(r)*item.getDepth()/2,z=item.getPosition().getZ()-Math.cos(r)*item.getDepth()/2;
        if(room.getWalls().isEmpty())return Math.min(Math.min(x,room.getWidth()-x),Math.min(z,room.getDepth()-z))<.16;
        return room.getWalls().stream().anyMatch(w->{double dx=w.getEnd().getX()-w.getStart().getX(),dz=w.getEnd().getZ()-w.getStart().getZ(),len=Math.hypot(dx,dz);if(len<1e-6)return false;
            double t=((x-w.getStart().getX())*dx+(z-w.getStart().getZ())*dz)/(len*len);
            return t>=0&&t<=1&&Math.abs((x-w.getStart().getX())*dz-(z-w.getStart().getZ())*dx)/len<.16;});
    }
}
