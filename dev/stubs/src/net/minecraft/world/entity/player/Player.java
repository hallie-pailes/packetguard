package net.minecraft.world.entity.player;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
public class Player { public AbstractContainerMenu containerMenu; public AbstractContainerMenu inventoryMenu; public double x,y,z; public double eyeHeight=1.62; public boolean alive=true; public boolean isAlive(){return alive;} public double reach=4.5; public boolean creative, spectator; public String name="Steve"; public UUID uuid=UUID.randomUUID(); public Level level;
 public Level level(){return level;} public boolean isSpectator(){return spectator;} public boolean isCreative(){return creative;} public Component getName(){return Component.literal(name);} public UUID getUUID(){return uuid;}
 public double distanceToSqr(double ox,double oy,double oz){double dx=x-ox,dy=y-oy,dz=z-oz;return dx*dx+dy*dy+dz*dz;} public Vec3 getEyePosition(){return new Vec3(x,y+eyeHeight,z);} public double blockInteractionRange(){return reach;} }
