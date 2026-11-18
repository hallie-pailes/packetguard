package net.minecraft.world.inventory;
import java.util.*;
import net.minecraft.world.entity.player.Player;
public class AbstractContainerMenu { public int containerId; public List<Slot> slots = new ArrayList<>(); public boolean valid = true; public int resyncs;
 public boolean stillValid(Player p){return valid;} public void sendAllDataToRemote(){resyncs++;} }
