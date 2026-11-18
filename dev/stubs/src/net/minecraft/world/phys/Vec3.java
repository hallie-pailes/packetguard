package net.minecraft.world.phys;
import net.minecraft.core.Vec3i;
public class Vec3 { public final double x,y,z; public Vec3(double x,double y,double z){this.x=x;this.y=y;this.z=z;} public static Vec3 atCenterOf(Vec3i v){return new Vec3(v.getX()+0.5,v.getY()+0.5,v.getZ()+0.5);} public double distanceToSqr(Vec3 o){double dx=x-o.x,dy=y-o.y,dz=z-o.z;return dx*dx+dy*dy+dz*dz;} }
